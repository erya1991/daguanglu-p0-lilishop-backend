package cn.lili.modules.community.p0.channel;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.agreement.AgreementService;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.JsonObject;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Non-funding onboarding. Commands commit before HTTP; versioned results cannot restore newer restrictions. */
@Service
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
public class ChannelService {
    private final Stage1Db db;private final Stage1Context context;private final Stage1Access access;private final Stage1Mutations mutations;private final ChannelConfigService configs;private final ChannelMaterials materials;private final ChannelVault vault;private final Stage1Files files;private final Stage1Settings settings;private final TransactionTemplate tx;private final Clock clock;
    public ChannelService(Stage1Db db,Stage1Context context,Stage1Access access,Stage1Mutations mutations,ChannelConfigService configs,ChannelMaterials materials,ChannelVault vault,Stage1Files files,Stage1Settings settings,PlatformTransactionManager manager,@Qualifier("stage1Clock") Clock clock){this.db=db;this.context=context;this.access=access;this.mutations=mutations;this.configs=configs;this.materials=materials;this.vault=vault;this.files=files;this.settings=settings;this.tx=new TransactionTemplate(manager);this.clock=clock;}
    public Object create(Object body){
        context.require(SessionDomain.MANAGER);var f=new Fields(body).only("requestId","storeId","configId","predecessorId","existingSubMchid","reason");String store=f.id("storeId",true),config=f.id("configId",true),predecessor=f.id("predecessorId",false),manual=f.text("existingSubMchid",1,64,false),reason=f.text("reason",1,500,true);
        return mutations.run("submerchant.create",body,store,()->{
            db.required("SELECT store_id FROM li_p0_store_control WHERE store_id=? AND admission_state='APPROVED'",store);var configRow=configs.raw(config);String mode=id(configRow,"mode"),key=storeKey(store,mode);
            if(manual!=null)Stage1Exception.require(mode.equals("MOCK")?manual.startsWith("MOCK_SUB_"):manual.matches("[0-9]{8,32}"),422,"SUBMERCHANT_FORMAT_INVALID");
            if(predecessor!=null){var old=raw(predecessor);Stage1Exception.require(store.equals(id(old,"store_id"))&&mode.equals(id(old,"mode")),404,"RESOURCE_NOT_FOUND");db.update("UPDATE li_p0_submerchant_relation SET enabled=0,active_store_key=NULL,active_account_key=NULL,version=version+1,updated_at=? WHERE id=?",clock.instant(),predecessor);mutations.invalidated(store,predecessor,"CHANNEL_RELATION",number(old,"version")+1);}
            Stage1Exception.require(db.count("SELECT COUNT(*) FROM li_p0_submerchant_relation WHERE active_store_key=?",key)==0,409,"CHANNEL_RELATION_CONFLICT");String id=db.id(),application=db.id();
            db.update("INSERT INTO li_p0_submerchant_relation(id,store_id,config_id,config_version,mode,sub_mchid,application_id,capability_state,last_sync_state,active_store_key,predecessor_id,provider_snapshot,appid_snapshot,created_at,updated_at) VALUES(?,?,?,?,?,?,?,'UNVERIFIED','NEVER',?,?,?,?,?,?)",id,store,config,number(configRow,"config_version"),mode,manual,application,key,predecessor,configRow.get("provider_mchid"),configRow.get("payment_appid"),clock.instant(),clock.instant());
            db.update("INSERT INTO li_p0_channel_application(id,relation_id,config_id,mode,out_request_no,revision,state,safe_material_summary,created_at) VALUES(?,?,?,?,?,0,'DRAFT','{}',?)",application,id,config,mode,"S1_"+application,clock.instant());mutations.audit("submerchant.create",store,id,f.requestId(),null,0L,reason);return view(raw(id));
        });
    }
    public Object saveMaterials(String id,Object body){
        context.require(SessionDomain.MANAGER);var f=new Fields(body).only("requestId","expectedVersion","materials","reason");String store=id(raw(id),"store_id"),reason=f.text("reason",1,500,true);
        return mutations.run("submerchant.materials:"+id,body,store,()->{
            var relation=raw(id);version(relation,f.version());var app=application(relation);Stage1Exception.require(Set.of("DRAFT","NEED_CORRECTION").contains(id(app,"state")),409,"QUERY_BEFORE_MATERIAL_CHANGE");
            var previous=app.get("encrypted_payload_ref")==null?new JsonObject():vault.get(id(app,"encrypted_payload_ref"));var data=materials.validate(materials.merge(previous,f.child("materials",true)),store,context.current().id(),false);String ref=vault.put(data),hash=hash(data);int revision=(int)number(app,"revision")+1;materials.bind(data,store,context.current().id(),id(app,"id"));
            db.update("INSERT INTO li_p0_channel_material_revision(application_id,revision,encrypted_ref,material_hash,safe_summary,actor_id,created_at) VALUES(?,?,?,?,?,?,?)",app.get("id"),revision,ref,hash,write(materials.safeSummary(data)),context.current().id(),clock.instant());
            db.update("UPDATE li_p0_channel_application SET revision=?,material_hash=?,encrypted_payload_ref=?,safe_material_summary=? WHERE id=?",revision,hash,ref,write(materials.safeSummary(data)),app.get("id"));
            db.update("UPDATE li_p0_submerchant_relation SET material_state='DRAFT',material_version=material_version+1,version=version+1,updated_at=? WHERE id=?",clock.instant(),id);mutations.audit("submerchant.materials",store,id,f.requestId(),f.version(),f.version()+1,reason);return view(raw(id));
        });
    }
    public Object command(String id,Object body,String action){
        context.require(SessionDomain.MANAGER);var f=new Fields(body).only("requestId","expectedVersion","reason");String store=id(raw(id),"store_id"),reason=f.text("reason",1,500,true);
        Object result=mutations.run("submerchant."+action+":"+id,body,store,()->{
            var relation=raw(id);version(relation,f.version());var config=configs.raw(id(relation,"config_id"));var app=application(relation);String operation=null;
            if(action.equals("enable")){
                Stage1Exception.require(flag(config,"enabled")&&number(config,"config_version")==number(relation,"config_version")&&"COMPLETED".equals(id(app,"state"))&&"VERIFIED".equals(id(relation,"capability_state"))&&"SUCCESS".equals(id(relation,"last_sync_state"))&&fresh(relation.get("verified_at")),409,"CHANNEL_EVIDENCE_NOT_READY");Stage1Exception.require(relation.get("active_store_key")!=null,409,"RELATION_REPLACED");
                db.update("UPDATE li_p0_submerchant_relation SET enabled=1,version=version+1,updated_at=? WHERE id=?",clock.instant(),id);
            }else if(action.equals("disable"))db.update("UPDATE li_p0_submerchant_relation SET enabled=0,version=version+1,updated_at=? WHERE id=?",clock.instant(),id);
            else if(Set.of("submit","query").contains(action)){
                Stage1Exception.require(flag(config,"enabled")&&"VALID".equals(id(config,"check_state")),409,"CONFIG_DISABLED");Stage1Exception.require(Objects.equals(config.get("provider_mchid"),relation.get("provider_snapshot"))&&Objects.equals(config.get("payment_appid"),relation.get("appid_snapshot")),409,"CHANNEL_IDENTITY_CHANGED_REPLACE_RELATION");Stage1Exception.require(relation.get("active_store_key")!=null,409,"RELATION_REPLACED");
                if(action.equals("submit")){Stage1Exception.require(Set.of("DRAFT","NEED_CORRECTION").contains(id(app,"state")),409,"QUERY_EXISTING_APPLICATION");materials.validate(vault.get(id(app,"encrypted_payload_ref")),store,context.current().id(),true);}
                else Stage1Exception.require(!"DRAFT".equals(id(app,"state")),409,"APPLICATION_NOT_SUBMITTED");
                long seq=number(relation,"request_seq")+1;operation=db.id();
                db.update("INSERT INTO li_p0_channel_operation(id,application_id,action,request_hash,request_seq,config_version,status,object_type,object_id,actor_id,request_id,relation_version,material_revision,created_at) VALUES(?,?,?,?,?,?,'QUEUED','APPLICATION',?,?,?,?,?,?)",operation,app.get("id"),action.toUpperCase(Locale.ROOT),hash(f.json()),seq,number(config,"config_version"),id,context.current().id(),f.requestId(),f.version()+1,number(app,"revision"),clock.instant());
                db.update("UPDATE li_p0_submerchant_relation SET request_seq=?,config_version=?,last_sync_state='UNKNOWN',capability_state='UNVERIFIED',version=version+1,updated_at=? WHERE id=?",seq,number(config,"config_version"),clock.instant(),id);
                if(action.equals("submit"))db.update("UPDATE li_p0_channel_application SET state='SUBMITTING' WHERE id=?",app.get("id"));
            }else throw new Stage1Exception(404,"RESOURCE_NOT_FOUND");
            mutations.invalidated(store,id,"CHANNEL_RELATION",f.version()+1);mutations.audit("submerchant."+action,store,id,f.requestId(),f.version(),f.version()+1,reason);return map("id",id,"operationId",operation,"version",f.version()+1);
        });
        String operation=new Fields(result).id("operationId",false);if(operation!=null)dispatch(operation);
        return map("relation",view(raw(id)),"operation",operation==null?null:operationView(db.required("SELECT * FROM li_p0_channel_operation WHERE id=?",operation)));
    }
    public Object receiver(String id,Object body){
        context.require(SessionDomain.MANAGER);var f=new Fields(body).only("requestId","expectedVersion","receiverId","reason");String store=id(raw(id),"store_id"),receiver=f.id("receiverId",false),reason=f.text("reason",1,500,true);
        return mutations.run("submerchant.receiver:"+id,body,store,()->{var row=raw(id);version(row,f.version());if(receiver!=null)db.required("SELECT id FROM li_p0_receiver_relation WHERE id=? AND config_id=? AND mode=?",receiver,row.get("config_id"),row.get("mode"));db.update("UPDATE li_p0_submerchant_relation SET receiver_id=?,version=version+1,updated_at=? WHERE id=?",receiver,clock.instant(),id);mutations.audit("submerchant.receiver",store,id,f.requestId(),f.version(),f.version()+1,reason);mutations.invalidated(store,id,"CHANNEL_RELATION",f.version()+1);return view(raw(id));});
    }
    /** Claim once. Crashes are recovered by querying the original business number, never repeating submit blindly. */
    public void dispatch(String operation){
        var claimed=tx.execute(status->{
            db.required("SELECT id FROM li_p0_consistency_guard WHERE id=1 FOR SHARE");var op=db.required("SELECT * FROM li_p0_channel_operation WHERE id=?",operation);db.one("SELECT id FROM li_admin_user WHERE id=? FOR UPDATE",op.get("actor_id"));String store=id(raw(id(op,"object_id")),"store_id");db.required("SELECT id FROM li_store WHERE id=? FOR UPDATE",store);
            if(!"QUEUED".equals(id(op,"status")))return null;
            if(!permitted(id(op,"actor_id"))){db.update("UPDATE li_p0_channel_operation SET status='FAILED',failure_code='AUTHORIZATION_REVOKED',completed_at=? WHERE id=?",clock.instant(),operation);return null;}
            var relation=raw(id(op,"object_id"));var config=configs.raw(id(relation,"config_id"));if(!current(op,relation,config)){db.update("UPDATE li_p0_channel_operation SET status='STALE',failure_code='CONTEXT_CHANGED',completed_at=? WHERE id=?",clock.instant(),operation);return null;}
            db.cas(db.update("UPDATE li_p0_channel_operation SET status='DISPATCHING',attempt=attempt+1 WHERE id=? AND status='QUEUED'",operation));return op;
        });
        if(claimed==null)return;
        var relation=raw(id(claimed,"object_id"));var config=configs.raw(id(relation,"config_id"));var app=application(relation);JsonObject response=null;Map<String,Object> capabilities=null;String responseRef=null;ChannelFailure failure=null;
        try{
            var port=configs.port(config);
            if("SUBMIT".equals(id(claimed,"action"))){var material=vault.get(id(app,"encrypted_payload_ref"));var payload=materials.mapToChannel(material,id(relation,"store_id"),file->media(config,id(relation,"store_id"),file,port));response=port.submitApplication(id(app,"out_request_no"),payload);}
            else response=app.get("applyment_id")==null?port.queryByOutRequestNo(id(app,"out_request_no")):port.queryByApplymentId(id(app,"applyment_id"));
            checkResponse(app,response,claimed);responseRef=vault.put(response);if("QUERY".equals(id(claimed,"action")))capabilities=port.verifyRelation(response,id(config,"payment_appid"));
        }catch(ChannelFailure safe){failure=safe;}catch(Stage1Exception safe){failure=new ChannelFailure("CHANNEL_MATERIAL_UNAVAILABLE",false,false);}catch(RuntimeException unavailable){failure=new ChannelFailure("CHANNEL_TRANSPORT_UNKNOWN",true,true);}
        finish(claimed,response,capabilities,responseRef,failure);
    }
    private void checkResponse(Map<String,Object> app,JsonObject response,Map<String,Object> operation){
        var f=new Fields(response);String applyment=f.text("applyment_id",1,64,true),out=f.text("out_request_no",1,124,false);if(!applyment.matches("[0-9]{1,64}")||app.get("applyment_id")!=null&&!applyment.equals(id(app,"applyment_id"))||out!=null&&!out.equals(id(app,"out_request_no")))throw new ChannelFailure("CHANNEL_RESPONSE_IDENTITY_MISMATCH",true,false);
        if("QUERY".equals(id(operation,"action"))&&!f.has("applyment_state"))throw new ChannelFailure("CHANNEL_RESPONSE_INVALID",true,false);
    }
    private String media(Map<String,Object> config,String store,String file,OnboardingChannelPort port){
        var download=files.channelMaterial(file,store);String hash=digest(download.bytes());var old=db.one("SELECT media_id FROM li_p0_channel_media WHERE config_id=? AND config_version=? AND mode=? AND file_id=? AND sha256=?",config.get("id"),config.get("config_version"),config.get("mode"),file,hash);if(old!=null)return id(old,"media_id");
        String extension=switch(download.mime()){case "image/png"->"png";case "image/jpeg"->"jpg";case "application/pdf"->"pdf";default->throw new ChannelFailure("CHANNEL_MEDIA_INVALID",false,false);};String media=port.uploadMaterial("s1_"+file+"."+extension,download.mime(),download.bytes());
        db.update("INSERT INTO li_p0_channel_media(config_id,config_version,mode,file_id,sha256,media_id,created_at) VALUES(?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE media_id=media_id",config.get("id"),config.get("config_version"),config.get("mode"),file,hash,media,clock.instant());return id(db.required("SELECT media_id FROM li_p0_channel_media WHERE config_id=? AND config_version=? AND mode=? AND file_id=? AND sha256=?",config.get("id"),config.get("config_version"),config.get("mode"),file,hash),"media_id");
    }
    private void finish(Map<String,Object> op,JsonObject response,Map<String,Object> capability,String ref,ChannelFailure failure){
        tx.executeWithoutResult(status->{
            db.required("SELECT id FROM li_p0_consistency_guard WHERE id=1 FOR SHARE");db.one("SELECT id FROM li_admin_user WHERE id=? FOR UPDATE",op.get("actor_id"));var before=raw(id(op,"object_id"));String store=id(before,"store_id"),id=id(before,"id");db.required("SELECT id FROM li_store WHERE id=? FOR UPDATE",store);var relation=raw(id);var config=configs.raw(id(relation,"config_id"));var record=db.required("SELECT * FROM li_p0_channel_operation WHERE id=? FOR UPDATE",op.get("id"));if(!"DISPATCHING".equals(id(record,"status")))return;
            if(!current(op,relation,config)||!permitted(id(op,"actor_id"))){db.update("UPDATE li_p0_channel_operation SET status='STALE',response_ref=?,failure_code=?,completed_at=? WHERE id=?",ref,failure==null?"CONTEXT_CHANGED":failure.code(),clock.instant(),op.get("id"));return;}
            var app=application(relation);String operationState="SUCCEEDED",failureCode=null;Map<String,Object> safe;
            if(failure!=null){operationState=failure.uncertain()?"UNKNOWN":"FAILED";failureCode=failure.code();safe=map("code",failureCode,"uncertain",failure.uncertain(),"recoverBy","QUERY_ORIGINAL_APPLICATION");db.update("UPDATE li_p0_submerchant_relation SET capability_state='UNVERIFIED',last_sync_state=?,version=version+1,updated_at=? WHERE id=?",failure.uncertain()?"UNKNOWN":"FAILED",clock.instant(),id);if("SUBMIT".equals(id(op,"action")))db.update("UPDATE li_p0_channel_application SET state=? WHERE id=?",failure.uncertain()?"UNKNOWN":"DRAFT",app.get("id"));}
            else{
                var f=new Fields(response);String raw=f.text("applyment_state",1,80,false),state=raw==null?"PROCESSING":ChannelStatus.normalize(raw),sub=f.text("sub_mchid",1,64,false);boolean verified=capability!=null&&"VERIFIED".equals(capability.get("verificationState"))&&"COMPLETED".equals(state)&&sub!=null;
                if(sub!=null)Stage1Exception.require("MOCK".equals(id(config,"mode"))?sub.matches("MOCK_SUB_[0-9]{1,30}"):sub.matches("[0-9]{8,32}"),409,"CHANNEL_ACCOUNT_FORMAT_INVALID");
                String accountKey=sub==null?null:id(config,"mode")+":"+id(config,"provider_mchid")+":"+sub;
                if(accountKey!=null&&db.count("SELECT COUNT(*) FROM li_p0_submerchant_relation WHERE active_account_key=? AND id<>?",accountKey,id)>0){operationState="FAILED";failureCode="CHANNEL_ACCOUNT_CONFLICT";verified=false;accountKey=null;}
                safe=map("applymentId",f.text("applyment_id",1,64,true),"state",state,"rawState",raw,"subMchidMasked",ChannelConfigService.mask(sub),"requiredActions",requiredActions(response),"sourceMode",id(config,"mode"));
                db.update("UPDATE li_p0_channel_application SET applyment_id=?,state=?,raw_state=?,required_actions=? WHERE id=?",f.text("applyment_id",1,64,true),state,raw,write(requiredActions(response)),app.get("id"));
                db.update("UPDATE li_p0_submerchant_relation SET sub_mchid=COALESCE(?,sub_mchid),capability_state=?,capability_evidence=?,last_sync_state=?,verified_at=?,evidence_version=evidence_version+1,accepted_seq=?,active_account_key=COALESCE(?,active_account_key),material_state='SUBMITTED',version=version+1,updated_at=? WHERE id=?",sub,verified?"VERIFIED":"UNVERIFIED",capability==null?null:write(capability),failureCode==null&&raw!=null&&!state.equals("UNKNOWN")?"SUCCESS":"UNKNOWN",raw==null?null:clock.instant(),number(op,"request_seq"),accountKey,clock.instant(),id);
            }
            db.update("UPDATE li_p0_channel_operation SET status=?,safe_response=?,response_ref=?,failure_code=?,completed_at=? WHERE id=?",operationState,write(safe),ref,failureCode,clock.instant(),op.get("id"));mutations.invalidated(store,id,"CHANNEL_EVIDENCE",number(relation,"version")+1);db.update("INSERT INTO li_p0_audit(id,actor_id,identity_domain,action,store_id,object_id,request_id,before_version,after_version,reason,created_at) VALUES(?,?,'MANAGER','channel.result',?,?,?,?,?,?,?)",db.id(),op.get("actor_id"),store,id,op.get("request_id"),number(relation,"version"),number(relation,"version")+1,operationState,clock.instant());
        });
    }
    private boolean permitted(String actor){try{return access.managerPermissions(actor).contains("channel.onboard");}catch(RuntimeException denied){return false;}}
    private boolean current(Map<String,Object> op,Map<String,Object> relation,Map<String,Object> config){return number(op,"request_seq")==number(relation,"request_seq")&&number(op,"relation_version")==number(relation,"version")&&number(op,"config_version")==number(config,"config_version")&&flag(config,"enabled")&&relation.get("active_store_key")!=null;}
    private Object requiredActions(JsonObject response){return ChannelUserActions.summary(response);}
    public Object requiredAction(String id,long expectedVersion){
        var actor=context.require(SessionDomain.MANAGER);
        return tx.execute(status->{
            db.required("SELECT id FROM li_p0_consistency_guard WHERE id=1 FOR SHARE");db.required("SELECT id FROM li_admin_user WHERE id=? FOR UPDATE",actor.id());String store=id(raw(id),"store_id");db.required("SELECT id FROM li_store WHERE id=? FOR UPDATE",store);context.recheck(actor);
            var relation=raw(id);version(relation,expectedVersion);var config=configs.raw(id(relation,"config_id"));Stage1Exception.require(flag(config,"enabled")&&relation.get("active_store_key")!=null&&number(relation,"config_version")==number(config,"config_version")&&"SUCCESS".equals(id(relation,"last_sync_state")),409,"CHANNEL_ACTION_REFRESH_REQUIRED");
            var op=db.required("SELECT * FROM li_p0_channel_operation WHERE object_type='APPLICATION' AND object_id=? AND request_seq=? AND status='SUCCEEDED' AND action='QUERY'",id,relation.get("accepted_seq"));
            Instant source=instant(op.get("completed_at"));Stage1Exception.require(number(op,"relation_version")+1==number(relation,"version")&&source!=null&&!source.isAfter(clock.instant())&&source.isAfter(clock.instant().minusSeconds(900)),409,"CHANNEL_ACTION_REFRESH_REQUIRED");
            var result=new LinkedHashMap<>(ChannelUserActions.disclose(vault.get(id(op,"response_ref")),id(config,"mode")));Instant until=source.plusSeconds(900).isBefore(clock.instant().plusSeconds(300))?source.plusSeconds(900):clock.instant().plusSeconds(300);result.putAll(map("relationId",id,"version",expectedVersion,"sourceOperationId",id(op,"id"),"sourceTime",source,"displayUntil",until,"channelExpiry","NOT_SUPPLIED"));
            mutations.audit("submerchant.action.view",store,id,db.id(),expectedVersion,expectedVersion,"Authorized current channel action disclosure");context.recheck(actor);return result;
        });
    }
    public void recoverInterrupted(){
        // No transaction can know whether an interrupted submit reached the channel. Preserve original identifiers.
        for(var op:db.rows("SELECT * FROM li_p0_channel_operation WHERE object_type='APPLICATION' AND status='DISPATCHING' AND created_at<?",clock.instant().minusSeconds(120)))finish(op,null,null,null,new ChannelFailure("CHANNEL_INTERRUPTED_UNKNOWN",true,true));
        for(var op:db.rows("SELECT id FROM li_p0_channel_operation WHERE object_type='APPLICATION' AND status='QUEUED' ORDER BY created_at LIMIT 20"))dispatch(id(op,"id"));
    }
    private String storeKey(String store,String mode){return settings.getEnvironment()+":"+mode+":"+store;}
    private boolean fresh(Object value){return value!=null&&instant(value).isAfter(clock.instant().minusSeconds(86400))&&!instant(value).isAfter(clock.instant());}
    private void version(Map<String,Object> row,long version){Stage1Exception.require(number(row,"version")==version,409,"VERSION_CONFLICT");}
    private Map<String,Object> raw(String id){return db.required("SELECT r.* FROM li_p0_submerchant_relation r JOIN li_p0_channel_config c ON c.id=r.config_id WHERE r.id=? AND c.environment=?",id,settings.getEnvironment());}
    private Map<String,Object> application(Map<String,Object> relation){return db.required("SELECT * FROM li_p0_channel_application WHERE id=?",relation.get("application_id"));}
    public Object detail(String id){context.require(SessionDomain.MANAGER);var row=raw(id);var app=application(row);return map("relation",view(row),"materials",materials.editable(app.get("encrypted_payload_ref")==null?new JsonObject():vault.get(id(app,"encrypted_payload_ref"))),"materialRevisions",db.rows("SELECT revision,safe_summary,created_at FROM li_p0_channel_material_revision WHERE application_id=? ORDER BY revision DESC",row.get("application_id")).stream().map(r->map("revision",r.get("revision"),"summary",object(r.get("safe_summary")),"at",instant(r.get("created_at")))).toList(),"operations",db.rows("SELECT * FROM li_p0_channel_operation WHERE application_id=? ORDER BY created_at DESC LIMIT 100",row.get("application_id")).stream().map(this::operationView).toList());}
    public Object list(String store,int page,int size){context.require(SessionDomain.MANAGER);AgreementService.page(page,size);String where=" FROM li_p0_submerchant_relation r JOIN li_p0_channel_config c ON c.id=r.config_id WHERE c.environment=? AND (? IS NULL OR r.store_id=?)";return map("total",db.count("SELECT COUNT(*)"+where,settings.getEnvironment(),store,store),"records",db.rows("SELECT r.*"+where+" ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",settings.getEnvironment(),store,store,size,(page-1)*size).stream().map(this::view).toList());}
    public Object view(Map<String,Object> row){var app=application(row);return map("id",id(row,"id"),"storeId",id(row,"store_id"),"configId",id(row,"config_id"),"configVersion",row.get("config_version"),"mode",row.get("mode"),"version",row.get("version"),"enabled",flag(row,"enabled"),"replaced",row.get("active_store_key")==null,"subMchidMasked",ChannelConfigService.mask(id(row,"sub_mchid")),"applicationId",id(app,"id"),"outRequestNo",app.get("out_request_no"),"applymentId",id(app,"applyment_id"),"applicationState",app.get("state"),"rawState",app.get("raw_state"),"materialVersion",row.get("material_version"),"materialSummary",object(app.get("safe_material_summary")),"requiredActions",app.get("required_actions")==null?null:object(app.get("required_actions")),"capabilityState",row.get("capability_state"),"capabilityEvidence",row.get("capability_evidence")==null?null:object(row.get("capability_evidence")),"lastSyncState",row.get("last_sync_state"),"verifiedAt",instant(row.get("verified_at")),"receiverId",id(row,"receiver_id"),"realQualification","CHANNEL_PENDING");}
    private Object operationView(Map<String,Object> row){return map("id",id(row,"id"),"action",row.get("action"),"sequence",row.get("request_seq"),"configVersion",row.get("config_version"),"status",row.get("status"),"attempt",row.get("attempt"),"failureCode",row.get("failure_code"),"result",row.get("safe_response")==null?null:object(row.get("safe_response")),"createdAt",instant(row.get("created_at")),"completedAt",instant(row.get("completed_at")));}
}
