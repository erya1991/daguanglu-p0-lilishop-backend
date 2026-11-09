package cn.lili.common.security.session;

import cn.lili.modules.community.p0.stage2.*;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.common.security.session.Stage2PersistenceTest.*;
import static cn.lili.common.security.session.Stage2RemainingVerificationTest.request;
import static cn.lili.common.security.session.Stage2RemainingVerificationTest.await;
import static cn.lili.common.security.session.Stage2AcceptanceVerificationTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfSystemProperty(named="s2.integration",matches="owned-run")
@Timeout(value=20,unit=TimeUnit.MINUTES)
class Stage2SourceRaceTest {
    @BeforeAll static void open()throws Exception {simulation.offset=0;Stage2RemainingVerificationTest.open();}
    @BeforeEach void before(){simulation.offset=0;mvc=transactionMvc;}
    @AfterAll static void close()throws Exception {Stage2PersistenceTest.closeS2();}
    @FunctionalInterface interface Maintenance {void apply(Purchase p)throws Exception;}
    record Source(String name,String sql,Maintenance change){}
    static List<Source> sources(){return List.of(
        new Source("unlist","update li_goods",Stage2RemainingVerificationTest::unlist),
        new Source("price","update li_goods_sku",p->{
            String goods=id(db.required("SELECT goods_id FROM li_p0_sku_control WHERE sku_id=?",p.sku()),"goods_id");var detail=request(supplyMvc,get(Stage1Routes.STORE+"/goods/"+goods),p.owner().login().session(),null,200);var payload=detail.getAsJsonObject("payload").deepCopy();payload.getAsJsonArray("skus").get(0).getAsJsonObject().addProperty("priceCent",1002);
            var updated=request(supplyMvc,put(Stage1Routes.STORE+"/goods/"+goods+"/content"),p.owner().login().session(),map("requestId",req(),"expectedVersion",detail.getAsJsonObject("goods").get("version").getAsLong(),"confirmUnlist",true,"payload",payload),200);long v=updated.getAsJsonObject("goods").get("version").getAsLong(),content=updated.getAsJsonObject("goods").get("contentVersion").getAsLong();
            request(supplyMvc,post(Stage1Routes.STORE+"/goods/"+goods+"/submit"),p.owner().login().session(),map("requestId",req(),"expectedVersion",v),200);var approved=request(supplyMvc,post(Stage1Routes.MANAGER+"/goods/"+goods+"/approve"),reviewer,map("requestId",req(),"expectedVersion",v+1,"contentVersion",content),200);request(supplyMvc,post(Stage1Routes.STORE+"/goods/"+goods+"/list"),p.owner().login().session(),map("requestId",req(),"expectedVersion",approved.getAsJsonObject("goods").get("version").getAsLong()),200);
        }),
        new Source("store-close","update li_store",p->request(supplyMvc,post(Stage1Routes.MANAGER+"/stores/"+p.owner().store()+"/close"),operator,map("requestId",req(),"expectedVersion",db.count("SELECT version FROM li_p0_store_control WHERE store_id=?",p.owner().store()),"reason","S2竞争监管停业"),200)),
        new Source("qualification","update li_p0_qualification",p->{var row=db.required("SELECT id,version FROM li_p0_qualification WHERE store_id=? AND revoked_at IS NULL ORDER BY id LIMIT 1",p.owner().store());request(supplyMvc,post(Stage1Routes.MANAGER+"/qualifications/"+id(row,"id")+"/revoke"),reviewer,map("requestId",req(),"expectedVersion",number(row,"version"),"reason","S2竞争资质撤销"),200);}),
        new Source("agreement","update li_p0_agreement",p->{var row=db.required("SELECT id,version FROM li_p0_agreement WHERE store_id=? AND status='CONFIRMED' ORDER BY id DESC LIMIT 1",p.owner().store());request(supplyMvc,post(Stage1Routes.MANAGER+"/agreements/"+id(row,"id")+"/terminate"),operator,map("requestId",req(),"expectedVersion",number(row,"version"),"reason","S2竞争协议终止"),200);}),
        new Source("rate-window","update li_p0_commission_rule",p->{var row=db.required("SELECT id,version FROM li_p0_commission_rule WHERE store_id=? ORDER BY id DESC LIMIT 1",p.owner().store());request(supplyMvc,post(Stage1Routes.MANAGER+"/commission-rules/"+id(row,"id")+"/disable"),channel,map("requestId",req(),"expectedVersion",number(row,"version"),"reason","S2竞争费率窗口停止"),200);}),
        new Source("fee","update li_p0_fulfillment_config",p->{var row=db.required("SELECT version,methods FROM li_p0_fulfillment_config WHERE store_id=?",p.owner().store());var methods=object(row.get("methods"));methods.getAsJsonObject("MERCHANT_DELIVERY").addProperty("feeCent",301);request(supplyMvc,put(Stage1Routes.STORE+"/fulfillment"),p.owner().login().session(),map("requestId",req(),"expectedVersion",number(row,"version"),"methods",methods,"reason","S2竞争配送费用"),200);}),
        new Source("channel-relation","update li_p0_submerchant_relation",p->{var row=db.required("SELECT id,version FROM li_p0_submerchant_relation WHERE store_id=?",p.owner().store());request(supplyMvc,post(Stage1Routes.MANAGER+"/submerchants/"+id(row,"id")+"/disable"),channel,map("requestId",req(),"expectedVersion",number(row,"version"),"reason","S2竞争渠道关系"),200);}),
        new Source("point","update li_p0_point_control",p->{var row=db.required("SELECT point_id,version FROM li_p0_point_control WHERE store_id=? AND type='COMMUNITY_PICKUP'",p.owner().store());request(supplyMvc,post(Stage1Routes.STORE+"/points/"+id(row,"point_id")+"/disable"),p.owner().login().session(),map("requestId",req(),"expectedVersion",number(row,"version"),"reason","S2竞争选中点位"),200);})
    );}
    static Purchase fixture(Source source)throws Exception {
        var p=purchase(1);if(!source.name.equals("point"))return p;
        var points=Stage2ScenarioCompletionTest.allMethods(p.owner());String sku=Stage2ScenarioCompletionTest.goodsFor(p.owner(),List.of("COMMUNITY_PICKUP"),1001,10);
        var q=request(transactionMvc,post(Stage2Routes.BASE+"/checkout/quotes"),p.resident().session(),map("items",List.of(map("skuId",sku,"quantity",1)),"stores",List.of(map("storeId",p.owner().store(),"method","COMMUNITY_PICKUP","pointId",points.get("COMMUNITY_PICKUP"),"contact",map("name","S2测试居民","mobile","13800000000")))),200);
        mvc=transactionMvc;return new Purchase(p.owner(),p.resident(),sku,p.address(),q,map("intentId","intent_"+req(),"quoteId",q.get("quoteId").getAsString(),"confirmationHash",q.get("confirmationHash").getAsString()));
    }
    static String normalized(String sql){return sql.replace("`","").replaceAll("\\s+"," ").toLowerCase(Locale.ROOT);}

