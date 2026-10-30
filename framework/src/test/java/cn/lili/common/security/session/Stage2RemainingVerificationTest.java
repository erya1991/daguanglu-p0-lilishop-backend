package cn.lili.common.security.session;

import cn.lili.modules.community.p0.stage2.*;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.common.security.session.Stage2PersistenceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Prepared real MySQL/Redis tests. Synthetic Session identities; no WeChat/MAN claim.
 * Must run via the unchanged protected build entry, with the registered S2 Manager stopped.
 * Clock advances are labelled; no fabricated RUNNING/lease rows and no old task reset. */
@EnabledIfSystemProperty(named="s2.integration",matches="owned-run")
@Timeout(value=5,unit=TimeUnit.MINUTES)
class Stage2RemainingVerificationTest {
    static boolean initialized;
    @BeforeAll static void open() throws Exception {
        var directory=Path.of(Objects.requireNonNull(System.getenv("S2_RUN"))).toRealPath();
        var manager=object(Files.readString(directory.resolve("runtime/apis.json"))).getAsJsonObject("manager");
        assertFalse(ProcessHandle.of(manager.get("pid").getAsLong()).map(ProcessHandle::isAlive).orElse(false),
            "Registered S2 background worker must be stopped through protected lifecycle first");
        initialized=true;Stage2PersistenceTest.initialize();
    }
    @BeforeEach void before(){simulation.offset=0;mvc=transactionMvc;}
    @AfterAll static void close() throws Exception {if(initialized)Stage2PersistenceTest.closeS2();}
    static JsonObject request(MockMvc endpoint,MockHttpServletRequestBuilder builder,SessionTokens token,Object body,int expected)throws Exception{
        if(token!=null)builder.header("accessToken",token.accessToken());
        if(body!=null)builder.contentType("application/json").content(write(body));
        return checked(endpoint.perform(builder).andReturn(),expected);
    }
    static void await(CountDownLatch latch){try{assertTrue(latch.await(20,TimeUnit.SECONDS),"Test barrier timed out");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new AssertionError(e);}}
    static JsonObject unlist(Purchase p)throws Exception{
        var goods=db.required("SELECT c.goods_id,c.version FROM li_p0_sku_control s JOIN li_p0_goods_control c ON c.goods_id=s.goods_id WHERE s.sku_id=?",p.sku());
        return request(supplyMvc,post(Stage1Routes.STORE+"/goods/"+id(goods,"goods_id")+"/unlist"),p.owner().login().session(),map("requestId",req(),"expectedVersion",number(goods,"version"),"reason","S2_TEST真实维护竞争"),200);
    }
    @Test void q011T2AndRealUnlistSerializeInBothCommitOrders()throws Exception{
        for(boolean sourceFirst:List.of(false,true)){
            var p=purchase(1);var held=new CountDownLatch(1);var release=new CountDownLatch(1);var attempted=new CountDownLatch(1);var stopped=new AtomicBoolean();
            var executor=Executors.newFixedThreadPool(2);
            try{
                var first=executor.submit(()->{
                    sqlObserver.set(sql->{String s=sql.replace("`","").toLowerCase(Locale.ROOT);boolean point=sourceFirst?s.startsWith("update li_p0_goods_control"):s.startsWith("insert into li_trade");if(point&&stopped.compareAndSet(false,true)){held.countDown();await(release);}});
                    try{return sourceFirst?unlist(p):request(transactionMvc,post(Stage2Routes.BASE+"/checkout/intents"),p.resident().session(),p.create(),200);}finally{sqlObserver.remove();}
                });
                await(held);
                var second=executor.submit(()->{
                    sqlObserver.set(sql->{if(sql.contains("FROM li_store")&&sql.contains("FOR UPDATE"))attempted.countDown();});
                    try{return sourceFirst?request(transactionMvc,post(Stage2Routes.BASE+"/checkout/intents"),p.resident().session(),p.create(),200):unlist(p);}finally{sqlObserver.remove();}
                });
                await(attempted);assertFalse(first.isDone());assertFalse(second.isDone());release.countDown();
                var a=first.get(30,TimeUnit.SECONDS);var b=second.get(30,TimeUnit.SECONDS);var creation=sourceFirst?b:a;
                assertEquals(sourceFirst?"FAILED":"SUCCEEDED",creation.get("status").getAsString());
                assertEquals(sourceFirst?0:1,db.count("SELECT COUNT(*) FROM li_trade WHERE member_id=?",p.resident().memberId()));
                assertEquals(sourceFirst?0:1,s2inventory.held(p.sku()));
                observations.add(map("tc","TC-S2-Q-011-01","variant",sourceFirst?"unlist-first":"t2-first","realSourceMaintenance",true,"creationStatus",creation.get("status")));
            }finally{release.countDown();executor.shutdownNow();}
        }
    }
    @Test void q012AuthorizedButDelayedLaunchCompetesWithRealCancelOutsideDatabaseTransaction()throws Exception{
        var p=purchase(1);String trade=create(p);prepare(p,trade);var entered=new CountDownLatch(1);var resume=new CountDownLatch(1);var delayed=spy(paymentMock);
        doAnswer(inv->{assertFalse(TransactionSynchronizationManager.isActualTransactionActive());entered.countDown();await(resume);return inv.callRealMethod();}).when(delayed).launch(any(JsonObject.class));
        ReflectionTestUtils.setField(s2payments,"channel",delayed);var executor=Executors.newSingleThreadExecutor();
        try{
            var launch=executor.submit(()->request(transactionMvc,post(Stage2Routes.BASE+"/trades/"+trade+"/payment/launch"),p.resident().session(),map("operationId",req()),200));
            await(entered);assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_payment_operation WHERE trade_id=? AND operation_type='LAUNCH' AND status='MAYBE_SENT'",trade));
            cancel(p,trade);resume.countDown();launch.get(30,TimeUnit.SECONDS);
            assertEquals("CLOSED",id(db.required("SELECT status FROM li_p0_trade_state WHERE trade_id=?",trade),"status"));
            assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_payment WHERE trade_id=?",trade));assertEquals(0,s2inventory.held(p.sku()));
            assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='CAPTURE'",trade));
            assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='RELEASE'",trade));
            observations.add(map("tc","TC-S2-Q-012-01","variant","durable-launch-before-cancel-delayed-channel-call","tradeId",trade,"externalCallOutsideTransaction",true));
        }finally{resume.countDown();executor.shutdownNow();ReflectionTestUtils.setField(s2payments,"channel",paymentMock);}
    }
    @Test void q013TrustedPaidCommitSurvivesLocalCaptureFailureAndRetainsChannelTime()throws Exception{
        var p=purchase(2);String trade=create(p);prepare(p,trade);var broken=spy(s2inventory);
        doThrow(new org.springframework.dao.DataAccessResourceFailureException("S2_TEST capture unavailable")).when(broken).settle(trade,true);
        ReflectionTestUtils.setField(s2payments,"inventory",broken);
        try{
            var result=request(transactionMvc,post(Stage2Routes.BASE+"/trades/"+trade+"/payment/launch"),p.resident().session(),map("operationId",req()),200).getAsJsonObject("trade");
            assertEquals("SUCCEEDED",result.get("paymentStatus").getAsString());assertEquals("PAY_CONFIRMING",result.get("status").getAsString());
            assertNotNull(result.get("paidAt"));assertFalse(result.get("paidAt").isJsonNull());assertEquals(2,s2inventory.held(p.sku()));
            assertFalse(result.getAsJsonArray("allowedActions").asList().contains(new JsonPrimitive("PAY")));
            assertFalse(result.getAsJsonArray("allowedActions").asList().contains(new JsonPrimitive("CANCEL")));
            assertTrue(db.count("SELECT COUNT(*) FROM li_p0_s2_task WHERE target_key=? AND task_type='PAYMENT'",trade)>0);
        }finally{ReflectionTestUtils.setField(s2payments,"inventory",s2inventory);}
        var pay=db.required("SELECT result_json FROM li_p0_payment WHERE trade_id=?",trade);var actual=object(pay.get("result_json")).getAsJsonObject("paid");
        simulation.advance(90);s2payments.recover(trade);s2payments.recover(trade);
        assertEquals("PAID",id(db.required("SELECT status FROM li_p0_trade_state WHERE trade_id=?",trade),"status"));
        var sub=actual.getAsJsonArray("subOrders").get(0).getAsJsonObject();
        assertEquals(Instant.parse(sub.get("paidAt").getAsString()),instant(db.required("SELECT payment_time FROM li_order WHERE id=?",sub.get("orderId").getAsString()).get("payment_time")));
        assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='CAPTURE'",trade));assertEquals(8,db.count("SELECT quantity FROM li_goods_sku WHERE id=?",p.sku()));
        observations.add(map("tc","TC-S2-Q-013-01","variant","payment-committed-local-failed-recover","tradeId",trade,"clock","controlled +90 seconds","paidAt",actual.get("paidAt")));
    }
    @Test void repeatedNotificationControlsDeliverActualRowsAndPreserveFirstPaidFacts()throws Exception{
        var p=purchase(1);String trade=create(p);prepare(p,trade);scenario(trade,"DELAYED_SUCCESS",false);
        request(transactionMvc,post(Stage2Routes.BASE+"/trades/"+trade+"/payment/launch"),p.resident().session(),map("operationId",req()),200);
        String combine=id(db.required("SELECT combine_no FROM li_p0_payment WHERE trade_id=?",trade),"combine_no");
        paymentMock.scenario(map("combineNo",combine,"scenario","DELAYED_SUCCESS","releaseDelayed",true,"repeatNotifications",true),s2Settings.getMockControlKey());
        var jdbc=(JdbcTemplate)ReflectionTestUtils.getField(paymentMock,"jdbc");assertNotNull(jdbc);
        var messages=jdbc.queryForList("SELECT payload_json FROM li_p0_mock_delivery WHERE combine_no=? ORDER BY event_id",combine);assertEquals(11,messages.size());
        String facts=hash(object(messages.get(0).get("payload_json")).get("paid"));
        for(var row:messages){var message=object(row.get("payload_json"));s2payments.notification(message);paymentMock.delivered(message.get("eventId").getAsString());}
        var same=object(messages.get(0).get("payload_json"));for(int i=0;i<10;i++)s2payments.notification(same);
        simulation.advance(30);paymentMock.scenario(map("combineNo",combine,"scenario","SUCCESS","correctEvidence",true),s2Settings.getMockControlKey());
        var original=object(db.required("SELECT request_json FROM li_p0_payment WHERE trade_id=?",trade).get("request_json"));assertEquals(facts,hash(paymentMock.query(original).get("paid")));
        assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='CAPTURE'",trade));
        observations.add(map("tc","TC-S2-S11-010-01","variant","ten-distinct-events-and-ten-same-event-replays","tradeId",trade,"ledgerRowsObserved",messages.size(),"sameEventCalls",10));
    }
    @Test void cartTwoIntentsConsumeOldVersionAtMostOnce()throws Exception{
        var p=purchase(1);var added=request(transactionMvc,post(Stage2Routes.BASE+"/cart/items"),p.resident().session(),map("operationId",req(),"skuId",p.sku(),"quantity",2),200);
        var item=added.getAsJsonArray("items").get(0).getAsJsonObject();
        var input=map("items",List.of(map("skuId",p.sku(),"quantity",1,"cartId",item.get("id").getAsString(),"expectedVersion",item.get("version").getAsLong())),"stores",List.of(map("storeId",p.owner().store(),"method","MERCHANT_DELIVERY","addressId",p.address(),"addressVersion",0,"contact",map("name","S2_TEST居民","mobile","13800000000"))));
        var q=request(transactionMvc,post(Stage2Routes.BASE+"/checkout/quotes"),p.resident().session(),input,200);var ready=new CountDownLatch(2);var go=new CountDownLatch(1);var executor=Executors.newFixedThreadPool(2);
        try{
            Callable<JsonObject> create=()->{ready.countDown();await(go);return request(transactionMvc,post(Stage2Routes.BASE+"/checkout/intents"),p.resident().session(),map("intentId","intent_"+req(),"quoteId",q.get("quoteId").getAsString(),"confirmationHash",q.get("confirmationHash").getAsString()),200);};
            var a=executor.submit(create);var b=executor.submit(create);await(ready);go.countDown();var results=List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
            assertEquals(1,results.stream().filter(r->"SUCCEEDED".equals(r.get("status").getAsString())).count());assertEquals(1,results.stream().filter(r->"FAILED".equals(r.get("status").getAsString())).count());
            assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_cart_consumption WHERE cart_id=?",item.get("id").getAsString()));
            assertEquals(1,db.count("SELECT quantity FROM li_p0_cart_item WHERE id=?",item.get("id").getAsString()));
        }finally{go.countDown();executor.shutdownNow();}
    }
    @Test void addressFirstCreationAndLastCapacitySlotAreAtomic()throws Exception{
        var r=residentFixture();var executor=Executors.newFixedThreadPool(2);
        try{
            for(int limit:List.of(0,19)){
                long version=db.count("SELECT COALESCE(MAX(version),0) FROM li_p0_member_checkout WHERE member_id=?",r.memberId());
                while(db.count("SELECT COUNT(*) FROM li_member_address WHERE member_id=? AND delete_flag=0",r.memberId())<limit){address(r.session(),version++,200);}
                long submittedVersion=version;var ready=new CountDownLatch(2);var go=new CountDownLatch(1);
                Callable<Integer> add=()->{ready.countDown();await(go);var builder=post(Stage2Routes.BASE+"/addresses").header("accessToken",r.session().accessToken()).contentType("application/json").content(write(addressBody(submittedVersion)));return transactionMvc.perform(builder).andReturn().getResponse().getStatus();};
                var a=executor.submit(add);var b=executor.submit(add);await(ready);go.countDown();var statuses=List.of(a.get(30,TimeUnit.SECONDS),b.get(30,TimeUnit.SECONDS));
                assertEquals(1,statuses.stream().filter(s->s==200).count());assertEquals(1,statuses.stream().filter(s->s==409).count());
                assertEquals(limit+1,db.count("SELECT COUNT(*) FROM li_member_address WHERE member_id=? AND delete_flag=0",r.memberId()));
                assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_member_checkout c JOIN li_member_address a ON a.id=c.default_address_id AND a.member_id=c.member_id WHERE c.member_id=? AND a.delete_flag=0",r.memberId()));
            }
        }finally{executor.shutdownNow();}
    }
    static Map<String,Object> addressBody(long version){return map("operationId",req(),"addressBookVersion",version,"name","S2_TEST地址","mobile","13800000000","provinceCode","990000","cityCode","990100","districtCode","990101","detail","S2_TEST并发地址一号楼");}
    static void address(SessionTokens resident,long version,int expected)throws Exception{request(transactionMvc,post(Stage2Routes.BASE+"/addresses"),resident,addressBody(version),expected);}

