package cn.lili.common.security.session;

import cn.lili.modules.community.p0.stage2.*;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.common.security.session.Stage2PersistenceTest.*;
import static cn.lili.common.security.session.Stage2RemainingVerificationTest.request;
import static cn.lili.common.security.session.Stage2AcceptanceVerificationTest.*;
import static cn.lili.common.security.session.Stage2OwnerAcceptanceTest.limitedScope;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfSystemProperty(named="s2.integration",matches="owned-run")
@Timeout(value=20,unit=TimeUnit.MINUTES)
class Stage2ScenarioCompletionTest {
    @BeforeAll static void open()throws Exception {simulation.offset=0;Stage2RemainingVerificationTest.open();}
    @BeforeEach void before(){simulation.offset=0;mvc=transactionMvc;}
    @AfterAll static void close()throws Exception {Stage2PersistenceTest.closeS2();}

    static String goodsFor(OwnerFixture owner,List<String> methods,long cents,int quantity)throws Exception {
        mvc=supplyMvc;
        var payload=object(goodsPayload(unitFixture(),upload(owner.login().session(),Stage1Routes.STORE,"GOODS"),quantity));
        payload.addProperty("name","S2验收商品 "+cents);payload.add("methods",array(write(methods)));
        payload.getAsJsonArray("skus").get(0).getAsJsonObject().addProperty("priceCent",cents);
        payload.getAsJsonArray("skus").get(0).getAsJsonObject().addProperty("weightGram",500);
        var made=request(supplyMvc,post(Stage1Routes.STORE+"/goods"),owner.login().session(),map("requestId",req(),"payload",payload),200);
        String goods=made.getAsJsonObject("goods").get("id").getAsString();
        request(supplyMvc,post(Stage1Routes.STORE+"/goods/"+goods+"/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",0),200);
        request(supplyMvc,post(Stage1Routes.MANAGER+"/goods/"+goods+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"contentVersion",0),200);
        request(supplyMvc,post(Stage1Routes.STORE+"/goods/"+goods+"/list"),owner.login().session(),map("requestId",req(),"expectedVersion",2),200);
        writeRecoveryScope();return made.getAsJsonArray("skus").get(0).getAsJsonObject().get("id").getAsString();
    }
    static Map<String,String> allMethods(OwnerFixture owner)throws Exception {
        mvc=supplyMvc;String pickup=createPointFixture(owner,"COMMUNITY_PICKUP"),redeem=createPointFixture(owner,"STORE_REDEMPTION");
        for(String point:List.of(pickup,redeem))request(supplyMvc,post(Stage1Routes.STORE+"/points/"+point+"/enable"),owner.login().session(),map("requestId",req(),"expectedVersion",0,"reason","S2验收启用点位"),200);
        long version=db.count("SELECT version FROM li_p0_fulfillment_config WHERE store_id=?",owner.store());
        request(supplyMvc,put(Stage1Routes.STORE+"/fulfillment"),owner.login().session(),map("requestId",req(),"expectedVersion",version,"reason","S2验收四方式","methods",map("MERCHANT_DELIVERY",map("enabled",true,"feeCent",300,"districtCodes",List.of("990101")),"EXPRESS",map("enabled",true,"feeCent",450,"nationwide",true),"COMMUNITY_PICKUP",map("enabled",true,"feeCent",0),"STORE_REDEMPTION",map("enabled",true,"feeCent",0))),200);
        return Map.of("COMMUNITY_PICKUP",pickup,"STORE_REDEMPTION",redeem);
    }
    static JsonObject pay(SessionTokens resident,String trade)throws Exception {
        request(transactionMvc,post(Stage2Routes.BASE+"/trades/"+trade+"/payment/prepare"),resident,map("operationId",req()),200);
        return request(transactionMvc,post(Stage2Routes.BASE+"/trades/"+trade+"/payment/launch"),resident,map("operationId",req()),200);
    }