    @Test void t2UsesTheSameCommitBarrierForEveryRealSourceMaintenance()throws Exception {
        for(var source:sources())for(boolean sourceFirst:List.of(true,false)){
            var p=fixture(source);var held=new CountDownLatch(1);var go=new CountDownLatch(1);var attempted=new CountDownLatch(1);var hit=new AtomicBoolean();var executor=Executors.newFixedThreadPool(2);
            try{
                var first=executor.submit(()->{sqlObserver.set(sql->{if(normalized(sql).startsWith(sourceFirst?source.sql:"insert into li_trade")&&hit.compareAndSet(false,true)){held.countDown();await(go);}});try{if(sourceFirst){source.change.apply(p);return (JsonObject)null;}return request(transactionMvc,post(Stage2Routes.BASE+"/checkout/intents"),p.resident().session(),p.create(),200);}finally{sqlObserver.remove();}});
                try{await(held);}catch(AssertionError barrier){if(first.isDone())first.get();throw barrier;}
                var second=executor.submit(()->{attempted.countDown();if(sourceFirst)return request(transactionMvc,post(Stage2Routes.BASE+"/checkout/intents"),p.resident().session(),p.create(),200);source.change.apply(p);return (JsonObject)null;});await(attempted);assertFalse(second.isDone());go.countDown();var a=first.get(40,TimeUnit.SECONDS);var b=second.get(40,TimeUnit.SECONDS);var result=sourceFirst?b:a;
                assertTrue(hit.get());assertEquals(sourceFirst?"FAILED":"SUCCEEDED",result.get("status").getAsString());assertEquals(sourceFirst?0:1,db.count("SELECT COUNT(*) FROM li_p0_trade_state WHERE member_id=?",p.resident().memberId()));assertEquals(sourceFirst?10:9,available(p.sku()));assertEquals(sourceFirst?0:1,s2inventory.held(p.sku()));
                note("TC-S2-Q-011-01",source.name+"/"+(sourceFirst?"source-first":"T2-first"),map("intent",p.create().get("intentId")),map("intent",result,"stock",stock(p.sku()),"sourceViaRealService",true));
            }finally{go.countDown();executor.shutdownNow();}
        }
    }

