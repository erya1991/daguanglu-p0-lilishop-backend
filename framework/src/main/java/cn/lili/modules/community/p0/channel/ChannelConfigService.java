package cn.lili.modules.community.p0.channel;
import cn.lili.common.security.session.SessionDomain;
import cn.lili.common.security.session.http.SessionApplications;
import cn.lili.modules.community.p0.agreement.AgreementService;
import cn.lili.modules.community.p0.support.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
public class ChannelConfigService {
    private final Stage1Db db;private final Stage1Context context;private final Stage1Mutations mutations;private final Stage1Settings settings;private final ChannelVault vault;private final PlatformTransactionManager manager;private final Clock clock;private final SessionApplications applications;
    public ChannelConfigService(Stage1Db db,Stage1Context context,Stage1Mutations mutations,Stage1Settings settings,ChannelVault vault,PlatformTransactionManager manager,@Qualifier("stage1Clock") Clock clock,SessionApplications applications){this.db=db;this.context=context;this.mutations=mutations;this.settings=settings;this.vault=vault;this.manager=manager;this.clock=clock;this.applications=applications;}
    public Object save(String id,Object body){
        context.require(SessionDomain.MANAGER);var f=new Fields(body).only(id==null?new String[]{"requestId","name","mode","providerMchid","paymentAppId","credentialRef","mockMaxRateBps","reason"}:new String[]{"requestId","expectedVersion","name","providerMchid","paymentAppId","credentialRef","mockMaxRateBps","reason"});
        return mutations.run("channel-config.save"+(id==null?"":":"+id),body,null,()->{
            var old=id==null?null:raw(id);String mode=id==null?f.choice("mode",Set.of("MOCK","REAL"),true):id(old,"mode"),name=f.text("name",1,60,true),mchid=f.text("providerMchid",1,64,true),app=f.text("paymentAppId",1,128,true),reference=f.has("credentialRef")?f.text("credentialRef",1,64,false):old==null?null:id(old,"secret_ref"),reason=f.text("reason",1,500,true);Long cap=f.integer("mockMaxRateBps",0,10000,false);
            Stage1Exception.require(settings.permitsMode(mode),422,"CHANNEL_ENVIRONMENT_INVALID");
            if(mode.equals("REAL")){Stage1Exception.require(mchid.matches("[0-9]{8,32}")&&app.matches("wx[a-fA-F0-9]{16}")&&cap==null,422,"CHANNEL_CONFIG_INVALID");if(reference!=null)Stage1Exception.require(reference.matches("[A-Za-z0-9_-]{1,64}"),422,"CREDENTIAL_REFERENCE_INVALID");String resident=applications.getResidentApplication();Stage1Exception.require(!app.equals(applications.getMerchantApplication())&&(resident==null||!resident.matches("wx[a-fA-F0-9]{16}")||resident.equals(app)),422,"PAYMENT_APPLICATION_MISMATCH");}
            else Stage1Exception.require(mchid.startsWith("MOCK_")&&app.startsWith("MOCK_")&&reference==null,422,"MOCK_CONFIG_INVALID");
            String target=id==null?db.id():id;
            if(id==null)db.update("INSERT INTO li_p0_channel_config(id,name,mode,environment,provider,provider_mchid,payment_appid,secret_ref,mock_max_rate_bps,change_reason,created_at) VALUES(?,?,?,?, 'WECHAT_ECOMMERCE',?,?,?,?,?,?)",target,name,mode,settings.getEnvironment(),mchid,app,reference,cap,reason,clock.instant());
            else{db.cas(db.update("UPDATE li_p0_channel_config SET name=?,provider_mchid=?,payment_appid=?,secret_ref=?,mock_max_rate_bps=?,change_reason=?,config_version=config_version+1,enabled=0,active_route=NULL,check_state='NOT_CHECKED',checked_at=NULL WHERE id=? AND config_version=?",name,mchid,app,reference,cap,reason,id,f.version()));invalidate(id);}
            history(target,reason);mutations.audit("channel-config.save",null,target,f.requestId(),old==null?null:f.version(),old==null?0L:f.version()+1,reason);return view(raw(target));
        });
    }
    public Object command(String id,Object body,String action){
        context.require(SessionDomain.MANAGER);var f=new Fields(body).only("requestId","expectedVersion","reason");String reason=f.text("reason",1,500,true);
        return mutations.run("channel-config."+action+":"+id,body,null,()->{
            var row=db.required("SELECT * FROM li_p0_channel_config WHERE id=? FOR UPDATE",id);Stage1Exception.require(number(row,"config_version")==f.version(),409,"VERSION_CONFLICT");
            if(action.equals("check")){
                String state="VALID";
                if("REAL".equals(id(row,"mode")))try{var secret=vault.credential(id(row,"secret_ref"));Stage1Exception.require(id(row,"provider_mchid").equals(new Fields(secret).text("merchantId",8,32,true)),422,"CREDENTIAL_ACCOUNT_MISMATCH");WechatEcommerceChannel.real(settings,secret);if(!paymentApplicationMatches(row))state="APPLICATION_PENDING";}catch(ChannelFailure pending){state="CREDENTIAL_PENDING";}
                db.update("UPDATE li_p0_channel_config SET check_state=?,checked_at=?,config_version=config_version+1,enabled=0,active_route=NULL,change_reason=? WHERE id=?",state,clock.instant(),reason,id);invalidate(id);
            }else if(action.equals("enable")){
                Stage1Exception.require("VALID".equals(id(row,"check_state")),409,"CONFIG_NOT_CHECKED");Stage1Exception.require(settings.getMode().equals(id(row,"mode")),409,"RUNTIME_MODE_MISMATCH");
                Stage1Exception.require(settings.permitsMode(id(row,"mode")),409,"CHANNEL_ENVIRONMENT_INVALID");Stage1Exception.require(!"REAL".equals(id(row,"mode"))||paymentApplicationMatches(row),409,"PAYMENT_APPLICATION_MISMATCH");
                db.update("UPDATE li_p0_channel_config SET enabled=1,active_route=?,config_version=config_version+1,change_reason=? WHERE id=?",settings.getEnvironment(),reason,id);invalidate(id);
            }else if(action.equals("disable")){db.update("UPDATE li_p0_channel_config SET enabled=0,active_route=NULL,config_version=config_version+1,change_reason=? WHERE id=?",reason,id);invalidate(id);}
            else throw new Stage1Exception(404,"RESOURCE_NOT_FOUND");
            history(id,reason);mutations.audit("channel-config."+action,null,id,f.requestId(),f.version(),f.version()+1,reason);return view(raw(id));
        });
    }
    private void invalidate(String id){
        db.update("UPDATE li_p0_submerchant_relation SET capability_state='UNVERIFIED',last_sync_state='NEVER',version=version+1,updated_at=? WHERE config_id=?",clock.instant(),id);
        db.update("UPDATE li_p0_receiver_relation SET state='UNVERIFIED',trust_source='UNVERIFIED',version=version+1,reason='CHANNEL_CONFIG_CHANGED' WHERE config_id=?",id);
        for(var row:db.rows("SELECT id,store_id,version FROM li_p0_submerchant_relation WHERE config_id=?",id))mutations.invalidated(id(row,"store_id"),id(row,"id"),"CHANNEL_EVIDENCE",number(row,"version"));
    }
    private void history(String id,String reason){var row=raw(id);db.update("INSERT INTO li_p0_channel_config_history(config_id,version,safe_snapshot,actor_id,reason,created_at) VALUES(?,?,?,?,?,?)",id,number(row,"config_version"),write(view(row)),context.current().id(),reason,clock.instant());}
    public Map<String,Object> raw(String id){return db.required("SELECT * FROM li_p0_channel_config WHERE id=? AND environment=?",id,settings.getEnvironment());}
    public OnboardingChannelPort port(Map<String,Object> config){
        if("MOCK".equals(id(config,"mode"))){Stage1Exception.require("MOCK".equals(settings.getMode())&&settings.permitsMode("MOCK"),409,"MOCK_MODE_UNAVAILABLE");return new PersistentMockChannel(db,id(config,"id"),config.get("mock_max_rate_bps")==null?null:number(config,"mock_max_rate_bps"),manager,clock);}
        if(!settings.isRealOutboundEnabled())throw new ChannelFailure("CHANNEL_OUTBOUND_NOT_AUTHORIZED",false,false);
        if(!settings.permitsMode("REAL")||!paymentApplicationMatches(config))throw new ChannelFailure("PAYMENT_APPLICATION_MISMATCH",false,false);
        return WechatEcommerceChannel.real(settings,vault.credential(id(config,"secret_ref")));
    }
    private boolean paymentApplicationMatches(Map<String,Object> config){String current=applications.getResidentApplication();return current!=null&&current.matches("wx[a-fA-F0-9]{16}")&&current.equals(id(config,"payment_appid"))&&!current.equals(applications.getMerchantApplication());}
    public Object detail(String id){context.require(SessionDomain.MANAGER);var result=object(view(raw(id)));result.add("history",new com.google.gson.JsonParser().parse(write(db.rows("SELECT version,safe_snapshot,reason,created_at FROM li_p0_channel_config_history WHERE config_id=? ORDER BY version DESC",id).stream().map(r->map("version",r.get("version"),"snapshot",object(r.get("safe_snapshot")),"reason",r.get("reason"),"at",instant(r.get("created_at")))).toList())));return result;}
    public Object list(int page,int size){context.require(SessionDomain.MANAGER);AgreementService.page(page,size);return map("total",db.count("SELECT COUNT(*) FROM li_p0_channel_config WHERE environment=?",settings.getEnvironment()),"records",db.rows("SELECT * FROM li_p0_channel_config WHERE environment=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",settings.getEnvironment(),size,(page-1)*size).stream().map(this::view).toList(),"environment",settings.getEnvironment(),"authorizedMode",settings.getMode(),"realOutboundAuthorized",settings.isRealOutboundEnabled(),"ordinaryWechat",ordinaryWechat());}
    /** Native WECHAT_PAYMENT remains its own setting. Never expose its payload or credentials. */
    private Object ordinaryWechat(){
        var rows=db.rows("SELECT setting_value,update_time FROM li_setting WHERE id='WECHAT_PAYMENT' AND COALESCE(delete_flag,0)=0");
        var result=map("type","ORDINARY_WECHAT","source","li_setting.WECHAT_PAYMENT","state","NOT_CONFIGURED","p0Fallback",false,"externalVerified",false);
        if(rows.isEmpty())return result;
        try{var nativeConfig=object(rows.get(0).get("setting_value"));result.put("state","CONFIGURED");result.put("updatedAt",instant(rows.get(0).get("update_time")));
            for(String key:List.of("mchId","mpAppId","jsapiAppId","nativeAppId","h5AppId","appAppId")){var value=nativeConfig.get(key);result.put(key+"Masked",value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()?mask(value.getAsString()):null);}
            for(String key:List.of("apiclientKey","apiKey3","publicKey","serialNumber")){var value=nativeConfig.get(key);result.put(key+"Configured",value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()&&!value.getAsString().isBlank());}
        }catch(RuntimeException malformed){result=map("type","ORDINARY_WECHAT","source","li_setting.WECHAT_PAYMENT","state","UNREADABLE","p0Fallback",false,"externalVerified",false);}
        return result;
    }
    public Object view(Map<String,Object> row){return map("id",id(row,"id"),"name",row.get("name"),"version",row.get("config_version"),"mode",row.get("mode"),"environment",row.get("environment"),"provider","WECHAT_ECOMMERCE","providerMchidMasked",mask(id(row,"provider_mchid")),"paymentAppIdMasked",mask(id(row,"payment_appid")),"credentialConfigured",row.get("secret_ref")!=null,"mockMaxRateBps",row.get("mock_max_rate_bps"),"enabled",flag(row,"enabled"),"checkState",row.get("check_state"),"checkedAt",instant(row.get("checked_at")),"channelQualification","NOT_VERIFIED","reason",row.get("change_reason"));}
    public static String mask(String value){return value==null?null:value.length()<5?"****":value.substring(0,2)+"****"+value.substring(value.length()-3);}
}
