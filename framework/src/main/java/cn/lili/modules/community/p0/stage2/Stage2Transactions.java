package cn.lili.modules.community.p0.stage2;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.support.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service @ConditionalOnProperty(name="p0.stage2.enabled",havingValue="true")
public class Stage2Transactions {
    private final Stage1Db db; private final Stage1Context context; private final Stage2Settings settings;
    private final TransactionTemplate tx; private final Clock clock;
    public Stage2Transactions(Stage1Db db,Stage1Context context,Stage2Settings settings,PlatformTransactionManager manager,@Qualifier("stage1Clock") Clock clock) {
        this.db=db;this.context=context;this.settings=settings;this.tx=new TransactionTemplate(manager);this.clock=clock;
    }
    public String member() {
        String member=context.require(SessionDomain.RESIDENT).id();
        Stage1Exception.require(db.count("SELECT COUNT(*) FROM li_p0_s2_member_scope WHERE member_id=? AND run_id=? AND permission='TRANSACT'",member,settings.getRunId())==1,403,"STAGE2_TEST_SCOPE_REQUIRED");
        return member;
    }
    public static String operation(Fields f) {
        String id=f.text("operationId",8,64,true);
        Stage1Exception.require(id.matches("[A-Za-z0-9_-]+"),422,"OPERATION_ID_INVALID");return id;
    }
    public <T> T locked(String member,Collection<String> stores,Supplier<T> work) {
        return locked(member,stores,false,work);
    }
    public <T> T historical(String member,Collection<String> stores,Supplier<T> work) {
        return locked(member,stores,true,work);
    }
    private <T> T locked(String member,Collection<String> stores,boolean historical,Supplier<T> work) {
        return tx.execute(status->{
            db.required("SELECT id FROM li_p0_consistency_guard WHERE id=1 FOR SHARE");
            db.required(historical?"SELECT id FROM li_member WHERE id=? FOR UPDATE":"SELECT id FROM li_member WHERE id=? AND delete_flag=0 AND disabled=1 FOR UPDATE",member);
            var sorted=new TreeSet<String>(Comparator.comparingLong(Long::parseLong));sorted.addAll(stores);
            for(String store:sorted)db.required("SELECT id FROM li_store WHERE id=? FOR UPDATE",store);
            return work.get();
        });
    }
    public Object command(String action,Object body,Collection<String> stores,Supplier<Object> work) {
        var actor=context.require(SessionDomain.RESIDENT);String member=member(),op=operation(new Fields(body));String digest=hash(map("action",action,"body",body));
        return locked(member,stores,()->{
            context.recheck(actor);
            var old=db.one("SELECT payload_hash,response_json FROM li_p0_cart_operation WHERE member_id=? AND operation_id=?",member,op);
            if(old!=null){Stage1Exception.require(digest.equals(id(old,"payload_hash")),409,"IDEMPOTENCY_CONFLICT");return object(old.get("response_json"));}
            Object result=work.get();context.recheck(actor);
            db.update("INSERT INTO li_p0_cart_operation VALUES(?,?,?,?,?)",member,op,digest,write(result),clock.instant());return result;
        });
    }
    public void task(String type,String target) {
        db.update("INSERT INTO li_p0_s2_task(id,run_id,task_type,target_key,next_attempt_at,created_at) VALUES(?,?,?,?,?,?) ON DUPLICATE KEY UPDATE generation=generation+1,status=IF(status='DONE','PENDING',status),next_attempt_at=LEAST(next_attempt_at,VALUES(next_attempt_at))",db.id(),settings.getRunId(),type,target,clock.instant(),clock.instant());
    }
}
