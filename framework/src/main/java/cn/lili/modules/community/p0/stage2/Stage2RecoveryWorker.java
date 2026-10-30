package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.support.*;
import cn.lili.modules.goods.service.GoodsSkuService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Only owned S2 tables/Redis; durable leases + generation fencing, no HTTP-user impersonation. */
@Component @ConditionalOnProperty(name={"p0.stage2.enabled","p0.stage2.workers-enabled"},havingValue="true")
public class Stage2RecoveryWorker {
    private final Stage2Transactions locks;private final Stage1Db db;private final Stage2Settings settings;private final Stage2TradeService trades;private final Stage2PaymentService payments;private final PersistentPaymentMock mock;private final StringRedisTemplate redis;private final TransactionTemplate tx;private final Clock clock;
    private static final DefaultRedisScript<Long> PROJECT=new DefaultRedisScript<>("local old=redis.call('GET',KEYS[2]); if old and tonumber(old)>tonumber(ARGV[1]) then return 0 end; redis.call('SET',KEYS[1],ARGV[2]); redis.call('SET',KEYS[2],ARGV[1]); return 1",Long.class);
    public Stage2RecoveryWorker(Stage2Transactions locks,Stage1Db db,Stage2Settings settings,Stage2TradeService trades,Stage2PaymentService payments,PersistentPaymentMock mock,StringRedisTemplate redis,PlatformTransactionManager manager,@Qualifier("stage1Clock")Clock clock){this.locks=locks;this.db=db;this.settings=settings;this.trades=trades;this.payments=payments;this.mock=mock;this.redis=redis;this.tx=new TransactionTemplate(manager);this.clock=clock;}
    @Scheduled(fixedDelayString="${p0.stage2.recovery-delay-ms:1000}")
    public void tick(){
        try{attention();for(int i=0;i<30&&one();i++){/* bounded */}}catch(RuntimeException unavailable){/* durable rows retain their leases and next attempt */}
        try{for(var event:mock.deliveries()){payments.notification(event);mock.delivered(event.get("eventId").getAsString());}}catch(RuntimeException unavailable){/* channel deliveries remain pending */}
        try{for(int i=0;i<100&&invalidation();i++){/* bounded */}}catch(RuntimeException unavailable){/* outbox remains durable */}
    }
    public void attention(){db.update("UPDATE li_p0_trade_state SET attention_at=? WHERE payment_status='UNKNOWN' AND unknown_since<=? AND attention_at IS NULL",clock.instant(),clock.instant().minusSeconds(120));}
    public boolean one(){String lease=UUID.randomUUID().toString();var task=tx.execute(status->{var row=db.one("SELECT * FROM li_p0_s2_task WHERE run_id=? AND next_attempt_at<=? AND (status='PENDING' OR status='RUNNING' AND lease_until<=?) ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",settings.getRunId(),clock.instant(),clock.instant());if(row==null)return null;db.update("UPDATE li_p0_s2_task SET status='RUNNING',lease_owner=?,lease_until=?,attempts=attempts+1 WHERE id=?",lease,clock.instant().plusSeconds(30),row.get("id"));return row;});
        if(task==null)return false;boolean done=false;Instant due=clock.instant().plusSeconds(5);String reason=null;
        try{String type=id(task,"task_type"),target=id(task,"target_key");
            switch(type){
                case "INTENT"->{String[] key=target.split(":",2);trades.resume(key[0],key[1]);var row=db.required("SELECT status FROM li_p0_checkout_intent WHERE member_id=? AND intent_id=?",key[0],key[1]);done=Set.of("SUCCEEDED","FAILED").contains(id(row,"status"));}
                case "CART_CLEAN"->{var owner=db.required("SELECT member_id FROM li_p0_trade_state WHERE trade_id=?",target);locks.historical(id(owner,"member_id"),trades.stores(target),()->{for(var row:db.rows("SELECT * FROM li_p0_cart_consumption WHERE trade_id=? AND cleanup_status='PENDING' ORDER BY cart_id FOR UPDATE",target)){db.update("UPDATE li_p0_cart_item SET deleted=1 WHERE id=? AND version=? AND quantity=0",row.get("cart_id"),row.get("cleanup_version"));db.update("UPDATE li_p0_cart_consumption SET cleanup_status='DONE' WHERE cart_id=? AND cart_version=?",row.get("cart_id"),row.get("cart_version"));}return null;});done=true;}
                case "PAYMENT"->{payments.recover(target);var row=db.required("SELECT * FROM li_p0_trade_state WHERE trade_id=?",target);done=Set.of("PAID","CLOSED").contains(id(row,"status"))&&row.get("protection")==null;if(row.get("next_query_at")!=null)due=instant(row.get("next_query_at"));else if(row.get("unknown_since")==null)due=instant(row.get("pay_deadline"));if("LATE_PAYMENT_AFTER_RELEASE".equals(id(row,"protection")))due=clock.instant().plusSeconds(300);}
                case "INVENTORY_PROJECTION"->{project(target);done=true;}
                default->throw new Stage1Exception(409,"TASK_TYPE_UNKNOWN");
            }
        }catch(RuntimeException failure){reason=failure instanceof Stage1Exception e?e.code():"RECOVERY_DEPENDENCY_UNAVAILABLE";due=clock.instant().plusSeconds(Math.min(300,1L<<Math.min(8,number(task,"attempts"))));}
        final boolean finished=done;final Instant next=due;final String last=reason;
        tx.execute(status->{int count=db.update("UPDATE li_p0_s2_task SET status=?,next_attempt_at=?,lease_owner=NULL,lease_until=NULL,last_reason=? WHERE id=? AND lease_owner=? AND generation=?",finished?"DONE":"PENDING",next,last,task.get("id"),lease,task.get("generation"));if(count==0)db.update("UPDATE li_p0_s2_task SET status='PENDING',lease_owner=NULL,lease_until=NULL WHERE id=? AND lease_owner=?",task.get("id"),lease);return null;});return true;
    }
    public void project(String sku){var row=db.required("SELECT s.quantity,c.inventory_version FROM li_goods_sku s JOIN li_p0_sku_control c ON c.sku_id=s.id WHERE s.id=?",sku);String prefix="stage2:"+settings.getRunId()+":stock-version:";Long result=redis.execute(PROJECT,List.of(GoodsSkuService.getStockCacheKey(sku),prefix+sku),String.valueOf(number(row,"inventory_version")),String.valueOf(number(row,"quantity")));Stage1Exception.require(result!=null,503,"CACHE_PROJECTION_UNKNOWN");}
    private boolean invalidation(){return Boolean.TRUE.equals(tx.execute(status->{var row=db.one("SELECT * FROM li_p0_outbox WHERE status='PENDING' AND next_attempt_at<=? ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",clock.instant());if(row==null)return false;String key="stage2:"+settings.getRunId()+":invalidation:"+id(row,"event_type")+":"+id(row,"object_id");redis.execute(PROJECT,List.of(key,key+":version"),String.valueOf(number(row,"version")),String.valueOf(number(row,"version")));if("SKU_STOCK".equals(id(row,"event_type")))project(id(row,"object_id"));db.update("UPDATE li_p0_outbox SET status='DONE',attempts=attempts+1,completed_at=? WHERE id=?",clock.instant(),row.get("id"));return true;}));}
}
