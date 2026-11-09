package cn.lili.common.security.session;

import cn.lili.modules.community.p0.stage2.*;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.common.security.session.Stage2PersistenceTest.*;
import static cn.lili.common.security.session.Stage2RemainingVerificationTest.request;
import static cn.lili.common.security.session.Stage2RemainingVerificationTest.await;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Additional approved variants. Owned S2 only; no global worker scans or real channel. */
@EnabledIfSystemProperty(named="s2.integration",matches="owned-run")
@Timeout(value=10,unit=TimeUnit.MINUTES)
class Stage2AcceptanceVerificationTest {
    @BeforeAll static void open() throws Exception { Stage2RemainingVerificationTest.open(); }
    @BeforeEach void before(){simulation.offset=0;mvc=transactionMvc;}
    @AfterAll static void close() throws Exception {Stage2PersistenceTest.closeS2();}
    static long available(String sku){return db.count("SELECT quantity FROM li_goods_sku WHERE id=?",sku);}
    static long stockVersion(String sku){return db.count("SELECT inventory_version FROM li_p0_sku_control WHERE sku_id=?",sku);}
    static Object stock(String sku){return map("skuId",sku,"A",available(sku),"H",s2inventory.held(sku),"version",stockVersion(sku));}
    static Object facts(String trade){return map("tradeId",trade,"state",db.required("SELECT trade_id,member_id,status,payment_status,close_status,protection,amount_cent,pay_deadline FROM li_p0_trade_state WHERE trade_id=?",trade),
        "holds",db.rows("SELECT id,sku_id,status,quantity,version FROM li_p0_inventory_hold WHERE trade_id=? ORDER BY sku_id",trade),
        "events",db.rows("SELECT sku_id,event_type,before_a,after_a,before_h,after_h,inventory_version FROM li_p0_inventory_event WHERE trade_id=? ORDER BY id",trade));}
    static void note(String tc,String variant,Object input,Object actual){observations.add(map("tc",tc,"variant",variant,"input",input,"actual",actual));}
    static JsonObject launch(Purchase p,String trade)throws Exception{return request(transactionMvc,post(Stage2Routes.BASE+"/trades/"+trade+"/payment/launch"),p.resident().session(),map("operationId",req()),200);}
    static String goods(OwnerFixture owner,long cents,int quantity)throws Exception{
        mvc=supplyMvc;var payload=object(goodsPayload(unitFixture(),upload(owner.login().session(),Stage1Routes.STORE,"GOODS"),quantity));
        payload.addProperty("name","S2_TEST acceptance "+cents);payload.getAsJsonArray("skus").get(0).getAsJsonObject().addProperty("priceCent",cents);
        var made=call(post(Stage1Routes.STORE+"/goods"),owner.login().session(),map("requestId",req(),"payload",payload),200);
        String id=made.getAsJsonObject("goods").get("id").getAsString();
        call(post(Stage1Routes.STORE+"/goods/"+id+"/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",0),200);
        call(post(Stage1Routes.MANAGER+"/goods/"+id+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"contentVersion",0),200);
        call(post(Stage1Routes.STORE+"/goods/"+id+"/list"),owner.login().session(),map("requestId",req(),"expectedVersion",2),200);
        return made.getAsJsonArray("skus").get(0).getAsJsonObject().get("id").getAsString();
    }
    static JsonObject child(JsonObject result){return result.getAsJsonObject("paid").getAsJsonArray("subOrders").get(0).getAsJsonObject();}

