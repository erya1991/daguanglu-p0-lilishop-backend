package cn.lili.common.security.session;

import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.*;
import java.util.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfSystemProperty(named="s1.integration",matches="owned-run")
@Timeout(value=6,unit=java.util.concurrent.TimeUnit.MINUTES)
class Stage1ChannelBoundaryTest {
    @BeforeAll static void start()throws Exception{initialize();}
    @AfterAll static void end()throws Exception{finish();}
    @BeforeEach void refresh(){new Stage1PersistenceTest().freshManagerSessions();}
    static Map<String,Object> configuration(String mode,String name,String reason){return map("requestId",req(),"name",name,"mode",mode,"providerMchid",mode.equals("MOCK")?"MOCK_"+db.id():"1234567890","paymentAppId",mode.equals("MOCK")?"MOCK_APP_"+db.id():"wx1111111111111111","reason",reason);}
    @Test void bnd003CanonicalEnvironmentsPaymentApplicationAndSecretReferenceRules()throws Exception{
        String environment=settings.getEnvironment(),resident=apps.getResidentApplication(),merchant=apps.getMerchantApplication();
        try{
            for(String env:List.of("DEV","TEST","PROD","UNKNOWN"))for(String mode:List.of("MOCK","REAL")){
                settings.setEnvironment(env);boolean accepted=!env.equals("UNKNOWN")&&!(env.equals("PROD")&&mode.equals("MOCK"));var result=call(post(Stage1Routes.MANAGER+"/channel-configs"),channel,configuration(mode,"S1_TEST环境","S1_TEST受控本地持久化矩阵"),accepted?200:422);if(accepted){assertEquals(env,result.get("environment").getAsString());assertFalse(result.get("enabled").getAsBoolean());assertEquals("NOT_VERIFIED",result.get("channelQualification").getAsString());}
            }
            settings.setEnvironment(environment);
            for(int length:List.of(0,1,60,61)){var result=call(post(Stage1Routes.MANAGER+"/channel-configs"),channel,configuration("MOCK","字".repeat(length),"S1_TEST名称长度"),length==0||length==61?422:200);if(length==1||length==60)assertEquals(length,result.get("name").getAsString().length());}
            for(int length:List.of(0,500,501))call(post(Stage1Routes.MANAGER+"/channel-configs"),channel,configuration("MOCK","S1_TEST原因","字".repeat(length)),length==500?200:422);
            var body=configuration("REAL","S1_TEST不回显凭据引用","S1_TEST引用");String reference="S1_TEST_REF_"+db.id();body.put("credentialRef",reference);var created=call(post(Stage1Routes.MANAGER+"/channel-configs"),channel,body,200);String id=created.get("id").getAsString();assertFalse(created.toString().contains(reference));assertTrue(created.get("credentialConfigured").getAsBoolean());
            var update=configuration("REAL","S1_TEST省略保留引用","S1_TEST保留");update.remove("mode");update.put("expectedVersion",0);call(put(Stage1Routes.MANAGER+"/channel-configs/"+id+"/draft"),channel,update,200);assertEquals(reference,db.required("SELECT secret_ref FROM li_p0_channel_config WHERE id=?",id).get("secret_ref"));
            update.put("requestId",req());update.put("expectedVersion",1);update.put("credentialRef","********");call(put(Stage1Routes.MANAGER+"/channel-configs/"+id+"/draft"),channel,update,422);assertEquals(1,db.count("SELECT config_version FROM li_p0_channel_config WHERE id=?",id));
            apps.setResidentApplication("wx1111111111111111");apps.setMerchantApplication("wx2222222222222222");
            for(String app:List.of("wx2222222222222222","wx3333333333333333","wx1111111111111111")){var input=configuration("REAL","S1_TEST付款应用","S1_TEST居民应用核对");input.put("paymentAppId",app);call(post(Stage1Routes.MANAGER+"/channel-configs"),channel,input,app.equals("wx1111111111111111")?200:422);}
            var injected=configuration("REAL","S1_TEST伪造凭据","S1_TEST拒绝");injected.put("apiV3Key","S1_TEST_secret_sentinel");call(post(Stage1Routes.MANAGER+"/channel-configs"),channel,injected,422);
            assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_channel_config WHERE environment='PROD' AND mode='MOCK'"));assertThrows(org.springframework.dao.DataAccessException.class,()->db.update("INSERT INTO li_p0_channel_config(id,name,mode,environment,provider,created_at) VALUES(?,'S1_TEST非法组合','MOCK','PROD','WECHAT_ECOMMERCE',UTC_TIMESTAMP(6))",db.id()));
            observations.add(map("case","BND003-environments","environmentModeCombinations",8,"productionDatabaseAccess",false,"testDatabaseOnly",true,"paymentApplicationMatchesResident",true,"maskedReferenceCannotOverwrite",true));
        }finally{settings.setEnvironment(environment);apps.setResidentApplication(resident);apps.setMerchantApplication(merchant);}
    }
    @Test void bnd003IncompleteConditionalMaterialsCannotSubmitOrLeakAndForeignFilesAreRejected()throws Exception{
        var owner=ownerFixture();String config=channelConfigFixture(1000),relation=channelRelationFixture(owner,config);var valid=object(channelMaterialsFixture(owner));long version=1;
        for(String missing:List.of("authorizationBasis","licenseFileId","legalId","contact","account","shortName","settlementId","industryName","soleBeneficialOwner")){
            var input=valid.deepCopy();input.add(missing,JsonNull.INSTANCE);var saved=call(put(Stage1Routes.MANAGER+"/submerchants/"+relation+"/materials"),channel,map("requestId",req(),"expectedVersion",version,"materials",input,"reason","S1_TEST缺少"+missing),200);version=saved.get("version").getAsLong();long before=db.count("SELECT COUNT(*) FROM li_p0_channel_operation WHERE object_id=?",relation);call(post(Stage1Routes.MANAGER+"/submerchants/"+relation+"/submit"),channel,map("requestId",req(),"expectedVersion",version,"reason","S1_TEST缺项拒绝"),422);assertEquals(before,db.count("SELECT COUNT(*) FROM li_p0_channel_operation WHERE object_id=?",relation));
        }
        var wrong=valid.deepCopy();wrong.getAsJsonObject("account").addProperty("type","PRIVATE");var saved=call(put(Stage1Routes.MANAGER+"/submerchants/"+relation+"/materials"),channel,map("requestId",req(),"expectedVersion",version,"materials",wrong,"reason","S1_TEST企业不能个人结算"),200);version=saved.get("version").getAsLong();call(post(Stage1Routes.MANAGER+"/submerchants/"+relation+"/submit"),channel,map("requestId",req(),"expectedVersion",version,"reason","S1_TEST拒绝不匹配主体"),422);
        wrong=valid.deepCopy();wrong.getAsJsonObject("account").addProperty("number","********");call(put(Stage1Routes.MANAGER+"/submerchants/"+relation+"/materials"),channel,map("requestId",req(),"expectedVersion",version,"materials",wrong,"reason","S1_TEST拒绝掩码"),422);
        var other=ownerFixture();wrong=valid.deepCopy();wrong.addProperty("licenseFileId",upload(other.login().session(),Stage1Routes.STORE));call(put(Stage1Routes.MANAGER+"/submerchants/"+relation+"/materials"),channel,map("requestId",req(),"expectedVersion",version,"materials",wrong,"reason","S1_TEST跨店附件"),404);
        String expired=upload(channel,Stage1Routes.MANAGER,"CHANNEL");db.update("UPDATE li_p0_file_control SET expires_at=? WHERE file_id=?",clock.instant().minusSeconds(1),expired);wrong=valid.deepCopy();wrong.getAsJsonObject("legalId").addProperty("frontFileId",expired);call(put(Stage1Routes.MANAGER+"/submerchants/"+relation+"/materials"),channel,map("requestId",req(),"expectedVersion",version,"materials",wrong,"reason","S1_TEST过期素材"),422);
        saved=call(put(Stage1Routes.MANAGER+"/submerchants/"+relation+"/materials"),channel,map("requestId",req(),"expectedVersion",version,"materials",valid,"reason","S1_TEST修正完整材料"),200);String secret=valid.getAsJsonObject("account").get("number").getAsString();assertFalse(saved.toString().contains(secret));call(get(Stage1Routes.MANAGER+"/submerchants/"+relation),operator,null,403);
        var submitted=relationCommand(relation,saved.get("version").getAsLong(),"submit");assertEquals("PROCESSING",submitted.get("applicationState").getAsString());assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_channel_mock WHERE config_id=?",config));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_operation WHERE response_json LIKE ?","%"+secret+"%"));
        observations.add(map("case","BND003-materials","incompleteVariants",9,"conditionalEnterpriseAccount",true,"privateFieldsNotReturned",true,"foreignAndExpiredFilesDenied",true));
    }
    @Test void bnd017EvidenceExpiresExactlyAt24HoursAndLatestFailureOrConfigChangeWins()throws Exception{
        var owner=ownerFixture();String agreement=agreementFixture(owner,0,LocalDate.now(Stage1Rules.ZONE),null);ruleFixture(owner,agreement,0);String config=channelConfigFixture(1000),relation=channelRelationFixture(owner,config);var current=relationCommand(relation,1,"submit");for(int i=0;i<3;i++)current=relationCommand(relation,current.get("version").getAsLong(),"query");current=relationCommand(relation,current.get("version").getAsLong(),"enable");
        Instant verified=instant(db.required("SELECT verified_at FROM li_p0_submerchant_relation WHERE id=?",relation).get("verified_at"));assertTrue((Boolean)eligibility.evaluate(owner.store(),category,verified.plusSeconds(86400).minusMillis(1)).get("MOCK_READY"));assertFalse((Boolean)eligibility.evaluate(owner.store(),category,verified.plusSeconds(86400)).get("MOCK_READY"));
        for(String scenario:List.of("TIMEOUT","BAD_SIGNATURE","FROZEN","UNKNOWN_STATE")){
            db.update("UPDATE li_p0_channel_mock SET scenario=? WHERE config_id=?",scenario,config);current=relationCommand(relation,current.get("version").getAsLong(),"query");assertFalse((Boolean)eligibility.evaluate(owner.store(),category,null).get("MOCK_READY"));assertFalse((Boolean)eligibility.evaluate(owner.store(),category,null).get("REAL_READY"));
            db.update("UPDATE li_p0_channel_mock SET scenario='NORMAL' WHERE config_id=?",config);current=relationCommand(relation,current.get("version").getAsLong(),"query");current=relationCommand(relation,current.get("version").getAsLong(),"enable");assertTrue((Boolean)eligibility.evaluate(owner.store(),category,null).get("MOCK_READY"));
        }
        long configVersion=db.count("SELECT config_version FROM li_p0_channel_config WHERE id=?",config);var existing=db.required("SELECT provider_mchid,payment_appid FROM li_p0_channel_config WHERE id=?",config);call(put(Stage1Routes.MANAGER+"/channel-configs/"+config+"/draft"),channel,map("requestId",req(),"expectedVersion",configVersion,"name","S1_TEST配置更新失效","providerMchid",existing.get("provider_mchid"),"paymentAppId",existing.get("payment_appid"),"mockMaxRateBps",1000,"reason","S1_TEST无需等待TTL"),200);assertFalse((Boolean)eligibility.evaluate(owner.store(),category,null).get("MOCK_READY"));assertEquals("NEVER",db.required("SELECT last_sync_state FROM li_p0_submerchant_relation WHERE id=?",relation).get("last_sync_state"));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_channel_mock WHERE config_id=?",config));
        observations.add(map("case","BND017-evidence","exact24Hours",true,"latestFailureScenarios",4,"configurationInvalidatesImmediately",true,"realChannelVerified",false));
    }
}
