package cn.lili.common.security.session;

import cn.lili.modules.community.p0.stage2.*;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.*;
import java.util.concurrent.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.common.security.session.Stage2PersistenceTest.*;
import static cn.lili.common.security.session.Stage2RemainingVerificationTest.request;
import static cn.lili.common.security.session.Stage2AcceptanceVerificationTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfSystemProperty(named="s2.integration",matches="owned-run")
@Timeout(value=15,unit=TimeUnit.MINUTES)
class Stage2DiscoveryVerificationTest {
    @BeforeAll static void open()throws Exception{simulation.offset=0;Stage2RemainingVerificationTest.open();}
    @BeforeEach void before(){simulation.offset=0;mvc=supplyMvc;}
    @AfterAll static void close()throws Exception{Stage2PersistenceTest.closeS2();}
    static String goods(String sku){return id(db.required("SELECT goods_id FROM li_p0_sku_control WHERE sku_id=?",sku),"goods_id");}
    static JsonObject product(OwnerFixture owner,String term,long cents,boolean cheapDisabled)throws Exception {
        var payload=object(goodsPayload(unitFixture(),upload(owner.login().session(),Stage1Routes.STORE,"GOODS"),120));payload.addProperty("name",term);payload.addProperty("note","PRIVATE_INTERNAL_"+testId);
        var enabled=payload.getAsJsonArray("skus").get(0).getAsJsonObject();enabled.addProperty("priceCent",cents);enabled.add("specifications",array(write(List.of(map("name","型号","value","有效规格")))));
        if(cheapDisabled){var cheap=enabled.deepCopy();cheap.addProperty("code","S2_"+db.id());cheap.addProperty("priceCent",100);cheap.addProperty("enabled",false);cheap.add("specifications",array(write(List.of(map("name","型号","value","停用规格")))));payload.getAsJsonArray("skus").add(cheap);}
        var made=request(supplyMvc,post(Stage1Routes.STORE+"/goods"),owner.login().session(),map("requestId",req(),"payload",payload),200);String id=made.getAsJsonObject("goods").get("id").getAsString();request(supplyMvc,post(Stage1Routes.STORE+"/goods/"+id+"/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",0),200);request(supplyMvc,post(Stage1Routes.MANAGER+"/goods/"+id+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"contentVersion",0),200);request(supplyMvc,post(Stage1Routes.STORE+"/goods/"+id+"/list"),owner.login().session(),map("requestId",req(),"expectedVersion",2),200);writeRecoveryScope();return made;
    }
    static List<String> ids(JsonObject page){return page.getAsJsonArray("records").asList().stream().map(JsonElement::getAsJsonObject).map(x->x.get("id").getAsString()).toList();}
    static void privateFieldsAbsent(Object value){String json=write(value);for(String field:List.of("cost","costCent","commissionCent","merchantCent","rateBps","subMchid","sub_mchid","appSecret","request_json"))assertFalse(json.contains("\""+field+"\""),field);}