    @Test void exactMinimumPreparationIsInclusiveAndDoesNotCloseAnExistingPreparation()throws Exception{
        try{
            for(long remaining:List.of(999L,1000L,1001L)){
                var p=purchase(1);String trade=create(p);var deadline=instant(db.required("SELECT pay_deadline FROM li_p0_trade_state WHERE trade_id=?",trade).get("pay_deadline"));
                Clock fixed=Clock.fixed(deadline.minusMillis(remaining),ZoneOffset.UTC);
                ReflectionTestUtils.setField(s2payments,"clock",fixed);ReflectionTestUtils.setField(paymentMock,"clock",fixed);
                request(transactionMvc,post(Stage2Routes.BASE+"/trades/"+trade+"/payment/prepare"),p.resident().session(),map("operationId",req()),remaining<1000?409:200);
                assertEquals(remaining<1000?0:1,db.count("SELECT COUNT(*) FROM li_p0_payment WHERE trade_id=?",trade));assertEquals(1,s2inventory.held(p.sku()));
                ReflectionTestUtils.setField(s2payments,"clock",simulation);ReflectionTestUtils.setField(paymentMock,"clock",simulation);
            }
            var p=purchase(1);String trade=create(p);prepare(p,trade);
            var deadline=instant(db.required("SELECT pay_deadline FROM li_p0_trade_state WHERE trade_id=?",trade).get("pay_deadline"));
            Clock fixed=Clock.fixed(deadline.minusMillis(999),ZoneOffset.UTC);
            ReflectionTestUtils.setField(s2payments,"clock",fixed);ReflectionTestUtils.setField(paymentMock,"clock",fixed);
            var original=object(db.required("SELECT request_json FROM li_p0_payment WHERE trade_id=?",trade).get("request_json"));
            assertEquals("UNPAID",paymentMock.prepare(original).get("state").getAsString());assertTrue(prepare(p,trade).get("launchReady").getAsBoolean());
        }finally{ReflectionTestUtils.setField(s2payments,"clock",simulation);ReflectionTestUtils.setField(paymentMock,"clock",simulation);}
    }

