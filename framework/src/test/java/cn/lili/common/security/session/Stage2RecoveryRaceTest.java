package cn.lili.common.security.session;

import cn.lili.modules.community.p0.stage2.*;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.common.security.session.Stage2PersistenceTest.*;
import static cn.lili.common.security.session.Stage2RemainingVerificationTest.*;
import static cn.lili.common.security.session.Stage2AcceptanceVerificationTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfSystemProperty(named="s2.integration",matches="owned-run")
@Timeout(value=10,unit=TimeUnit.MINUTES)
class Stage2RecoveryRaceTest {
    @BeforeAll static void open()throws Exception{simulation.offset=0;Stage2RemainingVerificationTest.open();}
    @BeforeEach void before(){simulation.offset=0;mvc=transactionMvc;}
    @AfterAll static void close()throws Exception{Stage2PersistenceTest.closeS2();}

    @Test void queryLeaseCoalescesTenRefreshesAndFencesTheOldTwentySecondFinisher()throws Exception {
        var p=purchase(1);String trade=create(p);prepare(p,trade);scenario(trade,"UNKNOWN",false);simulation.advance(6);
        var entered=new CountDownLatch(1);var enteredAgain=new CountDownLatch(1);var go=new CountDownLatch(1);var goAgain=new CountDownLatch(1);var calls=new AtomicInteger();
        var channel=spy(paymentMock);doAnswer(inv->{int n=calls.incrementAndGet();if(n==1){entered.countDown();await(go);}else if(n==2){enteredAgain.countDown();await(goAgain);}return inv.callRealMethod();}).when(channel).query(any(JsonObject.class));ReflectionTestUtils.setField(s2payments,"channel",channel);var pool=Executors.newFixedThreadPool(12);
        try {
            var first=pool.submit(()->s2payments.recover(trade));await(entered);var old=db.required("SELECT * FROM li_p0_payment_operation WHERE trade_id=? AND operation_type='QUERY'",trade);assertEquals("RUNNING",id(old,"status"));
            var observers=new ArrayList<Future<?>>();for(int i=0;i<10;i++)observers.add(pool.submit(()->s2payments.recover(trade)));for(var observer:observers)observer.get(20,TimeUnit.SECONDS);assertEquals(1,calls.get());assertEquals(id(old,"lease_owner"),id(db.required("SELECT * FROM li_p0_payment_operation WHERE trade_id=? AND operation_type='QUERY'",trade),"lease_owner"));
            simulation.advance(21);var second=pool.submit(()->s2payments.recover(trade));await(enteredAgain);var current=db.required("SELECT * FROM li_p0_payment_operation WHERE trade_id=? AND operation_type='QUERY'",trade);assertNotEquals(id(old,"lease_owner"),id(current,"lease_owner"));
            go.countDown();first.get(20,TimeUnit.SECONDS);var fenced=db.required("SELECT * FROM li_p0_payment_operation WHERE trade_id=? AND operation_type='QUERY'",trade);assertEquals("RUNNING",id(fenced,"status"));assertEquals(id(current,"lease_owner"),id(fenced,"lease_owner"));
            goAgain.countDown();second.get(20,TimeUnit.SECONDS);assertEquals("IDLE",id(db.required("SELECT * FROM li_p0_payment_operation WHERE trade_id=? AND operation_type='QUERY'",trade),"status"));assertEquals(2,calls.get());assertEquals(1,s2inventory.held(p.sku()));
            scenario(trade,"DELAYED_SUCCESS",true);deliver(trade);s2payments.recover(trade);assertEquals(2,calls.get());assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='CAPTURE'",trade));
            note("TC-S2-S11-013-02","10-observers-20-second-query-lease-old-finisher-fenced",map("trade",trade),map("queries",calls.get(),"clock","controlled +6/+21 seconds, not wall-clock wait","oldOwner",id(old,"lease_owner"),"newOwner",id(current,"lease_owner"),"final",facts(trade)));
        }finally{go.countDown();goAgain.countDown();pool.shutdownNow();ReflectionTestUtils.setField(s2payments,"channel",paymentMock);simulation.offset=0;}
    }