    @Test void catalogHasStableSortBoundsPrivateSearchAndNoWritesOnTwentyRefreshes()throws Exception {
        String config=channelConfigFixture(10000);var owner=readyStore(config);var resident=residentFixture();String term="发现"+req().substring(0,10);
        var a=product(owner,term+"苹果甲",1001,true);var b=product(owner,term+"苹果乙",1001,false);var d=product(owner,term+"苹果丙",1500,false);String ga=a.getAsJsonObject("goods").get("id").getAsString(),gb=b.getAsJsonObject("goods").get("id").getAsString(),gd=d.getAsJsonObject("goods").get("id").getAsString();
        var ascending=request(transactionMvc,get(Stage2Routes.BASE+"/goods").param("keyword",term).param("categoryId",category).param("sort","PRICE_ASC"),resident.session(),null,200);assertEquals(List.of(gb,ga,gd),ids(ascending));assertEquals(20,ascending.get("pageSize").getAsInt());privateFieldsAbsent(ascending);
        var descending=request(transactionMvc,get(Stage2Routes.BASE+"/goods").param("keyword",term).param("sort","PRICE_DESC"),resident.session(),null,200);assertEquals(List.of(gd,gb,ga),ids(descending));
        assertEquals(ids(ascending),ids(request(transactionMvc,get(Stage2Routes.BASE+"/goods").param("keyword"," "+term+" ").param("sort","PRICE_ASC"),resident.session(),null,200)));
        assertTrue(ids(request(transactionMvc,get(Stage2Routes.BASE+"/goods").param("keyword","PRIVATE_INTERNAL_"+testId),resident.session(),null,200)).isEmpty());request(transactionMvc,get(Stage2Routes.BASE+"/goods").param("keyword","字".repeat(50)),resident.session(),null,200);request(transactionMvc,get(Stage2Routes.BASE+"/goods").param("keyword","字".repeat(51)),resident.session(),null,422);
        for(String size:List.of("0","-1","101","1.5","99999999999999999999","twenty")){var response=transactionMvc.perform(get(Stage2Routes.BASE+"/goods").header("accessToken",resident.session().accessToken()).param("pageSize",size)).andReturn();assertTrue(response.getResponse().getStatus()>=400&&response.getResponse().getStatus()<500,size);assertFalse(response.getResponse().getContentAsString().contains("java.lang"));}
        for(String page:List.of("0","-1","1.5","99999999999999999999")){var response=transactionMvc.perform(get(Stage2Routes.BASE+"/goods").header("accessToken",resident.session().accessToken()).param("page",page)).andReturn();assertTrue(response.getResponse().getStatus()>=400&&response.getResponse().getStatus()<500,page);}
        assertEquals(3,request(transactionMvc,get(Stage2Routes.BASE+"/goods").param("keyword",term).param("pageSize","100"),resident.session(),null,200).getAsJsonArray("records").size());
        long carts=db.count("SELECT COUNT(*) FROM li_p0_cart_item WHERE member_id=?",resident.memberId()),trades=db.count("SELECT COUNT(*) FROM li_p0_trade_state WHERE member_id=?",resident.memberId()),holds=db.count("SELECT COUNT(*) FROM li_p0_inventory_hold WHERE sku_id IN (SELECT id FROM li_goods_sku WHERE store_id=?)",owner.store());
        forbidSqlWrites.set(true);try{for(int i=0;i<20;i++){var page=request(transactionMvc,get(Stage2Routes.BASE+"/goods").param("keyword",term),resident.session(),null,200);assertEquals(3,page.getAsJsonArray("records").size());if(i<10)privateFieldsAbsent(request(transactionMvc,get(Stage2Routes.BASE+"/goods/"+ga),resident.session(),null,200));}}finally{forbidSqlWrites.remove();}
        assertEquals(carts,db.count("SELECT COUNT(*) FROM li_p0_cart_item WHERE member_id=?",resident.memberId()));assertEquals(trades,db.count("SELECT COUNT(*) FROM li_p0_trade_state WHERE member_id=?",resident.memberId()));assertEquals(holds,db.count("SELECT COUNT(*) FROM li_p0_inventory_hold WHERE sku_id IN (SELECT id FROM li_goods_sku WHERE store_id=?)",owner.store()));
        for(String path:List.of("/goods","/cart","/addresses")){var anon=transactionMvc.perform(get(Stage2Routes.BASE+path)).andReturn();assertTrue(Set.of(401,403).contains(anon.getResponse().getStatus()));request(transactionMvc,get(Stage2Routes.BASE+path),owner.login().session(),null,403);}
        note("TC-S2-R05-004-01","stable-valid-SKU-sort-and-private-read-boundaries",map("goods",List.of(ga,gb,gd),"keyword",term),map("ascending",ids(ascending),"descending",ids(descending),"refreshes",20,"detailReads",10,"businessWrites",0));
    }

