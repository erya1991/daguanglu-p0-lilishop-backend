package cn.lili.modules.community.p0.channel;
import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.agreement.AgreementService;
import cn.lili.modules.community.p0.support.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Platform receiver master data, referenced by Stores; this class never creates a profit-sharing order. */
@Service
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
public class ReceiverService {
    private final Stage1Db db;private final Stage1Context context;private final Stage1Access access;private final Stage1Mutations mutations;private final ChannelConfigService configs;private final ChannelVault vault;private final TransactionTemplate tx;private final Clock clock;
    public ReceiverService(Stage1Db db,Stage1Context context,Stage1Access access,Stage1Mutations mutations,ChannelConfigService configs,ChannelVault vault,PlatformTransactionManager manager,@Qualifier("stage1Clock") Clock clock){this.db=db;this.context=context;this.access=access;this.mutations=mutations;this.configs=configs;this.vault=vault;this.tx=new TransactionTemplate(manager);this.clock=clock;}
    public Object create(Object body){
        context.require(SessionDomain.MANAGER);var f=new Fields(body).only("requestId","configId","displayName","accountReference","mockAccount","reason");String config=f.id("configId",true),name=f.text("displayName",2,120,true),reason=f.text("reason",1,500,true);
        return mutations.run("receiver.create",body,null,()->{
            var row=configs.raw(config);String mode=id(row,"mode"),account,reference;
            if(mode.equals("MOCK")){Stage1Exception.require(!f.has("accountReference"),422,"MOCK_REFERENCE_INVALID");account=f.text("mockAccount",6,64,true);Stage1Exception.require(account.matches("MOCK_[A-Za-z0-9_]{1,58}"),422,"MOCK_ACCOUNT_REQUIRED");reference=vault.put(map("account",account,"name",name));}
            else{Stage1Exception.require(!f.has("mockAccount"),422,"REAL_ACCOUNT_REQUIRED");reference=f.text("accountReference",1,64,true);var secret=new Fields(vault.credential(reference)).only("account","name");account=secret.text("account",8,32,true);Stage1Exception.require(account.matches("[0-9]{8,32}")&&name.equals(secret.text("name",2,120,true)),422,"RECEIVER_ACCOUNT_MISMATCH");reference="DEPLOY:"+reference;}
            String hash=digest(account.getBytes(java.nio.charset.StandardCharsets.UTF_8));Stage1Exception.require(db.count("SELECT COUNT(*) FROM li_p0_receiver_relation WHERE config_id=? AND mode=? AND receiver_type='MERCHANT_ID' AND account_hash=?",config,mode,hash)==0,409,"RECEIVER_ALREADY_EXISTS");String id=db.id();
            db.update("INSERT INTO li_p0_receiver_relation(id,config_id,mode,receiver_type,account_ref,account_hash,display_name,relation_type,source_kind,state,reason) VALUES(?,?,?,'MERCHANT_ID',?,?,?,'PLATFORM',?,'UNVERIFIED',?)",id,config,mode,reference,hash,name,mode,reason);mutations.audit("receiver.create",null,id,f.requestId(),null,0L,reason);return view(raw(id));
        });
    }
    public Object command(String id,Object body,String action){
        context.require(SessionDomain.MANAGER);var f=new Fields(body).only("requestId","expectedVersion","reason");String reason=f.text("reason",1,500,true);
        Object queued=mutations.run("receiver."+action+":"+id,body,null,()->{
            var row=raw(id);Stage1Exception.require(number(row,"version")==f.version(),409,"VERSION_CONFLICT");var config=configs.raw(id(row,"config_id"));String operation=null;
            if(action.equals("disable"))db.update("UPDATE li_p0_receiver_relation SET enabled=0,version=version+1,reason=? WHERE id=?",reason,id);
            else if(action.equals("enable")){Stage1Exception.require(flag(config,"enabled")&&ReceiverEvidence.current(row,id(row,"mode"),number(config,"config_version"),clock.instant()),409,"RECEIVER_EVIDENCE_NOT_READY");db.update("UPDATE li_p0_receiver_relation SET enabled=1,version=version+1,reason=? WHERE id=?",reason,id);}
            else if(action.equals("sync")){
                Stage1Exception.require(flag(config,"enabled"),409,"CONFIG_DISABLED");operation=db.id();long seq=number(row,"request_seq")+1;
                db.update("INSERT INTO li_p0_channel_operation(id,action,request_hash,request_seq,config_version,status,object_type,object_id,actor_id,request_id,relation_version,created_at) VALUES(?,'RECEIVER_SYNC',?,?,?,'QUEUED','RECEIVER',?,?,?,?,?)",operation,hash(f.json()),seq,config.get("config_version"),id,context.current().id(),f.requestId(),f.version()+1,clock.instant());
                db.update("UPDATE li_p0_receiver_relation SET state='UNKNOWN',request_seq=?,version=version+1,reason=? WHERE id=?",seq,reason,id);
            }else throw new Stage1Exception(404,"RESOURCE_NOT_FOUND");
            invalidate(id);mutations.audit("receiver."+action,null,id,f.requestId(),f.version(),f.version()+1,reason);return map("operationId",operation);
        });
        String operation=new Fields(queued).id("operationId",false);if(operation!=null)dispatch(operation);return map("receiver",view(raw(id)),"operationId",operation);
    }
    public void dispatch(String operation){
        var op=tx.execute(status->{db.required("SELECT id FROM li_p0_consistency_guard WHERE id=1 FOR UPDATE");var current=db.required("SELECT * FROM li_p0_channel_operation WHERE id=? AND object_type='RECEIVER'",operation);db.one("SELECT id FROM li_admin_user WHERE id=? FOR UPDATE",current.get("actor_id"));if(!"QUEUED".equals(id(current,"status")))return null;if(!permitted(id(current,"actor_id"))){db.update("UPDATE li_p0_channel_operation SET status='FAILED',failure_code='AUTHORIZATION_REVOKED',completed_at=? WHERE id=?",clock.instant(),operation);return null;}var row=raw(id(current,"object_id"));if(!matches(current,row,configs.raw(id(row,"config_id")))){db.update("UPDATE li_p0_channel_operation SET status='STALE',completed_at=? WHERE id=?",clock.instant(),operation);return null;}db.update("UPDATE li_p0_channel_operation SET status='DISPATCHING',attempt=attempt+1 WHERE id=?",operation);return current;});
        if(op==null)return;String error=null,ref=null;boolean verified=false;
        try{var row=raw(id(op,"object_id"));var config=configs.raw(id(row,"config_id"));String reference=id(row,"account_ref");var secret=new Fields(reference.startsWith("DEPLOY:")?vault.credential(reference.substring(7)):vault.get(reference));String account=secret.text("account",1,64,true);var payload=object(map("appid",config.get("payment_appid"),"type","MERCHANT_ID","account",account,"name",secret.text("name",2,120,true),"relation_type","PLATFORM"));var response=configs.port(config).ensureReceiverRelation(payload);ref=vault.put(response);var f=new Fields(response);verified="MERCHANT_ID".equals(f.text("type",1,24,true))&&account.equals(f.text("account",1,64,true));if(!verified)error="RECEIVER_RESPONSE_MISMATCH";}catch(ChannelFailure failure){error=failure.code();}catch(RuntimeException unavailable){error="RECEIVER_RESPONSE_UNKNOWN";}
        finish(op,verified,ref,error);
    }
    private void finish(Map<String,Object> op,boolean verified,String ref,String error){
        tx.executeWithoutResult(status->{db.required("SELECT id FROM li_p0_consistency_guard WHERE id=1 FOR UPDATE");db.one("SELECT id FROM li_admin_user WHERE id=? FOR UPDATE",op.get("actor_id"));var row=raw(id(op,"object_id"));var config=configs.raw(id(row,"config_id"));if(!"DISPATCHING".equals(id(db.required("SELECT status FROM li_p0_channel_operation WHERE id=? FOR UPDATE",op.get("id")),"status")))return;
            if(!matches(op,row,config)||!permitted(id(op,"actor_id"))){db.update("UPDATE li_p0_channel_operation SET status='STALE',response_ref=?,failure_code=?,completed_at=? WHERE id=?",ref,error,clock.instant(),op.get("id"));return;}
            db.update("UPDATE li_p0_receiver_relation SET state=?,trust_source=?,verified_at=?,config_version=?,version=version+1,reason=? WHERE id=?",verified?"VERIFIED":"UNKNOWN",verified?("MOCK".equals(id(row,"mode"))?"MOCK_CONTRACT":"API_REGISTERED_VERIFIED"):"UNVERIFIED",verified?clock.instant():null,op.get("config_version"),error,row.get("id"));
            db.update("UPDATE li_p0_channel_operation SET status=?,response_ref=?,failure_code=?,safe_response=?,completed_at=? WHERE id=?",verified?"SUCCEEDED":"UNKNOWN",ref,error,write(map("verified",verified,"sourceMode",row.get("mode"),"code",error)),clock.instant(),op.get("id"));invalidate(id(row,"id"));
        });
    }
    private boolean permitted(String actor){try{return access.managerPermissions(actor).contains("channel.receiver");}catch(RuntimeException failure){return false;}}
    private boolean matches(Map<String,Object> op,Map<String,Object> row,Map<String,Object> config){return number(op,"request_seq")==number(row,"request_seq")&&number(op,"relation_version")==number(row,"version")&&number(op,"config_version")==number(config,"config_version")&&flag(config,"enabled");}
    private void invalidate(String receiver){for(var row:db.rows("SELECT id,store_id,version FROM li_p0_submerchant_relation WHERE receiver_id=?",receiver))mutations.invalidated(id(row,"store_id"),id(row,"id"),"RECEIVER_EVIDENCE",number(row,"version"));}
    public void recoverInterrupted(){for(var op:db.rows("SELECT * FROM li_p0_channel_operation WHERE object_type='RECEIVER' AND status='DISPATCHING' AND created_at<?",clock.instant().minusSeconds(120)))finish(op,false,null,"RECEIVER_INTERRUPTED_UNKNOWN");for(var op:db.rows("SELECT id FROM li_p0_channel_operation WHERE object_type='RECEIVER' AND status='QUEUED' ORDER BY created_at LIMIT 20"))dispatch(id(op,"id"));}
    private Map<String,Object> raw(String id){var row=db.required("SELECT * FROM li_p0_receiver_relation WHERE id=?",id);configs.raw(id(row,"config_id"));return row;}
    public Object detail(String id){context.require(SessionDomain.MANAGER);var row=raw(id);return map("receiver",view(row),"operations",db.rows("SELECT id,action,status,failure_code,safe_response,created_at,completed_at FROM li_p0_channel_operation WHERE object_type='RECEIVER' AND object_id=? ORDER BY created_at DESC LIMIT 100",id).stream().map(r->map("id",id(r,"id"),"action",r.get("action"),"status",r.get("status"),"failureCode",r.get("failure_code"),"createdAt",instant(r.get("created_at")),"completedAt",instant(r.get("completed_at")))).toList());}
    public Object list(String config,int page,int size){context.require(SessionDomain.MANAGER);AgreementService.page(page,size);if(config!=null)configs.raw(config);return map("total",db.count("SELECT COUNT(*) FROM li_p0_receiver_relation WHERE (? IS NULL OR config_id=?)",config,config),"records",db.rows("SELECT * FROM li_p0_receiver_relation WHERE (? IS NULL OR config_id=?) ORDER BY id DESC LIMIT ? OFFSET ?",config,config,size,(page-1)*size).stream().map(this::view).toList());}
    private Object view(Map<String,Object> row){return map("id",id(row,"id"),"configId",id(row,"config_id"),"mode",row.get("mode"),"receiverType",row.get("receiver_type"),"displayName",row.get("display_name"),"accountProtected",true,"relationType",row.get("relation_type"),"state",row.get("state"),"trustSource",row.get("trust_source"),"enabled",flag(row,"enabled"),"verifiedAt",instant(row.get("verified_at")),"version",row.get("version"),"reason",row.get("reason"));}
}
