package cn.lili.modules.community.p0.channel;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.JsonObject;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Clock;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Durable simulation inside an explicitly selected local Mock configuration. Never supplies REAL evidence. */
public final class PersistentMockChannel implements OnboardingChannelPort {
    private final Stage1Db db;private final String config;private final Long cap;private final Clock clock;private final TransactionTemplate tx;
    public PersistentMockChannel(Stage1Db db,String config,Long explicitCap,PlatformTransactionManager manager,Clock clock){this.db=db;this.config=config;this.cap=explicitCap;this.clock=clock;tx=new TransactionTemplate(manager);}
    @Override public void validateMaterials(JsonObject materials){for(String key:List.of("organization_type","business_license_info","account_info","contact_info","sales_scene_info"))if(!materials.has(key))throw new ChannelFailure("CHANNEL_MATERIAL_REQUIRED",false,false);}
    @Override public String uploadMaterial(String name,String mime,byte[] bytes){if(bytes.length==0)throw new ChannelFailure("CHANNEL_MEDIA_INVALID",false,false);return "MOCK_"+config+"_"+digest(bytes);}
    @Override public JsonObject submitApplication(String out,JsonObject payload){
        validateMaterials(payload);var account=payload.getAsJsonObject("account_info");String bank=new Fields(account).text("account_bank",1,128,true);if(!Set.of("1001","1002","1099").contains(bank))throw new ChannelFailure("CHANNEL_BANK_NOT_SUPPORTED",false,false);ChannelBankRules.apply(account,object(map("account_bank_code",bank,"account_bank",bank,"need_bank_branch",!bank.equals("1001"))));var result=tx.execute(status->{
            db.required("SELECT id FROM li_p0_channel_config WHERE id=? AND mode='MOCK' FOR UPDATE",config);
            var old=db.one("SELECT * FROM li_p0_channel_mock WHERE config_id=? AND out_request_no=? FOR UPDATE",config,out);String hash=hash(payload);
            if(old!=null){
                if(!hash.equals(id(old,"request_hash"))){if(!"REJECTED".equals(id(old,"raw_state")))throw new ChannelFailure("CHANNEL_REQUEST_CONFLICT",false,false);db.update("UPDATE li_p0_channel_mock SET request_hash=?,raw_state='CHECKING',query_count=0,revision=revision+1,updated_at=? WHERE config_id=? AND out_request_no=?",hash,clock.instant(),config,out);}
            }else db.update("INSERT INTO li_p0_channel_mock(config_id,out_request_no,request_hash,applyment_id,sub_mchid,raw_state,created_at,updated_at) VALUES(?,?,?,?,?,'CHECKING',?,?)",config,out,hash,db.id(),"MOCK_SUB_"+db.id(),clock.instant(),clock.instant());
            return db.required("SELECT * FROM li_p0_channel_mock WHERE config_id=? AND out_request_no=?",config,out);
        });
        if("DROP_SUBMIT_RESPONSE".equals(id(result,"scenario")))throw new ChannelFailure("CHANNEL_TRANSPORT_UNKNOWN",true,true);
        return object(map("applyment_id",id(result,"applyment_id"),"out_request_no",out));
    }
    @Override public JsonObject queryByOutRequestNo(String out){return query("out_request_no",out);}
    @Override public JsonObject queryByApplymentId(String id){return query("applyment_id",id);}
    private JsonObject query(String field,String value){
        return tx.execute(status->{
            var row=db.one("SELECT * FROM li_p0_channel_mock WHERE config_id=? AND "+field+"=? FOR UPDATE",config,value);if(row==null)throw new ChannelFailure("CHANNEL_APPLICATION_NOT_FOUND",false,false);
            String scenario=id(row,"scenario"),raw=id(row,"raw_state");int count=(int)number(row,"query_count")+1;
            switch(scenario){
                case "TIMEOUT"->throw new ChannelFailure("CHANNEL_TRANSPORT_UNKNOWN",true,true);
                case "BAD_SIGNATURE"->throw new ChannelFailure("CHANNEL_SIGNATURE_INVALID",true,false);
                case "UNAUTHORIZED"->throw new ChannelFailure("CHANNEL_AUTH_REJECTED",false,false);
                case "RATE_LIMITED","SERVER_ERROR"->throw new ChannelFailure("CHANNEL_TEMPORARY_UNAVAILABLE",true,true);
                case "NEED_CORRECTION"->raw="REJECTED";
                case "VERIFY_ACCOUNT"->raw="ACCOUNT_NEED_VERIFY";
                case "FROZEN"->raw="FROZEN";
                case "CANCELED"->raw="CANCELED";
                case "UNKNOWN_STATE"->raw="NEW_UNRECOGNIZED_STATE";
                case "PROCESSING"->raw="AUDITING";
                default->raw=count==1?"AUDITING":count==2?"NEED_SIGN":"FINISH";
            }
            db.update("UPDATE li_p0_channel_mock SET query_count=?,raw_state=?,updated_at=? WHERE config_id=? AND out_request_no=?",count,raw,clock.instant(),config,row.get("out_request_no"));
            return object(map("applyment_id",id(row,"applyment_id"),"out_request_no",row.get("out_request_no"),"applyment_state",raw,"applyment_state_desc","S1_TEST持久Mock渠道模拟","sub_mchid",raw.equals("FINISH")?row.get("sub_mchid"):null,"audit_detail",raw.equals("REJECTED")?List.of(map("param_name","business_license_info","reject_reason","S1_TEST合成补正要求")):List.of()));
        });
    }
    @Override public Map<String,Object> verifyRelation(JsonObject application,String app){
        boolean complete=application.has("applyment_state")&&application.get("applyment_state").getAsString().equals("FINISH");
        return map("sourceMode","MOCK","paymentAppId",app,"paymentEnabled",complete,"appIdLinked",complete,"profitsharingEnabled",complete,"maxRateBps",cap,"verificationState",complete?"VERIFIED":"UNVERIFIED","source","S1_TEST_PERSISTENT_SIMULATION");
    }
    @Override public JsonObject ensureReceiverRelation(JsonObject receiver){var f=new Fields(receiver);f.choice("type",Set.of("MERCHANT_ID"),true);return object(map("type","MERCHANT_ID","account",f.text("account",1,64,true),"sourceMode","MOCK"));}
}