    @Test void eachActualUnavailableSourceDisappearsWithoutHidingAnUnrelatedReadyStore()throws Exception {
        String config=channelConfigFixture(10000);var normal=readyStore(config);var resident=residentFixture();String term="过滤"+req().substring(0,10);var healthy=product(normal,term+"正常",1001,false);String normalGoods=healthy.getAsJsonObject("goods").get("id").getAsString();
        for(String source:List.of("unlist","store-close","qualification","empty-stock","no-effective-method")){
            var owner=readyStore(config);var made=product(owner,term+source,1001,false);String sku=made.getAsJsonArray("skus").get(0).getAsJsonObject().get("id").getAsString(),gid=made.getAsJsonObject("goods").get("id").getAsString();var p=new Purchase(owner,resident,sku,null,null,null);
            assertTrue(ids(request(transactionMvc,get(Stage2Routes.BASE+"/goods").param("keyword",term),resident.session(),null,200)).contains(gid));
            if(source.equals("empty-stock"))request(transactionMvc,put(Stage1Routes.STORE+"/skus/"+sku+"/stock"),owner.login().session(),map("requestId",req(),"expectedVersion",stockVersion(sku),"targetQuantity",0,"reason","S2验证无货"),200);
            else if(source.equals("no-effective-method")){var disabled=object(deliveryMethods());for(var entry:disabled.entrySet())entry.getValue().getAsJsonObject().addProperty("enabled",false);request(supplyMvc,put(Stage1Routes.STORE+"/fulfillment"),owner.login().session(),map("requestId",req(),"expectedVersion",db.count("SELECT version FROM li_p0_fulfillment_config WHERE store_id=?",owner.store()),"methods",disabled,"reason","S2无有效方式"),200);}
            else Stage2SourceRaceTest.sources().stream().filter(s->s.name().equals(source)).findFirst().orElseThrow().change().apply(p);
            var after=request(transactionMvc,get(Stage2Routes.BASE+"/goods").param("keyword",term),resident.session(),null,200);assertFalse(ids(after).contains(gid));assertTrue(ids(after).contains(normalGoods));var detail=request(transactionMvc,get(Stage2Routes.BASE+"/goods/"+gid),resident.session(),null,200);assertFalse(detail.get("available").getAsBoolean());request(transactionMvc,post(Stage2Routes.BASE+"/cart/items"),resident.session(),map("operationId",req(),"skuId",sku,"quantity",1),409);assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_trade_state WHERE member_id=?",resident.memberId()));
            note("TC-S2-R03-003-01",source,map("goods",gid,"unrelated",normalGoods),map("hidden",true,"unrelatedVisible",true,"reasons",detail.get("reasonCodes")));
        }
        var draft=object(goodsPayload(unitFixture(),upload(normal.login().session(),Stage1Routes.STORE,"GOODS"),10));draft.addProperty("name",term+"草稿");var unapproved=request(supplyMvc,post(Stage1Routes.STORE+"/goods"),normal.login().session(),map("requestId",req(),"payload",draft),200);String draftId=unapproved.getAsJsonObject("goods").get("id").getAsString();assertFalse(ids(request(transactionMvc,get(Stage2Routes.BASE+"/goods").param("keyword",term),resident.session(),null,200)).contains(draftId));var detail=request(transactionMvc,get(Stage2Routes.BASE+"/goods/"+draftId),resident.session(),null,200);assertTrue(detail.get("name").isJsonNull());assertTrue(detail.getAsJsonArray("skus").isEmpty());request(transactionMvc,get(Stage2Routes.BASE+"/goods/"+db.id()),resident.session(),null,404);
    }

    @Test void inactiveChannelCohortsDoNotMultiplyUnfilteredDiscoveryQualificationReads()throws Exception {
        String inactiveConfig=channelConfigFixture(10000);var inactive=readyStore(inactiveConfig);
        String activeConfig=channelConfigFixture(10000);var active=readyStore(activeConfig);var resident=residentFixture();
        String activeGoods=id(db.required("SELECT id FROM li_goods WHERE store_id=?",active.store()),"id"),inactiveGoods=id(db.required("SELECT id FROM li_goods WHERE store_id=?",inactive.store()),"id");
        var qualificationReads=new java.util.concurrent.atomic.AtomicInteger();
        sqlObserver.set(sql->{if(sql.startsWith("SELECT s.id,s.store_disable,s.delete_flag,c.version"))qualificationReads.incrementAndGet();});
        JsonObject page;
        forbidSqlWrites.set(true);
        try{page=request(transactionMvc,get(Stage2Routes.BASE+"/goods"),resident.session(),null,200);}
        finally{sqlObserver.remove();forbidSqlWrites.remove();}
        assertTrue(ids(page).contains(activeGoods));assertFalse(ids(page).contains(inactiveGoods));
        assertTrue(qualificationReads.get()<=10,"Inactive historical channel cohorts multiplied qualification reads: "+qualificationReads.get());
        privateFieldsAbsent(page);
        note("TC-S2-R04-003-01","unfiltered-discovery-excludes-disabled-channel-cohorts-before-expensive-qualification",map("activeStore",active.store(),"inactiveStore",inactive.store()),map("qualificationReads",qualificationReads.get(),"records",ids(page),"businessWrites",0,"noProductionSla",true));
    }

