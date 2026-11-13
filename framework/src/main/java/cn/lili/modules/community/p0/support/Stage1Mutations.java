package cn.lili.modules.community.p0.support;

import cn.lili.common.security.session.SessionDomain;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.*;
import java.util.function.Supplier;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** One transaction for facts, journal, audit and invalidation. Lock: global guard, Admin, Members, Store, objects. */
@Service
@cn.lili.modules.community.p0.support.P0BusinessEnabled
public class Stage1Mutations {
    private final Stage1Db db;private final Stage1Context context;private final TransactionTemplate tx;private final Clock clock;
    public Stage1Mutations(Stage1Db db,Stage1Context context,PlatformTransactionManager manager,@Qualifier("stage1Clock") Clock clock){this.db=db;this.context=context;this.tx=new TransactionTemplate(manager);this.clock=clock;}
    public Object run(String action,Object body,String store,Supplier<Object> work){return run(action,body,store,List.of(),work);}
    public Object run(String action,Object body,String store,Collection<String> affectedMembers,Supplier<Object> work){
        var actor=context.current();var fields=new Fields(body);String request=fields.requestId();
        String hash=hash(map("action",action,"route",actor.method()+" "+actor.path(),"body",fields.json()));
        return tx.execute(status->{
            // Member-wide revocation must serialize with existing Stage1/2/3/45 transaction guards.
            boolean global=action.startsWith("channel-config.")||action.startsWith("receiver.")||action.startsWith("catalog.")||action.startsWith("resident.");
            db.required("SELECT id FROM li_p0_consistency_guard WHERE id=1 "+(global?"FOR UPDATE":"FOR SHARE"));
            if(actor.domain()==SessionDomain.MANAGER)db.required("SELECT id FROM li_admin_user WHERE id=? FOR UPDATE",actor.id());
            var members=new TreeSet<String>(Comparator.comparingLong(Long::parseLong));members.addAll(affectedMembers);
            if(actor.domain()!=SessionDomain.MANAGER)members.add(actor.id());
            for(String member:members)db.required("SELECT id FROM li_member WHERE id=? FOR UPDATE",member);
            if(store!=null)db.required("SELECT id FROM li_store WHERE id=? FOR UPDATE",store);
            context.recheck(actor);
            var old=db.one("SELECT action,payload_hash,response_json FROM li_p0_operation WHERE identity_domain=? AND actor_id=? AND request_id=?",actor.domain().name(),actor.id(),request);
            if(old!=null){Stage1Exception.require(!"OPERATION_ABANDONED".equals(id(old,"action")),409,"OPERATION_ABANDONED");Stage1Exception.require(hash.equals(id(old,"payload_hash")),409,"IDEMPOTENCY_CONFLICT");return context.receipt(id(old,"action"),old.get("response_json"));}
            Object result=work.get();
            context.recheck(actor);
            db.update("INSERT INTO li_p0_operation(identity_domain,actor_id,request_id,action,store_id,payload_hash,response_json,created_at) VALUES(?,?,?,?,?,?,?,?)",actor.domain().name(),actor.id(),request,action,store,hash,write(result),clock.instant());
            return result;
        });
    }
    public String audit(String action,String store,String object,String request,Long before,Long after,String reason){
        var actor=context.current();String id=db.id();
        db.update("INSERT INTO li_p0_audit(id,actor_id,identity_domain,action,store_id,object_id,request_id,before_version,after_version,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",id,actor.id(),actor.domain().name(),action,store,object,request,before,after,reason,clock.instant());return id;
    }
    public void invalidated(String store,String object,String type,long version){db.update("INSERT INTO li_p0_outbox(id,store_id,object_id,event_type,version,status,next_attempt_at,created_at) VALUES(?,?,?,?,?,'PENDING',?,?)",db.id(),store,object,type,version,clock.instant(),clock.instant());}
    public Object operation(String request){
        var actor=context.current();var row=db.required("SELECT action,store_id,response_json,created_at FROM li_p0_operation WHERE identity_domain=? AND actor_id=? AND request_id=?",actor.domain().name(),actor.id(),request);
        if(actor.domain()==SessionDomain.STORE&&!Objects.equals(actor.store(),id(row,"store_id")))throw new Stage1Exception(404,"RESOURCE_NOT_FOUND");
        return map("requestId",request,"action",row.get("action"),"status","OPERATION_ABANDONED".equals(id(row,"action"))?"ABANDONED":"COMMITTED","result",context.receipt(id(row,"action"),row.get("response_json")),"createdAt",instant(row.get("created_at")));
    }
    /** Close an uncertain receipt without guessing that a 404 means no request is still in flight.
     * The same actor lock serializes against every command: committed facts win, otherwise a tombstone
     * prevents a late original request from creating facts after the client unlocks its form. */
    public Object abandon(String request,Object body){
        var actor=context.current();Stage1Exception.require(actor.domain()!=SessionDomain.RESIDENT,403,"FORBIDDEN");
        var f=new Fields(body).only("requestId");Stage1Exception.require(request.equals(f.requestId()),422,"OPERATION_ID_MISMATCH");
        return tx.execute(status->{
            db.required("SELECT id FROM li_p0_consistency_guard WHERE id=1 FOR SHARE");
            db.required(actor.domain()==SessionDomain.MANAGER?"SELECT id FROM li_admin_user WHERE id=? FOR UPDATE":"SELECT id FROM li_member WHERE id=? FOR UPDATE",actor.id());
            if(actor.store()!=null)db.required("SELECT id FROM li_store WHERE id=? FOR UPDATE",actor.store());context.recheck(actor);
            var existing=db.one("SELECT request_id FROM li_p0_operation WHERE identity_domain=? AND actor_id=? AND request_id=?",actor.domain().name(),actor.id(),request);
            if(existing!=null)return operation(request);
            db.update("INSERT INTO li_p0_operation(identity_domain,actor_id,request_id,action,store_id,payload_hash,response_json,created_at) VALUES(?,?,?,'OPERATION_ABANDONED',?,?,'{}',?)",actor.domain().name(),actor.id(),request,actor.store(),hash(map("abandoned",request)),clock.instant());
            audit("operation.abandon",actor.store(),null,request,null,null,"Uncommitted request fenced by its original actor");context.recheck(actor);return operation(request);
        });
    }
}
