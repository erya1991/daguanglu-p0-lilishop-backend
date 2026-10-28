package cn.lili.common.security.session;
import cn.lili.modules.community.p0.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.*;
import java.util.concurrent.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfSystemProperty(named="s1.integration",matches="owned-run")
@Timeout(value=8,unit=TimeUnit.MINUTES)
class Stage1EligibilityMatrixTest {
    @BeforeAll static void start()throws Exception{initialize();}
    @AfterAll static void end()throws Exception{finish();}
    @BeforeEach void refresh(){new Stage1PersistenceTest().freshManagerSessions();}
    @AfterEach void clear(){sqlObserver.remove();}
    static Stage1ConcurrencyTest.Command invalidate(OwnerFixture owner,String kind){
        if(kind.equals("store"))return new Stage1ConcurrencyTest.Command("POST",Stage1Routes.MANAGER+"/stores/"+owner.store()+"/close",operator,map("requestId",req(),"expectedVersion",db.count("SELECT version FROM li_p0_store_control WHERE store_id=?",owner.store()),"reason","S1_TEST关闭强校验"));
        String table=kind.equals("agreement")?"li_p0_agreement":kind.equals("rule")?"li_p0_commission_rule":"li_p0_submerchant_relation";var row=db.required("SELECT * FROM "+table+" WHERE store_id=?",owner.store());String id=id(row,"id");
        if(kind.equals("config")){id=id(row,"config_id");return new Stage1ConcurrencyTest.Command("POST",Stage1Routes.MANAGER+"/channel-configs/"+id+"/disable",channel,map("requestId",req(),"expectedVersion",db.count("SELECT config_version FROM li_p0_channel_config WHERE id=?",id),"reason","S1_TEST禁用配置"));}
        return new Stage1ConcurrencyTest.Command("POST",Stage1Routes.MANAGER+(kind.equals("agreement")?"/agreements/":"/commission-rules/")+id+(kind.equals("agreement")?"/terminate":"/disable"),kind.equals("agreement")?operator:channel,map("requestId",req(),"expectedVersion",row.get("version"),"reason","S1_TEST停止当前依据"));
    }
    static int execute(Stage1ConcurrencyTest.Command c)throws Exception{return mvc.perform(post(c.route()).header("accessToken",c.token().accessToken()).contentType("application/json").content(write(c.body()))).andReturn().getResponse().getStatus();}
    @Test void nonApprovedAdmissionNeverBecomesBasicEvenWithAllLaterConditions()throws Exception{
        new Stage1SupplyMatrixTest().refresh();var owner=Stage1SupplyMatrixTest.ready();try{db.update("UPDATE li_p0_store_control SET admission_state='PENDING' WHERE store_id=?",owner.store());var state=object(eligibility.evaluate(owner.store(),category,null));assertFalse(state.get("BASIC_ELIGIBLE").getAsBoolean());assertFalse(state.get("MOCK_READY").getAsBoolean());assertTrue(state.get("reasons").toString().contains("STORE_INACTIVE"));}finally{db.update("UPDATE li_p0_store_control SET admission_state='APPROVED' WHERE store_id=?",owner.store());}assertTrue((Boolean)eligibility.evaluate(owner.store(),category,null).get("BASIC_ELIGIBLE"));
    }
    @Test void currentSourcesGateEachActionWithoutTreatingMissingChannelOrRuleAsMissingBasic()throws Exception{
        for(String kind:List.of("store","agreement","certificate","category","rule","config")){
            new Stage1SupplyMatrixTest().refresh();var owner=Stage1SupplyMatrixTest.ready();var listed=Stage1SupplyMatrixTest.createGoods(owner,4,List.of("MERCHANT_DELIVERY"),true);var draft=Stage1SupplyMatrixTest.createGoods(owner,4,List.of("MERCHANT_DELIVERY"),false);String id=draft.getAsJsonObject("goods").get("id").getAsString(),sku=Stage1CrossHostTest.sku(listed);var buyer=Stage1CrossHostTest.resident();assertEquals("PASS",Stage1CrossHostTest.precheck(owner,buyer,sku,"MERCHANT_DELIVERY",null,true).get("status").getAsString());
            if(kind.equals("certificate")){var q=db.required("SELECT id,version FROM li_p0_qualification WHERE store_id=?",owner.store());call(post(Stage1Routes.MANAGER+"/qualifications/"+q.get("id")+"/revoke"),reviewer,map("requestId",req(),"expectedVersion",q.get("version"),"reason","S1_TEST撤销基础证"),200);}
            else if(kind.equals("category"))call(post(Stage1Routes.MANAGER+"/categories/"+category+"/disable"),operator,map("requestId",req(),"expectedVersion",1,"reason","S1_TEST类目关闭"),200);
            else assertEquals(200,execute(invalidate(owner,kind)));
            var state=object(eligibility.evaluate(owner.store(),category,null));boolean onlyTrade=Set.of("rule","config").contains(kind);assertEquals(onlyTrade,state.get("BASIC_ELIGIBLE").getAsBoolean(),kind);assertFalse(state.get("MOCK_READY").getAsBoolean(),kind);assertEquals("BLOCKED",Stage1CrossHostTest.precheck(owner,buyer,sku,"MERCHANT_DELIVERY",null,true).get("status").getAsString(),kind);
            var token=kind.equals("store")?login(owner.open()).session():owner.login().session();call(post(Stage1Routes.STORE+"/goods/"+id+"/submit"),token,map("requestId",req(),"expectedVersion",0),onlyTrade?200:kind.equals("store")?403:kind.equals("category")?422:409);
            if(onlyTrade){call(post(Stage1Routes.MANAGER+"/goods/"+id+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"contentVersion",0),200);call(post(Stage1Routes.STORE+"/goods/"+id+"/list"),token,map("requestId",req(),"expectedVersion",2),200);}else assertEquals("DRAFT",db.required("SELECT review_status FROM li_p0_goods_control WHERE goods_id=?",id).get("review_status"));
            call(post(Stage1Routes.STORE+"/goods/"+listed.getAsJsonObject("goods").get("id").getAsString()+"/unlist"),token,map("requestId",req(),"expectedVersion",3),200);
        }
    }
    @Test void storeAgreementAndConfigurationChangesSerializeWithListingInBothCommitOrders()throws Exception{
        for(String kind:List.of("store","agreement","config"))for(boolean restrictFirst:List.of(false,true)){
            new Stage1SupplyMatrixTest().refresh();var owner=Stage1SupplyMatrixTest.ready();var created=Stage1SupplyMatrixTest.createGoods(owner,3,List.of("MERCHANT_DELIVERY"),false);String id=created.getAsJsonObject("goods").get("id").getAsString();call(post(Stage1Routes.STORE+"/goods/"+id+"/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",0),200);call(post(Stage1Routes.MANAGER+"/goods/"+id+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"contentVersion",0),200);
            var restriction=invalidate(owner,kind);var listing=new Stage1ConcurrencyTest.Command("POST",Stage1Routes.STORE+"/goods/"+id+"/list",owner.login().session(),map("requestId",req(),"expectedVersion",2));var locked=new CountDownLatch(1);var waiting=new CountDownLatch(1);var release=new CountDownLatch(1);
            java.util.function.Function<Boolean,Integer> operation=restrict->{try{
                if(restrict==restrictFirst)sqlObserver.set(sql->{boolean hit=restrict?sql.startsWith(kind.equals("store")?"UPDATE li_store SET store_disable":kind.equals("agreement")?"UPDATE li_p0_agreement SET terminated_at":"UPDATE li_p0_channel_config SET enabled=0"):sql.strip().replaceAll("\\s+"," ").toUpperCase(Locale.ROOT).startsWith("UPDATE LI_GOODS SET ");if(hit){sqlObserver.remove();locked.countDown();try{if(!release.await(20,TimeUnit.SECONDS))throw new java.sql.SQLException("S1_TEST barrier expired");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.sql.SQLException(e);}}});
                else sqlObserver.set(sql->{if(sql.startsWith("SELECT id FROM li_p0_consistency_guard")){sqlObserver.remove();waiting.countDown();}});
                return execute(restrict?restriction:listing);
            }catch(Exception failure){throw new RuntimeException(failure);}finally{sqlObserver.remove();}};
            try(var pool=Executors.newFixedThreadPool(2)){var first=pool.submit(()->operation.apply(restrictFirst));assertTrue(locked.await(20,TimeUnit.SECONDS),kind+" first lock restriction="+restrictFirst);var second=pool.submit(()->operation.apply(!restrictFirst));assertTrue(waiting.await(20,TimeUnit.SECONDS),kind+" second lock restriction="+!restrictFirst);release.countDown();assertEquals(200,first.get(40,TimeUnit.SECONDS));assertEquals(restrictFirst&&!kind.equals("config")?(kind.equals("store")?403:409):200,second.get(40,TimeUnit.SECONDS));}finally{release.countDown();}
            var state=object(eligibility.evaluate(owner.store(),category,null));assertFalse(state.get("MOCK_READY").getAsBoolean());assertEquals(kind.equals("config"),state.get("BASIC_ELIGIBLE").getAsBoolean());assertEquals("BLOCKED",Stage1CrossHostTest.precheck(owner,Stage1CrossHostTest.resident(),Stage1CrossHostTest.sku(created),"MERCHANT_DELIVERY",null,true).get("status").getAsString());observations.add(map("case","S04-008-commit-order","source",kind,"restrictionFirst",restrictFirst,"currentMockBlocked",true,"channelDoesNotBlockBasicListing",kind.equals("config")));
        }
    }
}