    @Test void addQuantityBoundsIdentityForgeryAndTenReplaysKeepOnePersistentFact()throws Exception {
        var p=purchase(1);request(transactionMvc,put(Stage1Routes.STORE+"/skus/"+p.sku()+"/stock"),p.owner().login().session(),map("requestId",req(),"expectedVersion",stockVersion(p.sku()),"targetQuantity",120,"reason","S2数量边界"),200);var other=residentFixture();
        for(Object quantity:List.of(0,-1,1.5,100,"1")){request(transactionMvc,post(Stage2Routes.BASE+"/cart/items"),p.resident().session(),map("operationId",req(),"skuId",p.sku(),"quantity",quantity),422);}request(transactionMvc,post(Stage2Routes.BASE+"/cart/items"),p.resident().session(),map("operationId",req(),"skuId",p.sku(),"quantity",1,"memberId",other.memberId()),422);request(transactionMvc,post(Stage2Routes.BASE+"/cart/items"),p.resident().session(),map("operationId",req(),"skuId",p.sku(),"quantity",1,"storeId",db.id()),422);
        request(transactionMvc,post(Stage2Routes.BASE+"/cart/items"),p.resident().session(),map("operationId",req(),"skuId",p.sku(),"quantity",1),200);var add=map("operationId",req(),"skuId",p.sku(),"quantity",2);var pool=Executors.newFixedThreadPool(10);try{var tasks=new ArrayList<Future<JsonObject>>();for(int i=0;i<10;i++)tasks.add(pool.submit(()->request(transactionMvc,post(Stage2Routes.BASE+"/cart/items"),p.resident().session(),add,200)));for(var task:tasks)task.get(30,TimeUnit.SECONDS);}finally{pool.shutdownNow();}assertEquals(3,db.count("SELECT quantity FROM li_p0_cart_item WHERE member_id=? AND sku_id=?",p.resident().memberId(),p.sku()));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_cart_item WHERE member_id=? AND sku_id=?",p.resident().memberId(),p.sku()));
        request(transactionMvc,post(Stage2Routes.BASE+"/cart/items"),p.resident().session(),map("operationId",req(),"skuId",p.sku(),"quantity",1),200);assertEquals(4,db.count("SELECT quantity FROM li_p0_cart_item WHERE member_id=? AND sku_id=?",p.resident().memberId(),p.sku()));String line=id(db.required("SELECT id FROM li_p0_cart_item WHERE member_id=?",p.resident().memberId()),"id");long v=db.count("SELECT version FROM li_p0_cart_item WHERE id=?",line);request(transactionMvc,put(Stage2Routes.BASE+"/cart/items/"+line),other.session(),map("operationId",req(),"expectedVersion",v,"quantity",3),404);request(transactionMvc,put(Stage2Routes.BASE+"/cart/items/"+line),p.resident().session(),map("operationId",req(),"expectedVersion",v,"quantity",99),200);request(transactionMvc,post(Stage2Routes.BASE+"/cart/items"),p.resident().session(),map("operationId",req(),"skuId",p.sku(),"quantity",1),422);assertEquals(99,db.count("SELECT quantity FROM li_p0_cart_item WHERE id=?",line));assertEquals(0,s2inventory.held(p.sku()));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_trade_state WHERE member_id=?",p.resident().memberId()));note("TC-S2-R06-008-01","ten-concurrent-replays-and-explicit-new-add",map("member",p.resident().memberId(),"sku",p.sku()),map("afterReplayQuantity",3,"afterNewAdd",4,"finalBoundary",99,"holds",0));
    }
}
