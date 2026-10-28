package cn.lili.modules.community.p0.support;

import cn.lili.modules.community.p0.channel.ChannelService;
import cn.lili.modules.community.p0.channel.ReceiverService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.List;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Durable invalidation delivery; eligibility and authorization still read current database facts. */
@Component
@ConditionalOnProperty(name={"p0.stage1.enabled","p0.stage1.workers-enabled"},havingValue="true")
public class Stage1RecoveryWorker {
    private static final DefaultRedisScript<Long> INVALIDATE=new DefaultRedisScript<>("""
        if redis.call('EXISTS',KEYS[1]) == 1 then return 0 end
        local old=redis.call('HGET',KEYS[2],ARGV[1])
        if not old or tonumber(old)<tonumber(ARGV[2]) then redis.call('HSET',KEYS[2],ARGV[1],ARGV[2]) end
        redis.call('SET',KEYS[1],'1','EX',2592000)
        return 1
        """,Long.class);
    private final Stage1Db db;private final StringRedisTemplate redis;private final Stage1Settings settings;
    private final TransactionTemplate tx;private final Clock clock;private final ChannelService channels;private final ReceiverService receivers;
    public Stage1RecoveryWorker(Stage1Db db,StringRedisTemplate redis,Stage1Settings settings,PlatformTransactionManager manager,@Qualifier("stage1Clock") Clock clock,ChannelService channels,ReceiverService receivers){this.db=db;this.redis=redis;this.settings=settings;this.tx=new TransactionTemplate(manager);this.clock=clock;this.channels=channels;this.receivers=receivers;}
    @Scheduled(fixedDelayString="${p0.stage1.recovery-delay-ms:5000}",initialDelayString="${p0.stage1.recovery-delay-ms:5000}")
    public void run(){
        try{for(int i=0;i<100&&deliverOne();i++){/* bounded batch */}}catch(RuntimeException unavailable){/* Durable rows remain pending; no payload or secret in logs. */}
        try{channels.recoverInterrupted();}catch(RuntimeException unavailable){/* Channel operations retain their state. */}
        try{receivers.recoverInterrupted();}catch(RuntimeException unavailable){/* Reconciled on the next bounded pass. */}
    }
    public boolean deliverOne(){
        return Boolean.TRUE.equals(tx.execute(status->{
            var row=db.one("SELECT * FROM li_p0_outbox WHERE status='PENDING' AND next_attempt_at<=? ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",clock.instant());
            if(row==null)return false;
            try{
                String prefix="stage1:"+settings.getRunId()+":invalidation:";
                Long delivered=redis.execute(INVALIDATE,List.of(prefix+"receipt:"+id(row,"id"),prefix+"versions"),id(row,"event_type")+":"+id(row,"store_id")+":"+id(row,"object_id"),String.valueOf(number(row,"version")));
                if(delivered==null)throw new IllegalStateException("INVALIDATION_UNKNOWN");
                db.update("UPDATE li_p0_outbox SET status='DONE',attempts=attempts+1,completed_at=? WHERE id=?",clock.instant(),row.get("id"));
            }catch(RuntimeException unavailable){long attempts=number(row,"attempts")+1;db.update("UPDATE li_p0_outbox SET attempts=?,next_attempt_at=? WHERE id=?",attempts,clock.instant().plusSeconds(Math.min(300,1L<<Math.min(attempts,8))),row.get("id"));}
            return true;
        }));
    }
}