    @Test void t3BeforeAndAfterDurableAuthorizationKeepsOneOriginalPaymentIdentity()throws Exception {
        for(var source:sources())for(boolean afterAuthorization:List.of(false,true)){
            var p=fixture(source);String trade=create(p);prepare(p,trade);String combine=id(db.required("SELECT combine_no FROM li_p0_payment WHERE trade_id=?",trade),"combine_no");String snapshots=hash(db.rows("SELECT * FROM li_p0_order_snapshot WHERE trade_id=? ORDER BY order_id",trade));
            var entered=new CountDownLatch(1);var go=new CountDownLatch(1);var calls=new AtomicInteger();var delayed=spy(paymentMock);doAnswer(inv->{calls.incrementAndGet();assertFalse(TransactionSynchronizationManager.isActualTransactionActive());entered.countDown();await(go);return inv.callRealMethod();}).when(delayed).launch(any(JsonObject.class));ReflectionTestUtils.setField(s2payments,"channel",delayed);var executor=Executors.newSingleThreadExecutor();
            try{
                if(afterAuthorization){var payment=executor.submit(()->launch(p,trade));await(entered);assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_payment_operation WHERE trade_id=? AND operation_type='LAUNCH' AND status='MAYBE_SENT'",trade));source.change.apply(p);go.countDown();payment.get(40,TimeUnit.SECONDS);assertEquals(1,calls.get());assertEquals("PAID",id(db.required("SELECT status FROM li_p0_trade_state WHERE trade_id=?",trade),"status"));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='CAPTURE'",trade));}
                else{source.change.apply(p);launch(p,trade);assertEquals(0,calls.get());assertEquals("CLOSED",id(db.required("SELECT status FROM li_p0_trade_state WHERE trade_id=?",trade),"status"));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='RELEASE'",trade));}
                assertEquals(combine,id(db.required("SELECT combine_no FROM li_p0_payment WHERE trade_id=?",trade),"combine_no"));assertEquals(snapshots,hash(db.rows("SELECT * FROM li_p0_order_snapshot WHERE trade_id=? ORDER BY order_id",trade)));assertEquals(0,s2inventory.held(p.sku()));
                note("TC-S2-Q-012-01",source.name+"/"+(afterAuthorization?"durable-authority-first":"source-first"),map("trade",trade,"combine",combine),map("final",facts(trade),"dispatches",calls.get(),"snapshotsUnchanged",true));
            }finally{go.countDown();executor.shutdownNow();ReflectionTestUtils.setField(s2payments,"channel",paymentMock);}
        }
    }
}