    @Test void fullPaidMismatchMatrixUsesBothNotificationAndRealQueryRecovery()throws Exception{
        mvc=supplyMvc;String config=channelConfigFixture(10000);var a=readyStore(config);var b=readyStore(config);String sa=goods(a,1001,50),sb=goods(b,2500,50);
        request(supplyMvc,put(Stage1Routes.STORE+"/fulfillment"),a.login().session(),map("requestId",req(),"expectedVersion",1,"reason","S2_TEST 300 cent delivery", "methods",map("MERCHANT_DELIVERY",map("enabled",true,"feeCent",300,"districtCodes",List.of("990101")),"EXPRESS",map("enabled",false),"COMMUNITY_PICKUP",map("enabled",false),"STORE_REDEMPTION",map("enabled",false))),200);
        var mutations=new LinkedHashMap<String,Consumer<JsonObject>>();
        mutations.put("untrusted-source",r->r.addProperty("signature","invalid"));
        mutations.put("amount-3800",r->r.getAsJsonObject("paid").addProperty("amountCent",3800));
        mutations.put("currency-USD",r->r.getAsJsonObject("paid").addProperty("currency","USD"));
        mutations.put("missing-suborder",r->r.getAsJsonObject("paid").getAsJsonArray("subOrders").remove(1));
        mutations.put("duplicate-suborder",r->{var subs=r.getAsJsonObject("paid").getAsJsonArray("subOrders");subs.set(1,subs.get(0).deepCopy());});
        mutations.put("wrong-merchant",r->child(r).addProperty("subMchid","S2_TEST_OTHER"));
        mutations.put("missing-transaction",r->child(r).remove("transactionId"));
        mutations.put("missing-paid-time",r->child(r).remove("paidAt"));
        mutations.put("cross-mode",r->r.addProperty("mode","REAL"));
        for(String entry:List.of("notification","query"))for(var variant:mutations.entrySet()){
            // Do not carry payment backoff clock injection into the next Session creation.
            simulation.offset=0;
            var resident=residentFixture();var address=request(transactionMvc,post(Stage2Routes.BASE+"/addresses"),resident.session(),Stage2RemainingVerificationTest.addressBody(0),200);
            String addressId=address.get("defaultAddressId").getAsString();
            var stores=List.of(map("storeId",a.store(),"method","MERCHANT_DELIVERY","addressId",addressId,"addressVersion",0,"contact",map("name","S2_TEST resident","mobile","13800000000")),map("storeId",b.store(),"method","MERCHANT_DELIVERY","addressId",addressId,"addressVersion",0,"contact",map("name","S2_TEST resident","mobile","13800000000")));
            var q=request(transactionMvc,post(Stage2Routes.BASE+"/checkout/quotes"),resident.session(),map("items",List.of(map("skuId",sa,"quantity",1),map("skuId",sb,"quantity",1)),"stores",stores),200);assertEquals(3801,q.get("amountCent").getAsLong());
            var made=request(transactionMvc,post(Stage2Routes.BASE+"/checkout/intents"),resident.session(),map("intentId","intent_"+req(),"quoteId",q.get("quoteId").getAsString(),"confirmationHash",q.get("confirmationHash").getAsString()),200);String trade=made.get("tradeId").getAsString();
            request(transactionMvc,post(Stage2Routes.BASE+"/trades/"+trade+"/payment/prepare"),resident.session(),map("operationId",req()),200);
            var original=object(db.required("SELECT request_json FROM li_p0_payment WHERE trade_id=?",trade).get("request_json"));var valid=paymentMock.launch(original);var bad=valid.deepCopy();bad.addProperty("eventId","bad_"+req());variant.getValue().accept(bad);if(!variant.getKey().equals("untrusted-source"))bad.addProperty("signature",paymentMock.sign(bad));
            long beforeA=available(sa),beforeB=available(sb);Object heldBefore=List.of(stock(sa),stock(sb));
            if(entry.equals("notification")){if(variant.getKey().equals("untrusted-source"))assertThrows(Stage1Exception.class,()->s2payments.notification(bad));else s2payments.notification(bad);}
            else{var altered=spy(paymentMock);doReturn(bad).when(altered).query(any(JsonObject.class));ReflectionTestUtils.setField(s2payments,"channel",altered);try{simulation.advance(6);s2payments.recover(trade);}finally{ReflectionTestUtils.setField(s2payments,"channel",paymentMock);}}
            assertNotEquals("SUCCEEDED",id(db.required("SELECT status FROM li_p0_payment WHERE trade_id=?",trade),"status"));
            assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type IN ('CAPTURE','RELEASE')",trade));assertEquals(2,db.count("SELECT COUNT(*) FROM li_p0_inventory_hold WHERE trade_id=? AND status='HELD'",trade));assertEquals(beforeA,available(sa));assertEquals(beforeB,available(sb));
            Object rejected=facts(trade);s2payments.notification(valid);paymentMock.delivered(valid.get("eventId").getAsString());assertEquals("PAID",id(db.required("SELECT status FROM li_p0_trade_state WHERE trade_id=?",trade),"status"));assertEquals(2,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='CAPTURE'",trade));assertEquals(beforeA,available(sa));assertEquals(beforeB,available(sb));
            var paid=Stage2PaymentFacts.require(valid,original);for(var raw:paid.getAsJsonArray("subOrders")){var sub=raw.getAsJsonObject();assertEquals(Instant.parse(sub.get("paidAt").getAsString()),instant(db.required("SELECT payment_time FROM li_order WHERE id=?",sub.get("orderId").getAsString()).get("payment_time")));}
            String first=hash(object(db.required("SELECT result_json FROM li_p0_payment WHERE trade_id=?",trade).get("result_json")).get("paid"));
            var contradiction=valid.deepCopy();contradiction.addProperty("eventId","conflict_"+req());child(contradiction).addProperty("transactionId","S2_TEST_DIFFERENT_"+req());contradiction.addProperty("signature",paymentMock.sign(contradiction));s2payments.notification(contradiction);assertEquals(first,hash(object(db.required("SELECT result_json FROM li_p0_payment WHERE trade_id=?",trade).get("result_json")).get("paid")));assertNotNull(db.required("SELECT protection FROM li_p0_trade_state WHERE trade_id=?",trade).get("protection"));s2payments.notification(valid);
            note("TC-S2-S11-009-01",entry+"/"+variant.getKey(),map("tradeId",trade,"combineNo",original.get("combineNo"),"amountCent",3801,"invalidEvidenceHash",hash(bad),"clock","query branch advances controlled clock 6 seconds"),map("stockBefore",heldBefore,"afterRejected",rejected,"final",facts(trade),"firstPaidHash",first,"paidFacts",paid,"nativeOrders",db.rows("SELECT CAST(id AS CHAR) AS orderId,payment_time,pay_status FROM li_order WHERE trade_sn=(SELECT sn FROM li_trade WHERE id=?) ORDER BY id",trade)));
        }
    }

    @Test void paidLocalFailureCannotCreateCancelReleaseOrNewPaymentIdentity()throws Exception{
        var p=purchase(2);String trade=create(p);prepare(p,trade);var broken=spy(s2inventory);doThrow(new org.springframework.dao.DataAccessResourceFailureException("S2_TEST capture fault")).when(broken).settle(trade,true);ReflectionTestUtils.setField(s2payments,"inventory",broken);
        Object pending;
        try{launch(p,trade);String combine=id(db.required("SELECT combine_no FROM li_p0_payment WHERE trade_id=?",trade),"combine_no");cancel(p,trade);var again=prepare(p,trade);assertFalse(again.get("launchReady").getAsBoolean());assertEquals("NONE",id(db.required("SELECT close_status FROM li_p0_trade_state WHERE trade_id=?",trade),"close_status"));assertEquals("SUCCEEDED",id(db.required("SELECT status FROM li_p0_payment WHERE trade_id=?",trade),"status"));assertEquals(combine,id(db.required("SELECT combine_no FROM li_p0_payment WHERE trade_id=?",trade),"combine_no"));assertEquals(2,s2inventory.held(p.sku()));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type IN ('CAPTURE','RELEASE')",trade));pending=facts(trade);}
        finally{ReflectionTestUtils.setField(s2payments,"inventory",s2inventory);}
        s2payments.recover(trade);assertEquals("PAID",id(db.required("SELECT status FROM li_p0_trade_state WHERE trade_id=?",trade),"status"));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='CAPTURE'",trade));note("TC-S2-Q-013-01","paid-local-pending-real-cancel-and-prepare",map("quantity",2),map("pending",pending,"recovered",facts(trade)));
    }

    @Test void manualTargetRacesReserveCaptureAndReleaseInBothOrders()throws Exception{
        for(String operation:List.of("RESERVE","CAPTURE","RELEASE"))for(boolean manualFirst:List.of(true,false)){
            var p=purchase(1);String trade=operation.equals("RESERVE")?null:create(p);if(operation.equals("CAPTURE"))prepare(p,trade);long version=stockVersion(p.sku());String originalTrade=trade;Object before=stock(p.sku());var held=new CountDownLatch(1);var release=new CountDownLatch(1);var attempted=new CountDownLatch(1);var stopped=new AtomicBoolean();var executor=Executors.newFixedThreadPool(2);
            Callable<JsonObject> manual=()->request(supplyMvc,put(Stage1Routes.STORE+"/skus/"+p.sku()+"/stock"),p.owner().login().session(),map("requestId",req(),"expectedVersion",version,"targetQuantity",7,"reason","S2_TEST concurrent A prime"),manualFirst?200:409);
            Callable<JsonObject> action=()->operation.equals("RESERVE")?request(transactionMvc,post(Stage2Routes.BASE+"/checkout/intents"),p.resident().session(),p.create(),200):operation.equals("CAPTURE")?launch(p,originalTrade):cancel(p,originalTrade);
            try{
                var first=executor.submit(()->{sqlObserver.set(sql->{String q=sql.replace("`","").toLowerCase(Locale.ROOT).replaceAll("\\s+"," ").stripLeading();boolean skuWrite=q.startsWith("update li_goods_sku ")&&q.contains("quantity");boolean point=manualFirst?skuWrite:operation.equals("CAPTURE")?q.startsWith("update li_p0_inventory_hold set status"):skuWrite;if(point&&stopped.compareAndSet(false,true)){held.countDown();await(release);}});try{return (manualFirst?manual:action).call();}finally{sqlObserver.remove();}});await(held);
                var second=executor.submit(()->{sqlObserver.set(sql->{if(sql.contains("FROM li_store")&&sql.contains("FOR UPDATE"))attempted.countDown();});try{return (manualFirst?action:manual).call();}finally{sqlObserver.remove();}});await(attempted);assertFalse(first.isDone());assertFalse(second.isDone());release.countDown();var firstResult=first.get(40,TimeUnit.SECONDS);var secondResult=second.get(40,TimeUnit.SECONDS);if(trade==null)trade=(manualFirst?secondResult:firstResult).get("tradeId").getAsString();
                long expectedA=manualFirst?(operation.equals("RESERVE")?6:operation.equals("CAPTURE")?7:8):(operation.equals("RELEASE")?10:9);assertEquals(expectedA,available(p.sku()));assertEquals(operation.equals("RESERVE")?1:0,s2inventory.held(p.sku()));assertEquals(operation.equals("CAPTURE")?1:0,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='CAPTURE'",trade));assertEquals(operation.equals("RELEASE")?1:0,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='RELEASE'",trade));note("TC-S2-S09-009-01",operation+"/"+(manualFirst?"manual-first":"trade-first"),map("targetA",7,"expectedVersion",version,"before",before),map("trade",facts(trade),"stock",stock(p.sku())));
            }finally{release.countDown();executor.shutdownNow();}
        }
    }

    @Test void t3RealUnlistBeforeOrAfterDurableDispatchHasOneOriginalPaymentIdentity()throws Exception{
        for(boolean sourceFirst:List.of(true,false)){
            var p=purchase(1);String trade=create(p);prepare(p,trade);String combine=id(db.required("SELECT combine_no FROM li_p0_payment WHERE trade_id=?",trade),"combine_no");
            if(sourceFirst){Stage2RemainingVerificationTest.unlist(p);launch(p,trade);assertEquals("CLOSED",id(db.required("SELECT status FROM li_p0_trade_state WHERE trade_id=?",trade),"status"));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_payment_operation WHERE trade_id=? AND operation_type='LAUNCH'",trade));}
            else{var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);var delayed=spy(paymentMock);doAnswer(inv->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());entered.countDown();await(resume);return inv.callRealMethod();}).when(delayed).launch(any(JsonObject.class));ReflectionTestUtils.setField(s2payments,"channel",delayed);var executor=Executors.newSingleThreadExecutor();try{var launched=executor.submit(()->launch(p,trade));await(entered);Stage2RemainingVerificationTest.unlist(p);resume.countDown();launched.get(30,TimeUnit.SECONDS);assertEquals("PAID",id(db.required("SELECT status FROM li_p0_trade_state WHERE trade_id=?",trade),"status"));}finally{resume.countDown();executor.shutdownNow();ReflectionTestUtils.setField(s2payments,"channel",paymentMock);}}
            assertEquals(combine,id(db.required("SELECT combine_no FROM li_p0_payment WHERE trade_id=?",trade),"combine_no"));assertEquals(0,s2inventory.held(p.sku()));note("TC-S2-Q-012-01",sourceFirst?"unlist-before-launch-authorization":"unlist-after-durable-launch-authorization",map("combineNo",combine),facts(trade));
        }
    }
}