    @Test void q013TwoActualWorkersClaimExpiredLeaseAndOldFinisherCannotClearNewGeneration()throws Exception{
        var p=purchase(1);String trade=create(p);prepare(p,trade);scenario(trade,"UNKNOWN",false);
        request(transactionMvc,post(Stage2Routes.BASE+"/trades/"+trade+"/payment/launch"),p.resident().session(),map("operationId",req()),200);
        // Restrict only task selection to this new fixture. Production claim/update/lease SQL runs on MySQL.
        var scoped=mock(Stage1Db.class,inv->{
            var raw=inv.getRawArguments();
            if(inv.getMethod().getName().equals("one")&&raw[0] instanceof String sql&&sql.startsWith("SELECT * FROM li_p0_s2_task WHERE run_id=")){
                var values=(Object[])raw[1];var args=Arrays.copyOf(values,values.length+1);args[values.length]=trade;
                return db.one(sql.replace(" ORDER BY"," AND task_type='PAYMENT' AND target_key=? ORDER BY"),args);
            }
            try{return inv.getMethod().invoke(db,raw);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}
        });
        var firstEntered=new CountDownLatch(1);var secondEntered=new CountDownLatch(1);var firstGo=new CountDownLatch(1);var secondGo=new CountDownLatch(1);var calls=new AtomicInteger();
        var delayed=spy(s2payments);doAnswer(inv->{int n=calls.incrementAndGet();if(n==1){firstEntered.countDown();await(firstGo);}else if(n==2){secondEntered.countDown();await(secondGo);}return inv.callRealMethod();}).when(delayed).recover(trade);
        var template=(org.springframework.transaction.support.TransactionTemplate)ReflectionTestUtils.getField(s2tx,"tx");assertNotNull(template);PlatformTransactionManager manager=Objects.requireNonNull(template.getTransactionManager());
        var a=new Stage2RecoveryWorker(s2tx,scoped,s2Settings,s2trades,delayed,paymentMock,redis,manager,simulation);
        var b=new Stage2RecoveryWorker(s2tx,scoped,s2Settings,s2trades,delayed,paymentMock,redis,manager,simulation);
        var executor=Executors.newFixedThreadPool(2);
        try{
            var first=executor.submit(a::one);await(firstEntered);
            var old=db.required("SELECT * FROM li_p0_s2_task WHERE task_type='PAYMENT' AND target_key=?",trade);assertEquals("RUNNING",id(old,"status"));
            simulation.advance(31);var second=executor.submit(b::one);await(secondEntered);
            var newer=db.required("SELECT * FROM li_p0_s2_task WHERE task_type='PAYMENT' AND target_key=?",trade);assertNotEquals(id(old,"lease_owner"),id(newer,"lease_owner"));
            firstGo.countDown();assertTrue(first.get(30,TimeUnit.SECONDS));
            var fenced=db.required("SELECT * FROM li_p0_s2_task WHERE task_type='PAYMENT' AND target_key=?",trade);assertEquals("RUNNING",id(fenced,"status"));assertEquals(id(newer,"lease_owner"),id(fenced,"lease_owner"));assertTrue(number(fenced,"generation")>number(newer,"generation"));
            secondGo.countDown();assertTrue(second.get(30,TimeUnit.SECONDS));
            assertEquals("PENDING",id(db.required("SELECT status FROM li_p0_s2_task WHERE task_type='PAYMENT' AND target_key=?",trade),"status"));assertEquals(1,s2inventory.held(p.sku()));
            scenario(trade,"DELAYED_SUCCESS",true);deliver(trade);assertTrue(b.one());
            assertEquals("DONE",id(db.required("SELECT status FROM li_p0_s2_task WHERE task_type='PAYMENT' AND target_key=?",trade),"status"));
            assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='CAPTURE'",trade));
            observations.add(map("tc","TC-S2-Q-013-01","variant","actual-two-worker-claims-stale-finisher-fenced","tradeId",trade,"clock","controlled +31 seconds; not real wall-time expiry","selectionRestrictedToNewFixture",true));
        }finally{firstGo.countDown();secondGo.countDown();executor.shutdownNow();}
    }
}