    @Test void manualAvailableTargetsRaceReserveCaptureAndReleaseInBothCommitOrders()throws Exception {
        for(String operation:List.of("reserve","capture","release"))for(boolean manualFirst:List.of(true,false)){
            simulation.offset=0;var p=purchase(2);String trade=operation.equals("reserve")?null:create(p);if(operation.equals("capture"))prepare(p,trade);
            final String original=trade;long expected=stockVersion(p.sku());var held=new CountDownLatch(1);var go=new CountDownLatch(1);var attempted=new CountDownLatch(1);var hit=new AtomicBoolean();var pool=Executors.newFixedThreadPool(2);
            java.util.concurrent.Callable<JsonObject> manual=()->request(transactionMvc,put(Stage1Routes.STORE+"/skus/"+p.sku()+"/stock"),p.owner().login().session(),map("requestId",req(),"expectedVersion",expected,"targetQuantity",5,"reason","S2真实人工目标竞争"),200);
            java.util.concurrent.Callable<String> stockOperation=()->{if(operation.equals("reserve"))return create(p);if(operation.equals("capture")){launch(p,original);return original;}cancel(p,original);return original;};
            try{
                var first=pool.submit(()->{sqlObserver.set(sql->{String s=Stage2SourceRaceTest.normalized(sql);String prefix=manualFirst?"insert into li_p0_inventory_event":operation.equals("reserve")?"insert into li_p0_inventory_hold":"update li_p0_inventory_hold";if(s.startsWith(prefix)&&hit.compareAndSet(false,true)){held.countDown();await(go);}});try{return manualFirst?manual.call():stockOperation.call();}finally{sqlObserver.remove();}});
                try{await(held);}catch(AssertionError timeout){if(first.isDone())first.get();throw timeout;}
                var second=pool.submit(()->{attempted.countDown();if(manualFirst)return stockOperation.call();return request(transactionMvc,put(Stage1Routes.STORE+"/skus/"+p.sku()+"/stock"),p.owner().login().session(),map("requestId",req(),"expectedVersion",expected,"targetQuantity",5,"reason","S2旧版本并发拒绝"),409);});await(attempted);assertFalse(second.isDone());go.countDown();var one=first.get(30,TimeUnit.SECONDS);var two=second.get(30,TimeUnit.SECONDS);assertTrue(hit.get());String actual=operation.equals("reserve")?(String)(manualFirst?two:one):original;
                long finalA=manualFirst?(operation.equals("reserve")?3:operation.equals("capture")?5:7):(operation.equals("release")?10:8),finalH=operation.equals("reserve")?2:0;
                assertEquals(finalA,available(p.sku()));assertEquals(finalH,s2inventory.held(p.sku()));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type=?",actual,operation.equals("reserve")?"RESERVE":operation.equals("capture")?"CAPTURE":"RELEASE"));
                if(!manualFirst)request(transactionMvc,put(Stage1Routes.STORE+"/skus/"+p.sku()+"/stock"),p.owner().login().session(),map("requestId",req(),"expectedVersion",stockVersion(p.sku()),"targetQuantity",5,"reason","S2读取最新版本后恢复输入"),200);
                note("TC-S2-Q-005-01",operation+"/"+(manualFirst?"manual-first":"business-first"),map("trade",actual,"sku",p.sku(),"targetA",5),map("beforeRetryA",finalA,"beforeRetryH",finalH,"final",stock(p.sku())));
            }finally{go.countDown();pool.shutdownNow();}
        }
    }

