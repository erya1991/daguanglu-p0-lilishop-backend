package cn.lili.common.security.session;

import cn.lili.modules.community.p0.stage2.*;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.time.*;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Actual S2 services/transactions with explicit returned fixture IDs, never existing trade adoption. */
@EnabledIfSystemProperty(named="s2.integration",matches="owned-run")
@Timeout(value=15,unit=TimeUnit.MINUTES)
class Stage2OwnerAcceptanceTest {
    @BeforeAll static void open()throws Exception{simulation.offset=0;Stage2RemainingVerificationTest.open();}
    @BeforeEach void before(){simulation.offset=0;mvc=transactionMvc;}
    @AfterAll static void close()throws Exception{Stage2PersistenceTest.closeS2();}
    static JsonObject getAddresses(SessionTokens token)throws Exception{return request(transactionMvc,get(Stage2Routes.BASE+"/addresses"),token,null,200);}
    static String firstAddress(JsonObject book){return book.getAsJsonArray("records").get(0).getAsJsonObject().get("id").getAsString();}
    static Map<String,Object> address(long version,String name){var b=new LinkedHashMap<String,Object>(Stage2RemainingVerificationTest.addressBody(version));b.put("name",name);return b;}
    static String stateHash(String table,String where,Object...args){return hash(db.rows("SELECT * FROM "+table+" WHERE "+where+" ORDER BY 1",args));}
    static void limitedScope(Purchase p)throws Exception{
        Path file=run.resolve("tmp/owner-scope-"+testId+".json");byte[] bytes=write(map("runId",settings.getRunId(),"admittedAt",recoveryAdmittedAt,"evidence","Q-013 explicit purchased fixture","members",List.of(p.resident().memberId()),"stores",List.of(p.owner().store()),"skus",List.of(p.sku()))).getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(file,bytes);s2Settings.setRecoveryScopeFile(file.toString());s2Settings.setRecoveryScopeSha256(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
    }

    @Test void scopedTickRecoversItsPaidTradeAndLeavesForeignTasksDeliveriesAndAttentionUntouched()throws Exception{
        var foreign=purchase(1);String other=create(foreign);prepare(foreign,other);scenario(other,"UNKNOWN",false);launch(foreign,other);scenario(other,"DELAYED_SUCCESS",true);
        var own=purchase(1);String trade=create(own);prepare(own,trade);scenario(trade,"UNKNOWN",false);launch(own,trade);scenario(trade,"DELAYED_SUCCESS",true);
        String oldTrade=stateHash("li_p0_trade_state","trade_id=?",other),oldTask=stateHash("li_p0_s2_task","target_key=? OR target_key=?",other,foreign.resident().memberId()+":"+foreign.create().get("intentId"));
        long oldDeliveries=((org.springframework.jdbc.core.JdbcTemplate)org.springframework.test.util.ReflectionTestUtils.getField(paymentMock,"jdbc")).queryForObject("SELECT COUNT(*) FROM li_p0_mock_delivery WHERE combine_no=? AND status='PENDING'",Long.class,"S2C"+other);
        limitedScope(own);simulation.advance(121);worker.tick();
        assertEquals("PAID",id(db.required("SELECT status FROM li_p0_trade_state WHERE trade_id=?",trade),"status"));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='CAPTURE'",trade));
        assertEquals(oldTrade,stateHash("li_p0_trade_state","trade_id=?",other));assertEquals(oldTask,stateHash("li_p0_s2_task","target_key=? OR target_key=?",other,foreign.resident().memberId()+":"+foreign.create().get("intentId")));
        assertEquals(oldDeliveries,((org.springframework.jdbc.core.JdbcTemplate)org.springframework.test.util.ReflectionTestUtils.getField(paymentMock,"jdbc")).queryForObject("SELECT COUNT(*) FROM li_p0_mock_delivery WHERE combine_no=? AND status='PENDING'",Long.class,"S2C"+other));assertEquals(1,s2inventory.held(foreign.sku()));
        note("TC-S2-Q-013-01","scope-before-limit-and-complete-tick",map("own",trade,"foreign",other),map("own",facts(trade),"foreignUnchanged",true,"foreignPendingDeliveries",oldDeliveries));writeRecoveryScope();
    }

    @Test void differentAddressFirstCreationReplaysRetryAndExplicitNoDefaultRemainConsistent()throws Exception{
        var r=residentFixture();var a=address(0,"甲住户");var b=address(0,"乙住户");var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{var x=executor.submit(()->{await(start);return transactionMvc.perform(post(Stage2Routes.BASE+"/addresses").header("accessToken",r.session().accessToken()).contentType("application/json").content(write(a))).andReturn();});var y=executor.submit(()->{await(start);return transactionMvc.perform(post(Stage2Routes.BASE+"/addresses").header("accessToken",r.session().accessToken()).contentType("application/json").content(write(b))).andReturn();});start.countDown();
            var first=x.get();var second=y.get();assertEquals(List.of(200,409),java.util.stream.Stream.of(first,second).map(z->z.getResponse().getStatus()).sorted().toList());
            var winner=first.getResponse().getStatus()==200?a:b;var loser=first.getResponse().getStatus()==200?b:a;var saved=checked(first.getResponse().getStatus()==200?first:second,200);
            assertEquals(saved,request(transactionMvc,post(Stage2Routes.BASE+"/addresses"),r.session(),winner,200));loser.put("addressBookVersion",1);request(transactionMvc,post(Stage2Routes.BASE+"/addresses"),r.session(),loser,200);
        }finally{executor.shutdownNow();}
        var book=getAddresses(r.session());assertEquals(2,book.getAsJsonArray("records").size());request(transactionMvc,post(Stage2Routes.BASE+"/addresses/default/clear"),r.session(),map("operationId",req(),"addressBookVersion",book.get("version").getAsLong()),200);
        book=getAddresses(r.session());assertTrue(book.get("defaultAddressId").isJsonNull());assertEquals(0,db.count("SELECT COUNT(*) FROM li_member_address WHERE member_id=? AND is_default=1",r.memberId()));
        String id=firstAddress(book);var remove=map("operationId",req(),"expectedVersion",0,"addressBookVersion",book.get("version").getAsLong());var removed=request(transactionMvc,delete(Stage2Routes.BASE+"/addresses/"+id),r.session(),remove,200);assertEquals(removed,request(transactionMvc,delete(Stage2Routes.BASE+"/addresses/"+id),r.session(),remove,200));assertTrue(removed.get("defaultAddressId").isJsonNull());
        note("TC-S2-R08-005-01","different-input-first-create-retry-replay-no-default",map("member",r.memberId()),getAddresses(r.session()));
    }

    @Test void twoDefaultChangesAndDeleteVersusEditRespectOneBookVersion()throws Exception{
        var r=residentFixture();var book=request(transactionMvc,post(Stage2Routes.BASE+"/addresses"),r.session(),address(0,"甲住户"),200);String a=firstAddress(book);book=request(transactionMvc,post(Stage2Routes.BASE+"/addresses"),r.session(),address(1,"乙住户"),200);String b=firstAddress(book);long version=book.get("version").getAsLong();
        var executor=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);try{var results=new ArrayList<Future<Integer>>();for(String id:List.of(a,b))results.add(executor.submit(()->{await(start);return transactionMvc.perform(post(Stage2Routes.BASE+"/addresses/"+id+"/default").header("accessToken",r.session().accessToken()).contentType("application/json").content(write(map("operationId",req(),"expectedVersion",0,"addressBookVersion",version)))).andReturn().getResponse().getStatus();}));start.countDown();assertEquals(List.of(200,409),results.stream().map(f->{try{return f.get();}catch(Exception e){throw new RuntimeException(e);}}).sorted().toList());}finally{executor.shutdownNow();}
        book=getAddresses(r.session());assertEquals(1,db.count("SELECT COUNT(*) FROM li_member_address WHERE member_id=? AND delete_flag=0 AND is_default=1",r.memberId()));
        long v=book.get("version").getAsLong();var edited=address(v,"修改后的甲住户");edited.put("expectedVersion",0);var editResult=request(transactionMvc,put(Stage2Routes.BASE+"/addresses/"+a),r.session(),edited,200);request(transactionMvc,delete(Stage2Routes.BASE+"/addresses/"+a),r.session(),map("operationId",req(),"expectedVersion",0,"addressBookVersion",v),409);
        var current=getAddresses(r.session());var row=current.getAsJsonArray("records").asList().stream().map(JsonElement::getAsJsonObject).filter(x->x.get("id").getAsString().equals(a)).findFirst().orElseThrow();request(transactionMvc,delete(Stage2Routes.BASE+"/addresses/"+a),r.session(),map("operationId",req(),"expectedVersion",row.get("version").getAsLong(),"addressBookVersion",current.get("version").getAsLong()),200);
        note("TC-S2-R08-006-01","two-default-cas-and-edit-delete-old-version",map("member",r.memberId()),getAddresses(r.session()));
    }