    @Test void sameTradeFourStoresFourMethodsAndRealNoIntersectionRejectsWholeQuote()throws Exception {
        mvc=supplyMvc;String config=channelConfigFixture(10000);var owners=new ArrayList<OwnerFixture>();var skus=new ArrayList<String>();var pointMaps=new ArrayList<Map<String,String>>();
        var methods=List.of("MERCHANT_DELIVERY","EXPRESS","COMMUNITY_PICKUP","STORE_REDEMPTION");
        for(int i=0;i<4;i++){var owner=readyStore(config);owners.add(owner);pointMaps.add(allMethods(owner));skus.add(goodsFor(owner,methods,1001L*(i+1),10));}
        var resident=residentFixture();var book=request(transactionMvc,post(Stage2Routes.BASE+"/addresses"),resident.session(),Stage2OwnerAcceptanceTest.address(0,"配送地址甲"),200);String first=Stage2OwnerAcceptanceTest.firstAddress(book);
        book=request(transactionMvc,post(Stage2Routes.BASE+"/addresses"),resident.session(),Stage2OwnerAcceptanceTest.address(1,"物流地址乙"),200);String second=Stage2OwnerAcceptanceTest.firstAddress(book);
        var items=new ArrayList<Object>();var choices=new ArrayList<Object>();
        for(int i=0;i<4;i++){items.add(map("skuId",skus.get(i),"quantity",1));var choice=map("storeId",owners.get(i).store(),"method",methods.get(i),"contact",map("name","S2测试联系人","mobile","13800000000"));if(i<2){choice.put("addressId",i==0?first:second);choice.put("addressVersion",0);}else choice.put("pointId",pointMaps.get(i).get(methods.get(i)));choices.add(choice);}
        var q=request(transactionMvc,post(Stage2Routes.BASE+"/checkout/quotes"),resident.session(),map("items",items,"stores",choices),200);assertEquals(10760,q.get("amountCent").getAsLong());
        var made=request(transactionMvc,post(Stage2Routes.BASE+"/checkout/intents"),resident.session(),map("intentId","intent_"+req(),"quoteId",q.get("quoteId").getAsString(),"confirmationHash",q.get("confirmationHash").getAsString()),200);String trade=made.get("tradeId").getAsString();
        String snapshots=hash(db.rows("SELECT * FROM li_p0_order_snapshot WHERE trade_id=? ORDER BY store_id",trade));pay(resident.session(),trade);assertEquals("PAID",id(db.required("SELECT status FROM li_p0_trade_state WHERE trade_id=?",trade),"status"));assertEquals(4,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='CAPTURE'",trade));assertEquals(snapshots,hash(db.rows("SELECT * FROM li_p0_order_snapshot WHERE trade_id=? ORDER BY store_id",trade)));
        var request=object(db.required("SELECT request_json FROM li_p0_payment WHERE trade_id=?",trade).get("request_json"));assertEquals(4,Stage2PaymentFacts.require(object(db.required("SELECT result_json FROM li_p0_payment WHERE trade_id=?",trade).get("result_json")),request).getAsJsonArray("subOrders").size());
        String onlyDelivery=goodsFor(owners.get(0),List.of("MERCHANT_DELIVERY"),1001,10),onlyExpress=goodsFor(owners.get(0),List.of("EXPRESS"),1001,10);long before=db.count("SELECT COUNT(*) FROM li_p0_trade_state WHERE member_id=?",resident.memberId());
        request(transactionMvc,post(Stage2Routes.BASE+"/checkout/quotes"),resident.session(),map("items",List.of(map("skuId",onlyDelivery,"quantity",1),map("skuId",onlyExpress,"quantity",1),map("skuId",skus.get(1),"quantity",1)),"stores",List.of(choices.get(0),choices.get(1))),409);
        assertEquals(before,db.count("SELECT COUNT(*) FROM li_p0_trade_state WHERE member_id=?",resident.memberId()));assertEquals(10,available(onlyDelivery));assertEquals(10,available(onlyExpress));
        note("TC-S2-E2E-003-01","one-trade-four-methods-distinct-addresses-and-no-intersection",map("trade",trade,"methods",methods,"addressIds",List.of(first,second)),map("amountCent",10760,"snapshotsUnchanged",true,"stocks",skus.stream().map(Stage2AcceptanceVerificationTest::stock).toList()));
    }

    @Test void fortyNineAndFiftyStoresCreateWholeTradesAndFiftyOneRejectsWithoutPartialState()throws Exception {
        mvc=supplyMvc;String config=channelConfigFixture(10000);var owners=new ArrayList<OwnerFixture>();var skus=new ArrayList<String>();
        for(int i=0;i<51;i++){var owner=readyStore(config);owners.add(owner);skus.add(id(db.required("SELECT id FROM li_goods_sku WHERE store_id=?",owner.store()),"id"));}
        var resident=residentFixture();var book=request(transactionMvc,post(Stage2Routes.BASE+"/addresses"),resident.session(),Stage2OwnerAcceptanceTest.address(0,"容量验证地址"),200);String address=Stage2OwnerAcceptanceTest.firstAddress(book);
        for(int count:List.of(49,50,51)){var items=new ArrayList<Object>();var choices=new ArrayList<Object>();for(int i=0;i<count;i++){items.add(map("skuId",skus.get(i),"quantity",1));choices.add(map("storeId",owners.get(i).store(),"method","MERCHANT_DELIVERY","addressId",address,"addressVersion",0,"contact",map("name","S2测试联系人","mobile","13800000000")));}
            if(count==51){long before=db.count("SELECT COUNT(*) FROM li_p0_trade_state WHERE member_id=?",resident.memberId());request(transactionMvc,post(Stage2Routes.BASE+"/checkout/quotes"),resident.session(),map("items",items,"stores",choices),422);assertEquals(before,db.count("SELECT COUNT(*) FROM li_p0_trade_state WHERE member_id=?",resident.memberId()));continue;}
            var q=request(transactionMvc,post(Stage2Routes.BASE+"/checkout/quotes"),resident.session(),map("items",items,"stores",choices),200);assertEquals(1001L*count,q.get("amountCent").getAsLong());
            var made=request(transactionMvc,post(Stage2Routes.BASE+"/checkout/intents"),resident.session(),map("intentId","intent_"+req(),"quoteId",q.get("quoteId").getAsString(),"confirmationHash",q.get("confirmationHash").getAsString()),200);String trade=made.get("tradeId").getAsString();assertEquals(count,db.count("SELECT COUNT(*) FROM li_p0_order_snapshot WHERE trade_id=?",trade));
            if(count==49)request(transactionMvc,post(Stage2Routes.BASE+"/trades/"+trade+"/cancel"),resident.session(),map("operationId",req()),200);else{pay(resident.session(),trade);assertEquals(50,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='CAPTURE'",trade));}
            note("TC-S2-Q-019-01","store-capacity-"+count,map("count",count,"trade",trade),facts(trade));
        }
        observations.add(map("fixture","capacity-supply-candidates","configId",config,"storeIds",owners.stream().map(OwnerFixture::store).toList(),"skuIds",skus,"syntheticOwners",true));
    }

    @Test void releaseFailureNotificationAckLossAndOutboxFailureRecoverWithoutASecondStockDisposition()throws Exception {
        var p=purchase(1);String trade=create(p);prepare(p,trade);scenario(trade,"FAILURE",false);launch(p,trade);limitedScope(p);
        var broken=spy(s2inventory);doThrow(new org.springframework.dao.DataAccessResourceFailureException("S2 controlled release failure")).when(broken).settle(trade,false);ReflectionTestUtils.setField(s2payments,"inventory",broken);
        try{cancel(p,trade);verify(broken).settle(trade,false);assertEquals("CLOSED",id(db.required("SELECT status FROM li_p0_payment WHERE trade_id=?",trade),"status"));assertEquals(9,available(p.sku()));assertEquals(1,s2inventory.held(p.sku()));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='RELEASE'",trade));}finally{ReflectionTestUtils.setField(s2payments,"inventory",s2inventory);}
        simulation.advance(6);worker.tick();assertEquals("CLOSED",id(db.required("SELECT status FROM li_p0_trade_state WHERE trade_id=?",trade),"status"));assertEquals(10,available(p.sku()));assertEquals(0,s2inventory.held(p.sku()));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='RELEASE'",trade));writeRecoveryScope();
        simulation.offset=0;var paid=purchase(1);String paidTrade=create(paid);prepare(paid,paidTrade);scenario(paidTrade,"DELAYED_SUCCESS",true);limitedScope(paid);
        var result=object(db.required("SELECT request_json FROM li_p0_payment WHERE trade_id=?",paidTrade).get("request_json"));var channelResult=paymentMock.query(result);String event=channelResult.get("eventId").getAsString();var delayedAck=spy(paymentMock);doThrow(new org.springframework.dao.DataAccessResourceFailureException("S2 controlled ack loss")).when(delayedAck).delivered(event);
        var txTemplate=(org.springframework.transaction.support.TransactionTemplate)ReflectionTestUtils.getField(s2tx,"tx");var recovery=new Stage2RecoveryWorker(s2tx,db,s2Settings,s2trades,s2payments,delayedAck,redis,Objects.requireNonNull(txTemplate.getTransactionManager()),simulation);
        recovery.tick();assertEquals("PAID",id(db.required("SELECT status FROM li_p0_trade_state WHERE trade_id=?",paidTrade),"status"));var mockDb=(org.springframework.jdbc.core.JdbcTemplate)ReflectionTestUtils.getField(paymentMock,"jdbc");assertEquals("PENDING",mockDb.queryForObject("SELECT status FROM li_p0_mock_delivery WHERE event_id=?",String.class,event));
        doCallRealMethod().when(delayedAck).delivered(event);recovery.tick();assertEquals("DONE",mockDb.queryForObject("SELECT status FROM li_p0_mock_delivery WHERE event_id=?",String.class,event));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='CAPTURE'",paidTrade));
        // A connection failure keeps only this enrolled outbox pending; it never edits Redis leases.
        request(transactionMvc,put(Stage1Routes.STORE+"/skus/"+paid.sku()+"/stock"),paid.owner().login().session(),map("requestId",req(),"expectedVersion",stockVersion(paid.sku()),"targetQuantity",7,"reason","S2 outbox failure"),200);
        long pending=db.count("SELECT COUNT(*) FROM li_p0_outbox WHERE store_id=? AND status='PENDING'",paid.owner().store());assertTrue(pending>0);redisFactory.stop();try{recovery.tick();assertEquals(pending,db.count("SELECT COUNT(*) FROM li_p0_outbox WHERE store_id=? AND status='PENDING'",paid.owner().store()));}finally{redisFactory.start();}
        recovery.tick();assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_outbox WHERE store_id=? AND status='PENDING'",paid.owner().store()));assertEquals("7",redis.opsForValue().get(cn.lili.modules.goods.service.GoodsSkuService.getStockCacheKey(paid.sku())));
        note("TC-S2-Q-013-01","release-ack-and-outbox-faults",map("closed",trade,"paid",paidTrade,"event",event),map("closed",facts(trade),"paid",facts(paidTrade),"cacheA",7));writeRecoveryScope();
    }

    static JsonObject wire(String suffix,long amount){return object(map("mode","MOCK","combineNo","S2C"+suffix,"tradeId",suffix,"payerMemberId","9007199254740993","platformMerchantId","MOCK_PROVIDER_BOUNDARY","applicationId","MOCK_APP_BOUNDARY","currency","CNY","amountCent",amount,"expiresAt",simulation.instant().plusSeconds(900),"subOrders",List.of(map("orderId","9007199254740993","storeId","9007199254740994","subNo","S2S9007199254740993","subMchid","MOCK_SUB_BOUNDARY","amountCent",amount))));}
    @Test void independentMockRejectsNonIntegerAmountsBeforeCreatingAnyLedgerIdentity() {
        var mockDb=(org.springframework.jdbc.core.JdbcTemplate)ReflectionTestUtils.getField(paymentMock,"jdbc");
        for(Object amount:List.of(new BigDecimal("1.9"),"2",new BigDecimal("9007199254740992"))){String suffix=db.id();var bad=wire(suffix,1);bad.add("amountCent",new Gson().toJsonTree(amount));bad.getAsJsonArray("subOrders").get(0).getAsJsonObject().add("amountCent",new Gson().toJsonTree(amount));
            assertThrows(Stage1Exception.class,()->paymentMock.prepare(bad));assertEquals(0L,mockDb.queryForObject("SELECT COUNT(*) FROM li_p0_mock_payment WHERE combine_no=?",Long.class,"S2C"+suffix));
        }
        for(long amount:List.of(1L,9_007_199_254_740_991L)){var request=wire(db.id(),amount);paymentMock.prepare(request);var first=paymentMock.launch(request);assertEquals(amount,Stage2PaymentFacts.require(first,request).get("amountCent").getAsLong());assertEquals("9007199254740993",first.getAsJsonObject("paid").getAsJsonArray("subOrders").get(0).getAsJsonObject().get("orderId").getAsString());assertEquals(first.get("paid"),paymentMock.query(request).get("paid"));note("TC-S2-Q-016-01","independent-Mock-wire-amount-"+amount,request,map("paid",first.get("paid"),"businessTradeCreated",false));}
    }

    record EvidenceVariant(String name,Consumer<JsonObject> change,boolean authentic){}
    static JsonObject paidChild(JsonObject r){return r.getAsJsonObject("paid").getAsJsonArray("subOrders").get(0).getAsJsonObject();}
    @Test void eachPaidMismatchUsesItsOwnTwoStoreTradeAtBothNotificationAndQueryEntrances()throws Exception {
        mvc=supplyMvc;String config=channelConfigFixture(10000);var a=readyStore(config);var b=readyStore(config);allMethods(a);
        String sa=goodsFor(a,List.of("MERCHANT_DELIVERY"),1001,10),sb=goodsFor(b,List.of("MERCHANT_DELIVERY"),2500,10);
        var resident=residentFixture();var book=request(transactionMvc,post(Stage2Routes.BASE+"/addresses"),resident.session(),Stage2OwnerAcceptanceTest.address(0,"付款证据矩阵"),200);String address=Stage2OwnerAcceptanceTest.firstAddress(book);
        var variants=new ArrayList<EvidenceVariant>();
        variants.add(new EvidenceVariant("untrusted-signature",r->r.addProperty("signature","0".repeat(64)),false));
        variants.add(new EvidenceVariant("cross-mode",r->r.addProperty("mode","REAL"),true));
        for(String field:List.of("combineNo","payerMemberId","platformMerchantId","applicationId","currency"))variants.add(new EvidenceVariant("request-"+field,r->r.getAsJsonObject("request").addProperty(field,"mismatch"),true));
        variants.add(new EvidenceVariant("request-total",r->r.getAsJsonObject("request").addProperty("amountCent",3800),true));
        variants.add(new EvidenceVariant("paid-total",r->r.getAsJsonObject("paid").addProperty("amountCent",3800),true));
        variants.add(new EvidenceVariant("paid-currency",r->r.getAsJsonObject("paid").addProperty("currency","USD"),true));
        variants.add(new EvidenceVariant("missing-child",r->r.getAsJsonObject("paid").getAsJsonArray("subOrders").remove(1),true));
        variants.add(new EvidenceVariant("duplicate-child",r->{var subs=r.getAsJsonObject("paid").getAsJsonArray("subOrders");subs.set(1,subs.get(0).deepCopy());},true));
        for(String field:List.of("orderId","storeId","subNo","subMchid","currency"))variants.add(new EvidenceVariant("child-"+field,r->paidChild(r).addProperty(field,"mismatch"),true));
        variants.add(new EvidenceVariant("child-amount",r->paidChild(r).addProperty("amountCent",1300),true));
        variants.add(new EvidenceVariant("missing-transaction",r->paidChild(r).remove("transactionId"),true));
        variants.add(new EvidenceVariant("missing-child-paidAt",r->paidChild(r).remove("paidAt"),true));
        variants.add(new EvidenceVariant("missing-paidAt",r->r.getAsJsonObject("paid").remove("paidAt"),true));
        variants.add(new EvidenceVariant("child-partial",r->paidChild(r).addProperty("state","UNPAID"),true));
        variants.add(new EvidenceVariant("partial-envelope",r->r.addProperty("state","PARTIAL"),true));
        for(String entrance:List.of("notification","query"))for(var variant:variants){
            simulation.offset=0;
            for(var pair:List.of(Map.entry(a,sa),Map.entry(b,sb)))request(transactionMvc,put(Stage1Routes.STORE+"/skus/"+pair.getValue()+"/stock"),pair.getKey().login().session(),map("requestId",req(),"expectedVersion",stockVersion(pair.getValue()),"targetQuantity",10,"reason","S2独立证据变体起始A10"),200);
            var q=request(transactionMvc,post(Stage2Routes.BASE+"/checkout/quotes"),resident.session(),map("items",List.of(map("skuId",sa,"quantity",1),map("skuId",sb,"quantity",1)),"stores",List.of(map("storeId",a.store(),"method","MERCHANT_DELIVERY","addressId",address,"addressVersion",0,"contact",map("name","S2测试居民","mobile","13800000000")),map("storeId",b.store(),"method","MERCHANT_DELIVERY","addressId",address,"addressVersion",0,"contact",map("name","S2测试居民","mobile","13800000000")))),200);
            assertEquals(3801,q.get("amountCent").getAsLong());var made=request(transactionMvc,post(Stage2Routes.BASE+"/checkout/intents"),resident.session(),map("intentId","intent_"+req(),"quoteId",q.get("quoteId").getAsString(),"confirmationHash",q.get("confirmationHash").getAsString()),200);String trade=made.get("tradeId").getAsString();
            request(transactionMvc,post(Stage2Routes.BASE+"/trades/"+trade+"/payment/prepare"),resident.session(),map("operationId",req()),200);var command=object(db.required("SELECT request_json FROM li_p0_payment WHERE trade_id=?",trade).get("request_json"));var good=paymentMock.launch(command);var bad=good.deepCopy();bad.addProperty("eventId","evidence:"+req());variant.change.accept(bad);if(variant.authentic)bad.addProperty("signature",paymentMock.sign(bad));
            try{if(entrance.equals("notification")){try{s2payments.notification(bad);}catch(Stage1Exception refused){assertTrue(Set.of(403,404).contains(refused.status()));}}else{var controlled=spy(paymentMock);doReturn(bad).when(controlled).query(any(JsonObject.class));ReflectionTestUtils.setField(s2payments,"channel",controlled);simulation.advance(6);s2payments.recover(trade);verify(controlled).query(command);}}
            finally{ReflectionTestUtils.setField(s2payments,"channel",paymentMock);}
            assertNotEquals("PAID",id(db.required("SELECT status FROM li_p0_trade_state WHERE trade_id=?",trade),"status"));for(String sku:List.of(sa,sb)){assertEquals(9,available(sku));assertEquals(1,s2inventory.held(sku));}assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type IN ('CAPTURE','RELEASE')",trade));
            s2payments.notification(good);assertEquals("PAID",id(db.required("SELECT status FROM li_p0_trade_state WHERE trade_id=?",trade),"status"));assertEquals(2,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='CAPTURE'",trade));for(String sku:List.of(sa,sb)){assertEquals(9,available(sku));assertEquals(0,s2inventory.held(sku));}
            assertEquals(Stage2PaymentFacts.require(good,command),Stage2PaymentFacts.require(paymentMock.query(command),command));note("TC-S2-S11-009-01",entrance+"/"+variant.name,map("trade",trade,"combine",command.get("combineNo")),map("initialA",10,"heldA",9,"heldH",1,"final",facts(trade),"completeOriginalPaidFacts",good.get("paid")));
        }
        simulation.offset=0;
    }
}
