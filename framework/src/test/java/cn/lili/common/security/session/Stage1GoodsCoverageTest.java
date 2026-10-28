package cn.lili.common.security.session;

import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfSystemProperty(named="s1.integration",matches="owned-run")
class Stage1GoodsCoverageTest {
    @BeforeAll static void start()throws Exception{initialize();}
    @AfterAll static void end()throws Exception{finish();}
    @BeforeEach void refresh(){new Stage1PersistenceTest().freshManagerSessions();}
    @AfterEach void clear(){sqlObserver.remove();}
    @Test void m06SecondNativeSkuFailureRollsBackAllRowsAndOriginalRequestCanRecover()throws Exception{
        var owner=ownerFixture();var payload=object(goodsPayload(unitFixture(),upload(owner.login().session(),Stage1Routes.STORE,"GOODS"),10));var template=payload.getAsJsonArray("skus").get(0).getAsJsonObject();var items=new JsonArray();
        for(int i=0;i<3;i++){var sku=template.deepCopy();sku.addProperty("code","ROW_"+db.id());sku.add("specifications",new Gson().toJsonTree(List.of(map("name","型号","value","款"+i))));items.add(sku);}payload.add("skus",items);
        var command=map("requestId",req(),"payload",payload);var before=Stage1SupplyMatrixTest.factCounts();var hits=new AtomicInteger();sqlObserver.set(sql->{if(sql.stripLeading().toUpperCase(Locale.ROOT).startsWith("INSERT INTO LI_GOODS_SKU")&&hits.incrementAndGet()==2)throw new java.sql.SQLTransientConnectionException("S1_TEST second native SKU storage interruption","08006");});
        call(post(Stage1Routes.STORE+"/goods"),owner.login().session(),command,503);sqlObserver.remove();assertEquals(2,hits.get());assertEquals(before,Stage1SupplyMatrixTest.factCounts());assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_operation WHERE request_id=?",command.get("requestId")));
        var created=call(post(Stage1Routes.STORE+"/goods"),owner.login().session(),command,200);String id=created.getAsJsonObject("goods").get("id").getAsString();assertEquals(3,created.getAsJsonArray("skus").size());assertEquals(created,call(post(Stage1Routes.STORE+"/goods"),owner.login().session(),command,200));assertEquals(3,db.count("SELECT COUNT(*) FROM li_goods_sku WHERE goods_id=?",id));assertEquals(3,db.count("SELECT COUNT(*) FROM li_p0_inventory_adjustment WHERE store_id=?",owner.store()));
        var different=new LinkedHashMap<>(command);different.put("payload",map("name","S1_TEST不同内容"));call(post(Stage1Routes.STORE+"/goods"),owner.login().session(),different,409);
        var savedPayload=created.getAsJsonObject("payload").deepCopy();savedPayload.addProperty("name","S1_TEST批量修订");savedPayload.getAsJsonArray("skus").get(2).getAsJsonObject().addProperty("code",savedPayload.getAsJsonArray("skus").get(0).getAsJsonObject().get("code").getAsString());before=Stage1SupplyMatrixTest.factCounts();
        call(put(Stage1Routes.STORE+"/goods/"+id+"/content"),owner.login().session(),map("requestId",req(),"expectedVersion",0,"payload",savedPayload),422);assertEquals(before,Stage1SupplyMatrixTest.factCounts());assertEquals(0,db.count("SELECT version FROM li_p0_goods_control WHERE goods_id=?",id));
        var a=created.getAsJsonObject("payload").deepCopy();a.addProperty("name","S1_TEST并发名称A");var b=a.deepCopy();b.addProperty("name","S1_TEST并发名称B");
        Stage1ConcurrencyTest.race(new Stage1ConcurrencyTest.Command("PUT",Stage1Routes.STORE+"/goods/"+id+"/content",owner.login().session(),map("requestId",req(),"expectedVersion",0,"payload",a)),new Stage1ConcurrencyTest.Command("PUT",Stage1Routes.STORE+"/goods/"+id+"/content",owner.login().session(),map("requestId",req(),"expectedVersion",0,"payload",b)));
        assertEquals(1,db.count("SELECT version FROM li_p0_goods_control WHERE goods_id=?",id));assertEquals(3,db.count("SELECT COUNT(*) FROM li_goods_sku WHERE goods_id=?",id));assertEquals(30,db.count("SELECT SUM(quantity) FROM li_goods_sku WHERE goods_id=?",id));
    }
    @Test void w10ReviewSnapshotNeverRewritesCurrentInventoryAndZeroCanRecoverWithoutReaudit()throws Exception{
        var owner=Stage1SupplyMatrixTest.ready();var row=Stage1SupplyMatrixTest.createGoods(owner,10,List.of("MERCHANT_DELIVERY"),false);String id=row.getAsJsonObject("goods").get("id").getAsString(),sku=row.getAsJsonArray("skus").get(0).getAsJsonObject().get("id").getAsString();
        call(post(Stage1Routes.STORE+"/goods/"+id+"/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",0),200);String initial=String.valueOf(db.required("SELECT payload FROM li_p0_goods_review WHERE goods_id=?",id).get("payload"));
        for(int quantity:new int[]{0,7}){call(put(Stage1Routes.STORE+"/skus/"+sku+"/stock"),owner.login().session(),map("requestId",req(),"expectedVersion",quantity==0?1:2,"targetQuantity",quantity,"reason","S1_TEST送审后调库存"),200);var detail=call(get(Stage1Routes.MANAGER+"/goods/"+id),reviewer,null,200);assertEquals(10,detail.getAsJsonArray("reviews").get(0).getAsJsonObject().getAsJsonObject("submitted").getAsJsonArray("skus").get(0).getAsJsonObject().get("quantity").getAsInt());assertEquals(quantity,detail.getAsJsonArray("skus").get(0).getAsJsonObject().get("quantity").getAsInt());assertEquals(0,detail.getAsJsonObject("goods").get("contentVersion").getAsInt());}
        call(post(Stage1Routes.MANAGER+"/goods/"+id+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"contentVersion",0),200);call(post(Stage1Routes.STORE+"/goods/"+id+"/list"),owner.login().session(),map("requestId",req(),"expectedVersion",2),200);assertEquals(initial,String.valueOf(db.required("SELECT payload FROM li_p0_goods_review WHERE goods_id=?",id).get("payload")));assertEquals(7,db.count("SELECT quantity FROM li_goods_sku WHERE id=?",sku));
        String code=req();codes.put(code,"S1_TEST_snapshot_"+req());var buyer=residentLogin.login(code).session();var input=map("storeId",owner.store(),"items",List.of(map("skuId",sku,"quantity",7)),"method","MERCHANT_DELIVERY","address",map("provinceCode","990000","cityCode","990100","districtCode","990101"));
        assertEquals("PASS",call(post(Stage1Routes.BUYER+"/precheck"),buyer,input,200).get("status").getAsString());call(put(Stage1Routes.STORE+"/skus/"+sku+"/stock"),owner.login().session(),map("requestId",req(),"expectedVersion",3,"targetQuantity",0,"reason","S1_TEST零库存逻辑上架"),200);assertTrue(call(post(Stage1Routes.BUYER+"/precheck"),buyer,input,200).get("reasonCodes").toString().contains("OUT_OF_STOCK"));var current=call(get(Stage1Routes.STORE+"/goods/"+id),owner.login().session(),null,200).getAsJsonObject("goods");assertEquals("UPPER",current.get("marketStatus").getAsString());assertEquals("APPROVED",current.get("reviewStatus").getAsString());
    }
    @Test void w10ApprovalRechecksEveryChangedDependencyAndKeepsPendingSnapshot()throws Exception{
        for(String dependency:List.of("agreement","qualification","category","point","image")){
            category=Stage1BusinessBoundaryTest.category("0","S1_TEST审核重验"+db.id(),0,200).get("id").getAsString();call(post(Stage1Routes.MANAGER+"/categories/"+category+"/enable"),operator,map("requestId",req(),"expectedVersion",0,"reason","S1_TEST独立类目"),200);
            var owner=ownerFixture();String agreement=agreementFixture(owner,0,LocalDate.now(Stage1Rules.ZONE),null);String point=createPointFixture(owner,"COMMUNITY_PICKUP");call(post(Stage1Routes.STORE+"/points/"+point+"/enable"),owner.login().session(),map("requestId",req(),"expectedVersion",0,"reason","S1_TEST启用"),200);var methods=object(deliveryMethods());methods.getAsJsonObject("COMMUNITY_PICKUP").addProperty("enabled",true);call(put(Stage1Routes.STORE+"/fulfillment"),owner.login().session(),map("requestId",req(),"expectedVersion",0,"methods",methods,"reason","S1_TEST独立方式"),200);
            var row=Stage1SupplyMatrixTest.createGoods(owner,0,List.of("COMMUNITY_PICKUP"),false);String id=row.getAsJsonObject("goods").get("id").getAsString();call(post(Stage1Routes.STORE+"/goods/"+id+"/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",0),200);var snapshot=db.required("SELECT * FROM li_p0_goods_review WHERE goods_id=?",id);
            switch(dependency){
                case "agreement"->call(post(Stage1Routes.MANAGER+"/agreements/"+agreement+"/terminate"),operator,map("requestId",req(),"expectedVersion",1,"reason","S1_TEST审核前终止"),200);
                case "qualification"->{String certificate=Stage1Json.id(db.required("SELECT id FROM li_p0_qualification WHERE store_id=?",owner.store()),"id");call(post(Stage1Routes.MANAGER+"/qualifications/"+certificate+"/revoke"),reviewer,map("requestId",req(),"expectedVersion",0,"reason","S1_TEST审核前撤证"),200);}
                case "category"->call(post(Stage1Routes.MANAGER+"/categories/"+category+"/disable"),operator,map("requestId",req(),"expectedVersion",1,"reason","S1_TEST审核前禁用"),200);
                case "point"->call(post(Stage1Routes.STORE+"/points/"+point+"/disable"),owner.login().session(),map("requestId",req(),"expectedVersion",1,"reason","S1_TEST审核前停点"),200);
                case "image"->db.update("UPDATE li_p0_file_control SET byte_size=byte_size+1 WHERE file_id=?",row.getAsJsonObject("payload").getAsJsonArray("galleryFileIds").get(0).getAsString());
            }
            call(post(Stage1Routes.MANAGER+"/goods/"+id+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"contentVersion",0),dependency.equals("image")?503:Set.of("category","point").contains(dependency)?422:409);assertEquals(snapshot,db.required("SELECT * FROM li_p0_goods_review WHERE goods_id=?",id));assertEquals("SUBMITTED",db.required("SELECT review_status FROM li_p0_goods_control WHERE goods_id=?",id).get("review_status"));
        }
    }
}