    @Test void everyNativeTradeWriteFaultRollsBackStockSnapshotsConsumptionAndTasks()throws Exception{
        for(String point:List.of("insert into li_trade","insert into li_order (","insert into li_p0_order_snapshot","insert into li_p0_inventory_hold","insert into li_order_item","insert into li_p0_cart_consumption","insert into li_p0_s2_task")){
            var p=purchase(1);var added=request(transactionMvc,post(Stage2Routes.BASE+"/cart/items"),p.resident().session(),map("operationId",req(),"skuId",p.sku(),"quantity",1),200);var line=added.getAsJsonArray("items").get(0).getAsJsonObject();
            // Quote through the public cart contract so consumption is part of the same transaction.
            var q=request(transactionMvc,post(Stage2Routes.BASE+"/checkout/quotes"),p.resident().session(),map("items",List.of(map("cartId",line.get("id").getAsString(),"expectedVersion",line.get("version").getAsLong(),"skuId",p.sku(),"quantity",1)),"stores",List.of(map("storeId",p.owner().store(),"method","MERCHANT_DELIVERY","addressId",p.address(),"addressVersion",0,"contact",map("name","S2_TEST居民","mobile","13800000000")))),200);
            String intent="intent_"+req();boolean acceptanceFault=point.equals("insert into li_p0_s2_task");var command=map("intentId",intent,"quoteId",q.get("quoteId").getAsString(),"confirmationHash",q.get("confirmationHash").getAsString());var hit=new AtomicBoolean();try{sqlObserver.set(sql->{if(sql.toLowerCase(Locale.ROOT).replace("`","").replaceAll("\\s+"," ").startsWith(point)&&hit.compareAndSet(false,true))throw new org.springframework.dao.DataAccessResourceFailureException("S2 owned write interruption");});
                request(transactionMvc,post(Stage2Routes.BASE+"/checkout/intents"),p.resident().session(),command,acceptanceFault?503:200);
            }finally{sqlObserver.remove();}
            assertTrue(hit.get(),point);assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_trade_state WHERE member_id=?",p.resident().memberId()));assertEquals(10,available(p.sku()));assertEquals(0,s2inventory.held(p.sku()));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_cart_consumption WHERE member_id=?",p.resident().memberId()));assertEquals(1,db.count("SELECT quantity FROM li_p0_cart_item WHERE id=?",line.get("id").getAsString()));
            var recovered=request(transactionMvc,get(Stage2Routes.BASE+"/checkout/intents/"+intent),p.resident().session(),null,acceptanceFault?404:200);if(!acceptanceFault)assertEquals("VERIFYING",recovered.get("status").getAsString());
            var retried=request(transactionMvc,post(Stage2Routes.BASE+"/checkout/intents"),p.resident().session(),command,200);assertEquals("SUCCEEDED",retried.get("status").getAsString());assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_trade_state WHERE member_id=?",p.resident().memberId()));assertEquals(9,available(p.sku()));assertEquals(1,s2inventory.held(p.sku()));note("TC-S2-S09-021-01",point,map("intent",intent,"sku",p.sku()),map("rollbackA",10,"rollbackH",0,"originalIntentRecovery",retried));
        }
    }

    @Test void multiSkuManualTargetsRollbackAsOneOperationAndPreserveBothHolds()throws Exception{
        var p=purchase(1);mvc=supplyMvc;var payload=object(goodsPayload(unitFixture(),upload(p.owner().login().session(),Stage1Routes.STORE,"GOODS"),13));var a=payload.getAsJsonArray("skus").get(0).getAsJsonObject();a.addProperty("code","S2_BATCH_A_"+req());a.add("specifications",array(write(List.of(map("name","规格","value","第一规格")))));var b=a.deepCopy();b.addProperty("code","S2_BATCH_B_"+req());b.addProperty("initialQuantity",7);b.getAsJsonArray("specifications").get(0).getAsJsonObject().addProperty("value","另一规格");payload.getAsJsonArray("skus").add(b);
        var made=request(supplyMvc,post(Stage1Routes.STORE+"/goods"),p.owner().login().session(),map("requestId",req(),"payload",payload),200);String goods=made.getAsJsonObject("goods").get("id").getAsString();String sa=made.getAsJsonArray("skus").get(0).getAsJsonObject().get("id").getAsString(),sb=made.getAsJsonArray("skus").get(1).getAsJsonObject().get("id").getAsString();
        request(supplyMvc,post(Stage1Routes.STORE+"/goods/"+goods+"/submit"),p.owner().login().session(),map("requestId",req(),"expectedVersion",0),200);request(supplyMvc,post(Stage1Routes.MANAGER+"/goods/"+goods+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"contentVersion",0),200);request(supplyMvc,post(Stage1Routes.STORE+"/goods/"+goods+"/list"),p.owner().login().session(),map("requestId",req(),"expectedVersion",2),200);writeRecoveryScope();
        var q=request(transactionMvc,post(Stage2Routes.BASE+"/checkout/quotes"),p.resident().session(),map("items",List.of(map("skuId",sa,"quantity",3),map("skuId",sb,"quantity",2)),"stores",List.of(map("storeId",p.owner().store(),"method","MERCHANT_DELIVERY","addressId",p.address(),"addressVersion",0,"contact",map("name","S2_TEST居民","mobile","13800000000")))),200);
        var created=request(transactionMvc,post(Stage2Routes.BASE+"/checkout/intents"),p.resident().session(),map("intentId","intent_"+req(),"quoteId",q.get("quoteId").getAsString(),"confirmationHash",q.get("confirmationHash").getAsString()),200);String trade=created.get("tradeId").getAsString();
        request(transactionMvc,put(Stage1Routes.STORE+"/skus/"+sb+"/stock"),p.owner().login().session(),map("requestId",req(),"expectedVersion",stockVersion(sb),"targetQuantity",5,"reason","S2_TEST建立不同实际版本"),200);
        assertEquals(10,available(sa));assertEquals(3,s2inventory.held(sa));assertEquals(5,available(sb));assertEquals(2,s2inventory.held(sb));long va=stockVersion(sa),vb=stockVersion(sb);String before=hash(List.of(stock(sa),stock(sb),db.rows("SELECT * FROM li_p0_inventory_adjustment WHERE sku_id IN (?,?) ORDER BY id",sa,sb),db.rows("SELECT * FROM li_p0_inventory_event WHERE sku_id IN (?,?) ORDER BY id",sa,sb)));String route=Stage1Routes.STORE+"/goods/"+goods+"/stock";
        for(boolean old:List.of(true,false)){var invalid=map("requestId",req(),"reason","S2_TEST批量失败回滚","items",List.of(map("skuId",sa,"expectedVersion",va,"targetQuantity",4),map("skuId",sb,"expectedVersion",old?vb-1:vb,"targetQuantity",old?1:9999998)));request(transactionMvc,put(route),p.owner().login().session(),invalid,409);assertEquals(before,hash(List.of(stock(sa),stock(sb),db.rows("SELECT * FROM li_p0_inventory_adjustment WHERE sku_id IN (?,?) ORDER BY id",sa,sb),db.rows("SELECT * FROM li_p0_inventory_event WHERE sku_id IN (?,?) ORDER BY id",sa,sb))));}
        var valid=map("requestId",req(),"reason","S2_TEST批量合法目标","items",List.of(map("skuId",sb,"expectedVersion",vb,"targetQuantity",1),map("skuId",sa,"expectedVersion",va,"targetQuantity",4)));var result=request(transactionMvc,put(route),p.owner().login().session(),valid,200);assertEquals(result,request(transactionMvc,put(route),p.owner().login().session(),valid,200));assertEquals(4,available(sa));assertEquals(3,s2inventory.held(sa));assertEquals(1,available(sb));assertEquals(2,s2inventory.held(sb));assertEquals(va+1,stockVersion(sa));assertEquals(vb+1,stockVersion(sb));
        note("TC-S2-S09-009-05","second-old-version-or-upper-bound-atomic-then-replay",map("trade",trade,"versions",List.of(va,vb),"initial",List.of(map("A",10,"H",3),map("A",5,"H",2))),map("result",result,"stock",List.of(stock(sa),stock(sb))));
    }
}
