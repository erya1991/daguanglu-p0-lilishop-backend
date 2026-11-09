package cn.lili.modules.community.p0.stage45;

import cn.lili.modules.community.p0.stage2.*;
import cn.lili.modules.community.p0.stage3.*;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service @ConditionalOnProperty(name="p0.stage45.enabled",havingValue="true")
public class Stage45Data {
    private final org.springframework.transaction.support.TransactionTemplate operationTx;
    final Stage1Db db;final Stage1Context context;final Stage45Authorization auth;
    final Stage3Orders orders;final Stage3Eligibility eligibility;final Stage45Gate gate;final Clock clock;
    public Stage45Data(Stage1Db db,Stage1Context context,Stage45Authorization auth,Stage3Orders orders,
            Stage3Eligibility eligibility,Stage45Gate gate,@Qualifier("stage1Clock")Clock clock,org.springframework.transaction.PlatformTransactionManager manager) {
        this.operationTx=new org.springframework.transaction.support.TransactionTemplate(manager);
        this.db=db;this.context=context;this.auth=auth;this.orders=orders;this.eligibility=eligibility;this.gate=gate;this.clock=clock;
    }
    public Map<String,Object> order(String id,String action) {
        var row=orders.row(id);auth.order(context.current(),row,action);return row;
    }
    public <T> T locked(Map<String,Object> order,Supplier<T> work) {
        @SuppressWarnings("unchecked") T result=(T)orders.locked(order,()->{
            db.update("INSERT IGNORE INTO li_p0_s45_order(order_id,updated_at) VALUES(?,?)",order.get("id"),clock.instant());
            db.required("SELECT * FROM li_p0_s45_order WHERE order_id=? FOR UPDATE",order.get("id"));
            return work.get();
        });return result;
    }
    public Object command(Map<String,Object> order,String action,String target,Object body,Supplier<Object> work) {
        gate.writes();var actor=context.current();String operation=Stage2Transactions.operation(new Fields(body));
        String digest=hash(map("action",action,"target",target,"body",body));String store=actor.store()==null?"0":actor.store();
        return locked(order,()->{
            context.recheck(actor);
            var previous=db.one("SELECT * FROM li_p0_s45_operation WHERE identity_domain=? AND actor_id=? AND store_scope=? AND operation_id=?",actor.domain().name(),actor.id(),store,operation);
            if(previous!=null){Stage1Exception.require(!"OP_ABANDONED".equals(id(previous,"action")),409,"OPERATION_ABANDONED");Stage1Exception.require(digest.equals(id(previous,"payload_hash")),409,"IDEMPOTENCY_CONFLICT");return object(previous.get("response_json"));}
            Object result=work.get();context.recheck(actor);
            db.update("INSERT INTO li_p0_s45_operation VALUES(?,?,?,?,?,?,?,?,?)",actor.domain().name(),actor.id(),store,operation,action,target,digest,write(result),clock.instant());
            return result;
        });
    }
    /** Account-level reconciliation has no owning order, including a day with no local requests. */
    public Object accountCommand(String action,String target,Object body,Supplier<Object> work) {
        gate.writes();var actor=context.require(cn.lili.common.security.session.SessionDomain.MANAGER);
        String operation=Stage2Transactions.operation(new Fields(body)),digest=hash(map("action",action,"target",target,"body",body));
        return operationTx.execute(status->{
            db.required("SELECT id FROM li_admin_user WHERE id=? FOR UPDATE",actor.id());context.recheck(actor);
            var previous=db.one("SELECT action,payload_hash,response_json FROM li_p0_s45_operation WHERE identity_domain='MANAGER' AND actor_id=? AND store_scope='0' AND operation_id=?",actor.id(),operation);
            if(previous!=null){Stage1Exception.require(!"OP_ABANDONED".equals(id(previous,"action")),409,"OPERATION_ABANDONED");Stage1Exception.require(digest.equals(id(previous,"payload_hash")),409,"IDEMPOTENCY_CONFLICT");return object(previous.get("response_json"));}
            Object result=work.get();context.recheck(actor);
            db.update("INSERT INTO li_p0_s45_operation VALUES('MANAGER',?,'0',?,?,?,?,?,?)",actor.id(),operation,action,target,digest,write(result),clock.instant());return result;
        });
    }
    public Object operation(String op) {
        var actor=context.current();
        var row=db.one("SELECT action,target_id,response_json FROM li_p0_s45_operation WHERE identity_domain=? AND actor_id=? AND store_scope=? AND operation_id=?",actor.domain().name(),actor.id(),actor.store()==null?"0":actor.store(),op);
        if(row!=null&&"OP_ABANDONED".equals(id(row,"action")))return map("status","ABANDONED");
        if(row==null){var inherited=db.one("SELECT action,response_json FROM li_p0_operation WHERE identity_domain=? AND actor_id=? AND request_id=?",actor.domain().name(),actor.id(),op);if(inherited!=null&&id(inherited,"action").startsWith("submerchant.query")){Stage1Exception.require(auth.permissions(actor.user()).contains("channel.read"),403,"FORBIDDEN");context.recheck(actor);return map("status","COMMITTED","result",object(inherited.get("response_json")));}}
        if(row!=null){String action=id(row,"action"),read=action.startsWith("funds.")?"funds.read":action.startsWith("aftersale.")?"aftersale.read":action.startsWith("evaluation.")?"evaluation.read":action.startsWith("reconciliation.")?"reconciliation.read":"attachment.read";Stage1Exception.require(auth.permissions(actor.user()).contains(read),403,"FORBIDDEN");}
        context.recheck(actor);return row==null?map("status","NOT_FOUND"):map("status","COMMITTED","result",object(row.get("response_json")));
    }
    public Object abandon(String op,Object body){
        gate.writes();new Fields(body).only();Stage2Transactions.operation(new Fields(map("operationId",op)));var actor=context.current();String scope=actor.store()==null?"0":actor.store();
        return operationTx.execute(status->{
            context.recheck(actor);
            var original=db.one("SELECT action,response_json FROM li_p0_s45_operation WHERE identity_domain=? AND actor_id=? AND store_scope=? AND operation_id=? FOR UPDATE",actor.domain().name(),actor.id(),scope,op);
            if(original!=null)return operation(op);
            // Native channel sync retains its original Stage1 journal. Fence both namespaces atomically.
            var nativeResult=db.one("SELECT action,response_json FROM li_p0_operation WHERE identity_domain=? AND actor_id=? AND request_id=? FOR UPDATE",actor.domain().name(),actor.id(),op);
            if(nativeResult!=null&&!id(nativeResult,"action").equals("OPERATION_ABANDONED"))return operation(op);
            db.update("INSERT IGNORE INTO li_p0_operation(identity_domain,actor_id,request_id,action,store_id,payload_hash,response_json,created_at) VALUES(?,?,?,'OPERATION_ABANDONED',?,?,'{}',?)",actor.domain().name(),actor.id(),op,actor.store(),hash(map("abandoned",op)),clock.instant());
            db.update("INSERT IGNORE INTO li_p0_s45_operation VALUES(?,?,?,?,'OP_ABANDONED',0,?,'{}',?)",actor.domain().name(),actor.id(),scope,op,hash(map("abandoned",op)),clock.instant());
            context.recheck(actor);return operation(op);
        });
    }
    public JsonObject facts(Map<String,Object> row,boolean normal) {
        if(normal)eligibility.require(id(row,"trade_id"));else eligibility.trustedPaid(id(row,"trade_id"));
        var snapshot=object(row.get("payload_json"));
        Stage1Exception.require(hash(snapshot).equals(id(row,"content_hash")),409,"SNAPSHOT_INVALID");
        var order=new Fields(snapshot.get("order"));var funds=new Fields(snapshot.get("funds"));
        long g=order.integer("goodsCent",0,Stage2Money.MAX_SAFE_CENT,true),f=order.integer("freightCent",0,Stage2Money.MAX_SAFE_CENT,true),paid=order.integer("amountCent",1,Stage2Money.MAX_SAFE_CENT,true);
        long b=funds.integer("rateBps",0,10000,true),commission=funds.integer("commissionCent",0,Stage2Money.MAX_SAFE_CENT,true);
        funds.id("ruleId",true);funds.id("agreementId",true);funds.integer("revision",1,Long.MAX_VALUE,true);
        Stage1Exception.require(g+f==paid&&commission==Stage45Money.commission(g,b),409,"FUNDS_SNAPSHOT_CONFLICT");
        long sum=0;for(var raw:order.list("items",1,200,true)){var line=new Fields(raw);long q=line.integer("quantity",1,99,true),unit=line.integer("unitPriceCent",0,Stage2Money.MAX_SAFE_CENT,true);sum=Math.addExact(sum,Math.multiplyExact(q,unit));}
        Stage1Exception.require(sum==g,409,"ITEM_ALLOCATION_UNEXPLAINED");return snapshot;
    }
    public Map<String,Object> item(Map<String,Object> order,String item) {
        return db.required("SELECT * FROM li_order_item WHERE id=? AND order_sn=? AND delete_flag=0",item,order.get("sn"));
    }
    public boolean sent(Map<String,Object> order) {
        var state=orders.state(order);String phase=state==null?"":id(state,"phase");
        return order.get("logistics_time")!=null||Set.of("DELIVERING","DELIVERED","SHIPPED","COMPLETED").contains(phase);
    }
    public boolean delivered(Map<String,Object> order) {
        var state=orders.state(order);return state!=null&&Set.of("DELIVERED","COMPLETED").contains(id(state,"phase"));
    }
    public void version(Map<String,Object> row,long expected) {Stage1Exception.require(number(row,"version")==expected,409,"VERSION_CONFLICT");}
    public void bump(String order) {
        db.update("UPDATE li_p0_s45_order SET version=version+1,updated_at=? WHERE order_id=?",clock.instant(),order);
        db.update("UPDATE li_p0_s3_fulfillment SET version=version+1,updated_at=? WHERE order_id=?",clock.instant(),order);
    }
    public long active(String order) {
        return db.count("SELECT COUNT(*) FROM li_p0_s45_aftersale WHERE order_id=? AND (state IN ('APPLY','WAIT_RETURN','WAIT_RECEIVE','WAIT_FUNDS') OR dispute IN ('OPEN','NEED_EVIDENCE'))",order);
    }
    public void audit(Map<String,Object> order,String subject,String target,String action,String reason,Object before,Object after) {
        var actor=context.current();
        db.update("INSERT INTO li_p0_s45_audit VALUES(?,?,?,?,?,?,?,?,?,?,?)",db.id(),order==null?null:order.get("id"),subject,target,actor.domain().name(),actor.id(),action,reason,before==null?null:write(before),write(after),clock.instant());
    }
    public void event(Map<String,Object> order,String subject,String target,String type,String permission,boolean resident,Object payload) {
        String event=db.id();
        db.update("INSERT INTO li_p0_s3_event(id,order_id,trade_id,store_id,member_id,version,event_type,actor_domain,payload_json,created_at,next_attempt_at) VALUES(?,?,?,?,?,?,?,'SYSTEM',?,?,?)",event,order.get("id"),order.get("trade_id"),order.get("store_id"),order.get("member_id"),event,type,write(payload),clock.instant(),clock.instant());
        db.update("INSERT INTO li_p0_s45_event_link VALUES(?,?,?,?,?)",event,subject,target,permission,resident);
    }
    public void issue(String order,String source,String code,Object detail) {
        operationTx.execute(status->{db.update("INSERT INTO li_p0_s45_exception(id,order_id,source_key,code,detail_json,created_at,updated_at) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE state='OPEN',detail_json=VALUES(detail_json),updated_at=VALUES(updated_at)",db.id(),order,source,code,write(detail),clock.instant(),clock.instant());
        var exception=db.required("SELECT id FROM li_p0_s45_exception WHERE source_key=? AND code=?",source,code);String json=write(detail);
        db.update("INSERT INTO li_p0_s45_exception_observation(exception_id,detail_hash,detail_json,first_observed_at,last_observed_at,observations) VALUES(?,SHA2(CAST(CAST(? AS JSON) AS CHAR),256),?,?,?,1) ON DUPLICATE KEY UPDATE last_observed_at=GREATEST(last_observed_at,VALUES(last_observed_at)),observations=observations+1",exception.get("id"),json,json,clock.instant(),clock.instant());return null;});
    }
    public static Map<String,Object> page(List<?> rows,long total,int page,int size){return map("records",rows,"total",total,"pageNumber",page,"pageSize",size);}
    public static int page(Map<String,String> q,String field,int defaultValue,int maximum) {
        try{int n=Integer.parseInt(q.getOrDefault(field,String.valueOf(defaultValue)));Stage1Exception.require(n>=1&&n<=maximum,422,"QUERY_INVALID");return n;}
        catch(NumberFormatException e){throw new Stage1Exception(422,"QUERY_INVALID");}
    }
}
