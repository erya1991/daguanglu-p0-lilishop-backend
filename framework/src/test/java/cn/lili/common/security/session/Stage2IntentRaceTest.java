package cn.lili.common.security.session;

import cn.lili.modules.community.p0.stage2.*;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.web.servlet.MvcResult;
import java.util.*;
import java.util.concurrent.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.common.security.session.Stage2PersistenceTest.*;
import static cn.lili.common.security.session.Stage2RemainingVerificationTest.*;
import static cn.lili.common.security.session.Stage2AcceptanceVerificationTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfSystemProperty(named="s2.integration",matches="owned-run")
@Timeout(value=5,unit=TimeUnit.MINUTES)
class Stage2IntentRaceTest {
    @BeforeAll static void open()throws Exception{simulation.offset=0;Stage2RemainingVerificationTest.open();}
    @BeforeEach void before(){simulation.offset=0;mvc=transactionMvc;}
    @AfterAll static void close()throws Exception{closeS2();}
    @Test void differentFreshMembersCanAddCartItemsInIndependentStoresAtTheSameMissingKeyBarrier()throws Exception {
        mvc=supplyMvc;String config=channelConfigFixture(10000);var oa=readyStore(config);var ob=readyStore(config);var ma=residentFixture();var mb=residentFixture();String sa=id(db.required("SELECT id FROM li_goods_sku WHERE store_id=?",oa.store()),"id"),sb=id(db.required("SELECT id FROM li_goods_sku WHERE store_id=?",ob.store()),"id");
        var entered=new CountDownLatch(2);var go=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);var actors=List.of(ma.session(),mb.session());var skus=List.of(sa,sb);var futures=new ArrayList<Future<MvcResult>>();
        try{for(int i=0;i<2;i++){final int n=i;futures.add(pool.submit(()->{sqlObserver.set(sql->{if(sql.toLowerCase(Locale.ROOT).startsWith("insert into li_p0_cart_item")){entered.countDown();await(go);}});try{return transactionMvc.perform(post(Stage2Routes.BASE+"/cart/items").header("accessToken",actors.get(n).accessToken()).contentType("application/json").content(write(map("operationId",req(),"skuId",skus.get(n),"quantity",1)))).andReturn();}finally{sqlObserver.remove();}}));}await(entered);go.countDown();var results=List.of(futures.get(0).get(30,TimeUnit.SECONDS),futures.get(1).get(30,TimeUnit.SECONDS));var statuses=results.stream().map(r->r.getResponse().getStatus()).toList();observations.add(map("tc","TC-S2-R07-001-01","variant","different-members-independent-store-missing-cart-keys","httpStatuses",statuses));assertEquals(List.of(200,200),statuses);assertEquals(1,db.count("SELECT quantity FROM li_p0_cart_item WHERE member_id=? AND sku_id=?",ma.memberId(),sa));assertEquals(1,db.count("SELECT quantity FROM li_p0_cart_item WHERE member_id=? AND sku_id=?",mb.memberId(),sb));assertEquals(1,checked(results.get(0),200).getAsJsonArray("items").size());assertEquals(1,checked(results.get(1),200).getAsJsonArray("items").size());}
        finally{go.countDown();pool.shutdownNow();}
    }
    @Test void twoFreshMembersCrossTheMissingIntentBarrierThenOnePaysItsOwnLastUnit()throws Exception {
        for(int repetition=0;repetition<3;repetition++){
            var p=purchase(1);var other=residentFixture();request(transactionMvc,put(Stage1Routes.STORE+"/skus/"+p.sku()+"/stock"),p.owner().login().session(),map("requestId",req(),"expectedVersion",stockVersion(p.sku()),"targetQuantity",1,"reason","S2最后一件并发回归"),200);
            var address=request(transactionMvc,post(Stage2Routes.BASE+"/addresses"),other.session(),addressBody(0),200);var q=quote(other,p.owner().store(),p.sku(),1,address.get("defaultAddressId").getAsString());var commands=List.of(p.create(),map("intentId","intent_"+req(),"quoteId",q.get("quoteId").getAsString(),"confirmationHash",q.get("confirmationHash").getAsString()));var actors=List.of(p.resident().session(),other.session());
            var entered=new CountDownLatch(2);var go=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);var futures=new ArrayList<Future<MvcResult>>();
            try{
                for(int i=0;i<2;i++){final int n=i;futures.add(pool.submit(()->{sqlObserver.set(sql->{if(sql.toLowerCase(Locale.ROOT).startsWith("insert into li_p0_checkout_intent")){entered.countDown();await(go);}});try{return transactionMvc.perform(post(Stage2Routes.BASE+"/checkout/intents").header("accessToken",actors.get(n).accessToken()).contentType("application/json").content(write(commands.get(n)))).andReturn();}finally{sqlObserver.remove();}}));}
                await(entered);go.countDown();var responses=List.of(futures.get(0).get(30,TimeUnit.SECONDS),futures.get(1).get(30,TimeUnit.SECONDS));var statuses=responses.stream().map(r->r.getResponse().getStatus()).toList();observations.add(map("tc","TC-S2-S09-007-01","variant","deterministic-two-missing-intents-before-insert","httpStatuses",statuses,"responses",responses.stream().map(r->{try{return object(r.getResponse().getContentAsString());}catch(Exception e){throw new RuntimeException(e);}}).toList()));assertEquals(List.of(200,200),statuses);
                var results=List.of(checked(responses.get(0),200),checked(responses.get(1),200));assertEquals(1,results.stream().filter(r->r.get("status").getAsString().equals("SUCCEEDED")).count());assertEquals(1,results.stream().filter(r->r.get("status").getAsString().equals("FAILED")).count());int winner=results.get(0).get("status").getAsString().equals("SUCCEEDED")?0:1;String trade=results.get(winner).get("tradeId").getAsString();assertEquals(0,available(p.sku()));assertEquals(1,s2inventory.held(p.sku()));
                var replayPool=Executors.newFixedThreadPool(10);try{var replays=new ArrayList<Future<JsonObject>>();for(int i=0;i<10;i++)replays.add(replayPool.submit(()->request(transactionMvc,post(Stage2Routes.BASE+"/checkout/intents"),actors.get(winner),commands.get(winner),200)));for(var replay:replays)assertEquals(trade,replay.get(30,TimeUnit.SECONDS).get("tradeId").getAsString());}finally{replayPool.shutdownNow();}
                var changed=new LinkedHashMap<>(commands.get(winner));changed.put("confirmationHash","a".repeat(64));request(transactionMvc,post(Stage2Routes.BASE+"/checkout/intents"),actors.get(winner),changed,409);assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_inventory_hold WHERE trade_id=?",trade));
                request(transactionMvc,post(Stage2Routes.BASE+"/trades/"+trade+"/payment/prepare"),actors.get(winner),map("operationId",req()),200);var paid=request(transactionMvc,post(Stage2Routes.BASE+"/trades/"+trade+"/payment/launch"),actors.get(winner),map("operationId",req()),200);assertEquals("PAID",paid.getAsJsonObject("trade").get("status").getAsString());assertEquals(0,available(p.sku()));assertEquals(0,s2inventory.held(p.sku()));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='CAPTURE'",trade));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='RELEASE'",trade));note("TC-S2-S09-008-01","held-last-unit-pays-after-missing-key-race-"+repetition,map("trade",trade,"sku",p.sku()),facts(trade));
            }finally{go.countDown();pool.shutdownNow();}
        }
    }
}
