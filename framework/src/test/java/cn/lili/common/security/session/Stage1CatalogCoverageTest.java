package cn.lili.common.security.session;
import cn.lili.modules.community.p0.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.*;
import java.util.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfSystemProperty(named="s1.integration",matches="owned-run")
class Stage1CatalogCoverageTest {
    @BeforeAll static void start()throws Exception{initialize();}
    @AfterAll static void end()throws Exception{finish();}
    @BeforeEach void refresh(){new Stage1PersistenceTest().freshManagerSessions();}
    @Test void catalogDefaultsScopeAncestorDisableAndReferencedUnitKeepHistoricalMeaning()throws Exception{
        var root=call(post(Stage1Routes.MANAGER+"/categories"),operator,map("requestId",req(),"parentId","0","name","S1_TEST影响"+db.id(),"reason","S1_TEST默认值"),200);category=root.get("id").getAsString();String parent=category;assertFalse(root.get("enabled").getAsBoolean());assertEquals(0,root.get("sort").getAsInt());call(post(Stage1Routes.MANAGER+"/categories/"+parent+"/enable"),operator,map("requestId",req(),"expectedVersion",0,"reason","S1_TEST启用"),200);
        var owner=ownerFixture();String leaf=Stage1BusinessBoundaryTest.category(parent,"S1_TEST叶子",0,200).get("id").getAsString();call(post(Stage1Routes.MANAGER+"/categories/"+leaf+"/enable"),operator,map("requestId",req(),"expectedVersion",0,"reason","S1_TEST启用"),200);agreementFixture(owner,0,LocalDate.now(Stage1Rules.ZONE),null);category=leaf;deliveryFixture(owner);
        String unit=unitFixture();var payload=object(goodsPayload(unit,upload(owner.login().session(),Stage1Routes.STORE,"GOODS"),2));var created=call(post(Stage1Routes.STORE+"/goods"),owner.login().session(),map("requestId",req(),"payload",payload),200);String goods=created.getAsJsonObject("goods").get("id").getAsString();String snapshot=hash(db.required("SELECT payload FROM li_p0_goods_draft WHERE goods_id=?",goods));
        var outside=Stage1BusinessBoundaryTest.category("0","S1_TEST界外"+db.id(),0,200);String outsideId=outside.get("id").getAsString();var illegal=payload.deepCopy();illegal.addProperty("categoryId",outsideId);call(post(Stage1Routes.STORE+"/goods"),owner.login().session(),map("requestId",req(),"payload",illegal),422);call(post(Stage1Routes.MANAGER+"/categories/"+outsideId+"/enable"),operator,map("requestId",req(),"expectedVersion",0,"reason","S1_TEST界外仍拒绝"),200);call(post(Stage1Routes.STORE+"/goods"),owner.login().session(),map("requestId",req(),"payload",illegal),422);
        call(put(Stage1Routes.MANAGER+"/categories/"+leaf),operator,map("requestId",req(),"expectedVersion",1,"parentId",outsideId,"name","S1_TEST不可搬移","reason","S1_TEST已有商品"),409);
        call(put(Stage1Routes.MANAGER+"/categories/"+leaf),operator,map("requestId",req(),"expectedVersion",1,"parentId",parent,"name","S1_TEST展示改名","reason","S1_TEST保持原ID"),200);var detail=call(get(Stage1Routes.MANAGER+"/categories/"+leaf).param("pageSize","1"),operator,null,200);assertEquals(3,detail.getAsJsonArray("history").size());assertEquals(1,detail.getAsJsonObject("impact").get("goodsTotal").getAsInt());assertEquals(owner.store(),detail.getAsJsonObject("impact").getAsJsonArray("stores").get(0).getAsJsonObject().get("storeId").getAsString());assertEquals(goods,detail.getAsJsonObject("impact").getAsJsonArray("goods").get(0).getAsJsonObject().get("id").getAsString());assertEquals(snapshot,hash(db.required("SELECT payload FROM li_p0_goods_draft WHERE goods_id=?",goods)));
        call(post(Stage1Routes.MANAGER+"/units/"+unit+"/disable"),operator,map("requestId",req(),"expectedVersion",1,"reason","S1_TEST停新选"),200);call(post(Stage1Routes.STORE+"/goods"),owner.login().session(),map("requestId",req(),"payload",payload),422);call(post(Stage1Routes.STORE+"/goods/"+goods+"/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",0),200);call(post(Stage1Routes.MANAGER+"/goods/"+goods+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"contentVersion",0),200);call(post(Stage1Routes.STORE+"/goods/"+goods+"/list"),owner.login().session(),map("requestId",req(),"expectedVersion",2),200);
        call(post(Stage1Routes.MANAGER+"/categories/"+parent+"/disable"),operator,map("requestId",req(),"expectedVersion",1,"reason","S1_TEST父停子孙失效"),200);assertFalse((Boolean)eligibility.evaluate(owner.store(),leaf,null).get("BASIC_ELIGIBLE"));call(post(Stage1Routes.STORE+"/goods"),owner.login().session(),map("requestId",req(),"payload",payload),422);call(delete(Stage1Routes.MANAGER+"/categories/"+parent),operator,null,403);call(delete(Stage1Routes.MANAGER+"/categories/"+leaf),operator,null,403);assertEquals(1,db.count("SELECT COUNT(*) FROM li_goods WHERE id=? AND delete_flag=0",goods));
        var impact=call(get(Stage1Routes.MANAGER+"/categories/"+parent).param("pageNumber","2").param("pageSize","1"),operator,null,200).getAsJsonObject("impact");assertEquals(1,impact.get("goodsTotal").getAsInt());assertTrue(impact.getAsJsonArray("goods").isEmpty());
    }
}