    @Test void doubleDispatchAndDeadlineBeforeOrAfterPossibleExternalPaymentUseOnlyOriginalIdentity()throws Exception {
        for(boolean channelAlreadyPaid:List.of(false,true)){
            simulation.offset=0;var p=purchase(1);String trade=create(p);prepare(p,trade);String combine=id(db.required("SELECT combine_no FROM li_p0_payment WHERE trade_id=?",trade),"combine_no");var entered=new CountDownLatch(1);var go=new CountDownLatch(1);var calls=new AtomicInteger();var controlled=spy(paymentMock);
            doAnswer(inv->{calls.incrementAndGet();JsonObject result=channelAlreadyPaid?(JsonObject)inv.callRealMethod():null;entered.countDown();await(go);return channelAlreadyPaid?result:inv.callRealMethod();}).when(controlled).launch(any(JsonObject.class));ReflectionTestUtils.setField(s2payments,"channel",controlled);var pool=Executors.newSingleThreadExecutor();
            try{
                var first=pool.submit(()->launch(p,trade));await(entered);request(transactionMvc,post(Stage2Routes.BASE+"/trades/"+trade+"/payment/launch"),p.resident().session(),map("operationId",req()),409);assertEquals(1,calls.get());simulation.advance(901);s2payments.recover(trade);go.countDown();first.get(30,TimeUnit.SECONDS);s2payments.recover(trade);
                assertEquals(channelAlreadyPaid?"PAID":"CLOSED",id(db.required("SELECT status FROM li_p0_trade_state WHERE trade_id=?",trade),"status"));assertEquals(combine,id(db.required("SELECT combine_no FROM li_p0_payment WHERE trade_id=?",trade),"combine_no"));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_payment_operation WHERE trade_id=? AND operation_type='LAUNCH'",trade));assertEquals(0,s2inventory.held(p.sku()));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type=?",trade,channelAlreadyPaid?"CAPTURE":"RELEASE"));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type=?",trade,channelAlreadyPaid?"RELEASE":"CAPTURE"));
                note("TC-S2-Q-012-01","double-dispatch-deadline-"+(channelAlreadyPaid?"after-channel-paid-before-local":"before-channel-send"),map("trade",trade,"combine",combine),map("clock","controlled +901 seconds, not wall-clock deadline","launches",calls.get(),"final",facts(trade)));
            }finally{go.countDown();pool.shutdownNow();ReflectionTestUtils.setField(s2payments,"channel",paymentMock);simulation.offset=0;}
        }
    }

    @Test void secondSkuCaptureFailureRollsBackBothHoldsAndNativePaidProjectionTogether()throws Exception {
        var p=purchase(1);String second=Stage2ScenarioCompletionTest.goodsFor(p.owner(),List.of("MERCHANT_DELIVERY"),1001,10);mvc=transactionMvc;
        var q=request(transactionMvc,post(Stage2Routes.BASE+"/checkout/quotes"),p.resident().session(),map("items",List.of(map("skuId",p.sku(),"quantity",1),map("skuId",second,"quantity",1)),"stores",List.of(map("storeId",p.owner().store(),"method","MERCHANT_DELIVERY","addressId",p.address(),"addressVersion",0,"contact",map("name","S2测试居民","mobile","13800000000")))),200);var made=request(transactionMvc,post(Stage2Routes.BASE+"/checkout/intents"),p.resident().session(),map("intentId","intent_"+req(),"quoteId",q.get("quoteId").getAsString(),"confirmationHash",q.get("confirmationHash").getAsString()),200);String trade=made.get("tradeId").getAsString();prepare(p,trade);var updates=new AtomicInteger();
        try{sqlObserver.set(sql->{if(Stage2SourceRaceTest.normalized(sql).startsWith("update li_p0_inventory_hold")&&updates.incrementAndGet()==2)throw new org.springframework.dao.DataAccessResourceFailureException("S2 second SKU capture cut");});launch(p,trade);}finally{sqlObserver.remove();}
        assertEquals(2,updates.get());assertEquals("SUCCEEDED",id(db.required("SELECT status FROM li_p0_payment WHERE trade_id=?",trade),"status"));assertEquals("PAY_CONFIRMING",id(db.required("SELECT status FROM li_p0_trade_state WHERE trade_id=?",trade),"status"));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='CAPTURE'",trade));assertEquals(0,db.count("SELECT COUNT(*) FROM li_order WHERE trade_sn=(SELECT sn FROM li_trade WHERE id=?) AND pay_status='PAID'",trade));
        for(String sku:List.of(p.sku(),second)){assertEquals(9,available(sku));assertEquals(1,s2inventory.held(sku));}var before=db.required("SELECT result_json FROM li_p0_payment WHERE trade_id=?",trade).get("result_json");cancel(p,trade);assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='RELEASE'",trade));s2payments.recover(trade);s2payments.recover(trade);assertEquals("PAID",id(db.required("SELECT status FROM li_p0_trade_state WHERE trade_id=?",trade),"status"));assertEquals(before,db.required("SELECT result_json FROM li_p0_payment WHERE trade_id=?",trade).get("result_json"));assertEquals(2,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='CAPTURE'",trade));for(String sku:List.of(p.sku(),second)){assertEquals(9,available(sku));assertEquals(0,s2inventory.held(sku));}
        note("TC-S2-Q-013-01","second-SKU-capture-rollback-with-durable-paid-fact",map("trade",trade,"skus",List.of(p.sku(),second)),facts(trade));
    }
}
