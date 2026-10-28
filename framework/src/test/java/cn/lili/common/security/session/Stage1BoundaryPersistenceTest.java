package cn.lili.common.security.session;

import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.*;
import java.util.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Approved BND variants use native persistence and the production authorization filter.
 * No database resets and no fabricated PASS: rejected variants must leave goods facts unchanged. */
@EnabledIfSystemProperty(named="s1.integration",matches="owned-run")
@Timeout(value=5,unit=java.util.concurrent.TimeUnit.MINUTES)
class Stage1BoundaryPersistenceTest {
    private static final Gson GSON=new Gson();
    @BeforeAll static void start()throws Exception{initialize();}
    @AfterAll static void end()throws Exception{finish();}
    @BeforeEach void refresh(){new Stage1PersistenceTest().freshManagerSessions();}
    static JsonObject create(OwnerFixture owner,Object payload,int status)throws Exception{return call(post(Stage1Routes.STORE+"/goods"),owner.login().session(),map("requestId",req(),"payload",payload),status);}
    static JsonObject valid(OwnerFixture owner)throws Exception{return object(goodsPayload(unitFixture(),upload(owner.login().session(),Stage1Routes.STORE,"GOODS"),1));}
    static JsonObject changed(JsonObject original,String field,Object value){var copy=original.deepCopy();copy.add(field,GSON.toJsonTree(value));return copy;}
    static void rejected(OwnerFixture owner,JsonObject payload)throws Exception{
        long goods=db.count("SELECT COUNT(*) FROM li_p0_goods_control WHERE store_id=?",owner.store()),skus=db.count("SELECT COUNT(*) FROM li_goods_sku WHERE store_id=?",owner.store());
        create(owner,payload,422);assertEquals(goods,db.count("SELECT COUNT(*) FROM li_p0_goods_control WHERE store_id=?",owner.store()));assertEquals(skus,db.count("SELECT COUNT(*) FROM li_goods_sku WHERE store_id=?",owner.store()));
    }
    @Test void bnd006ContentLengthsDraftSearchAndTextEscaping()throws Exception{
        var owner=ownerFixture();var base=valid(owner);
        for(var entry:Map.of("name",new int[]{1,61},"subtitle",new int[]{121},"note",new int[]{501}).entrySet())for(int length:entry.getValue())rejected(owner,changed(base,entry.getKey(),"字".repeat(length)));
        rejected(owner,changed(base,"detailBlocks",List.of(map("type","text","text","字".repeat(10001)))));
        rejected(owner,changed(base,"detailBlocks",List.of(map("type","html","html","<img onerror='x'>"))));
        rejected(owner,changed(base,"galleryFileIds",Collections.nCopies(10,base.getAsJsonArray("galleryFileIds").get(0).getAsString())));
        rejected(owner,changed(base,"storeId",owner.store()));
        var atLimit=changed(changed(changed(base,"name","字".repeat(60)),"subtitle","字".repeat(120)),"note","字".repeat(500));
        atLimit.getAsJsonArray("skus").get(0).getAsJsonObject().addProperty("code","LIMIT_"+db.id());atLimit.add("detailBlocks",GSON.toJsonTree(List.of(map("type","text","text","字".repeat(10000)))));create(owner,atLimit,200);
        var escaped=create(owner,base,200);String goods=escaped.getAsJsonObject("goods").get("id").getAsString();
        String intro=String.valueOf(db.required("SELECT intro FROM li_goods WHERE id=?",goods).get("intro"));assertFalse(intro.contains("<script>"));assertTrue(intro.contains("&lt;script&gt;"));
        var draft=create(owner,map("name","不完整_%草稿"),200);assertTrue(draft.getAsJsonArray("skus").isEmpty());
        var found=call(get(Stage1Routes.STORE+"/goods").param("search","_%"),owner.login().session(),null,200);assertEquals(1,found.get("total").getAsInt());assertEquals(draft.getAsJsonObject("goods").get("id"),found.getAsJsonArray("records").get(0).getAsJsonObject().get("id"));
        call(post(Stage1Routes.STORE+"/goods/"+draft.getAsJsonObject("goods").get("id").getAsString()+"/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",0),422);
        observations.add(map("case","BND-006","invalidRollback",true,"partialDraftSearch",true,"maxTextCodepoints",10000,"escapedNativeHtml",true));
    }
    @Test void bnd007NativeHundredSkuLimitAndDimensions()throws Exception{
        var owner=ownerFixture();var base=valid(owner);var sku=base.getAsJsonArray("skus").get(0).getAsJsonObject();
        for(int dimensions:new int[]{1,2,3}){var copy=base.deepCopy();var specs=new ArrayList<Object>();for(int n=0;n<dimensions;n++)specs.add(map("name","维".repeat(20),"value","值".repeat(30)));for(int n=0;n<specs.size();n++)specs.set(n,map("name","维".repeat(19)+n,"value","值".repeat(30)));copy.getAsJsonArray("skus").get(0).getAsJsonObject().addProperty("code","DIM_"+db.id());copy.getAsJsonArray("skus").get(0).getAsJsonObject().add("specifications",GSON.toJsonTree(specs));create(owner,copy,200);}
        for(Object specs:List.of(List.of(map("name","a","value","1"),map("name","b","value","1"),map("name","c","value","1"),map("name","d","value","1")),List.of(map("name","n".repeat(21),"value","v")),List.of(map("name","n","value","v".repeat(31))),List.of(map("name","Ａ","value","1"),map("name","a","value","2")))){var copy=base.deepCopy();copy.getAsJsonArray("skus").get(0).getAsJsonObject().add("specifications",GSON.toJsonTree(specs));rejected(owner,copy);}
        var many=base.deepCopy();JsonArray values=new JsonArray();for(int i=0;i<100;i++){var item=sku.deepCopy();item.addProperty("code","S1B_"+db.id());item.add("specifications",GSON.toJsonTree(List.of(map("name","规格","value",String.valueOf(i)))));values.add(item);}many.add("skus",values);var result=create(owner,many,200);assertEquals(100,result.getAsJsonArray("skus").size());assertEquals(100,db.count("SELECT COUNT(*) FROM li_goods_sku WHERE goods_id=?",result.getAsJsonObject("goods").get("id").getAsString()));
        many.getAsJsonArray("skus").add(sku);rejected(owner,many);
        var duplicate=base.deepCopy();duplicate.getAsJsonArray("skus").add(sku.deepCopy());rejected(owner,duplicate);
        observations.add(map("case","BND-007","nativeSkus",100,"rejected101",true,"maxDimensions",3));
    }
    @Test void bnd008And018IntegerAmountQuantityWeightAndNativePrecision()throws Exception{
        var owner=ownerFixture();var base=valid(owner);
        for(var entry:Map.<String,List<Object>>of("priceCent",List.of(0,-1,100000000,0.5,"1"),"costCent",List.of(-1,100000000,0.1),"initialQuantity",List.of(-1,10000000,0.5),"weightGram",List.of(-1,100000000,1.1)).entrySet())for(Object value:entry.getValue()){var copy=base.deepCopy();copy.getAsJsonArray("skus").get(0).getAsJsonObject().add(entry.getKey(),GSON.toJsonTree(value));rejected(owner,copy);}
        var max=base.deepCopy();var sku=max.getAsJsonArray("skus").get(0).getAsJsonObject();sku.addProperty("code","A".repeat(64));sku.addProperty("priceCent",99999999);sku.addProperty("costCent",99999999);sku.addProperty("initialQuantity",9999999);sku.addProperty("weightGram",99999999);var saved=create(owner,max,200);String id=saved.getAsJsonArray("skus").get(0).getAsJsonObject().get("id").getAsString();var raw=db.required("SELECT sn,price,cost,quantity,weight FROM li_goods_sku WHERE id=?",id);assertEquals("A".repeat(64),raw.get("sn"));assertEquals(99999999,cents(raw.get("price")));assertEquals(99999999,cents(raw.get("cost")));assertEquals("99999.999",String.valueOf(raw.get("weight")));
        for(String code:List.of("a".repeat(65),"has space","<bad>")){var copy=base.deepCopy();copy.getAsJsonArray("skus").get(0).getAsJsonObject().addProperty("code",code);rejected(owner,copy);}
        var omitted=base.deepCopy();omitted.getAsJsonArray("skus").get(0).getAsJsonObject().remove("costCent");var partial=create(owner,omitted,200);assertTrue(partial.getAsJsonArray("skus").get(0).getAsJsonObject().get("costCent").isJsonNull());
        call(put(Stage1Routes.STORE+"/skus/"+id+"/stock"),owner.login().session(),map("requestId",req(),"expectedVersion",1,"targetQuantity",0,"reason","理".repeat(200)),200);
        call(put(Stage1Routes.STORE+"/skus/"+id+"/stock"),owner.login().session(),map("requestId",req(),"expectedVersion",2,"targetQuantity",1,"reason","理".repeat(201)),422);
        observations.add(map("case","BND-008-018","nativePrecision",true,"omittedCostIsNull",true,"explicitZeroQuantity",true));
    }
    @Test void bnd014CombinedFiltersHaveExactTotalsStablePagesAndStoreScope()throws Exception{
        var owner=ownerFixture();agreementFixture(owner,0,LocalDate.now(Stage1Rules.ZONE),null);deliveryFixture(owner);var base=valid(owner);var ids=new ArrayList<String>();
        for(int i=0;i<5;i++){var payload=base.deepCopy();payload.addProperty("name","S1_TEST筛选商品"+i);payload.getAsJsonArray("skus").get(0).getAsJsonObject().addProperty("initialQuantity",i%2);payload.getAsJsonArray("skus").get(0).getAsJsonObject().addProperty("code","F_"+db.id());var g=create(owner,payload,200);String id=g.getAsJsonObject("goods").get("id").getAsString();ids.add(id);if(i<3){call(post(Stage1Routes.STORE+"/goods/"+id+"/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",0),200);call(post(Stage1Routes.MANAGER+"/goods/"+id+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"contentVersion",0),200);call(post(Stage1Routes.STORE+"/goods/"+id+"/list"),owner.login().session(),map("requestId",req(),"expectedVersion",2),200);}}
        call(post(Stage1Routes.MANAGER+"/goods/"+ids.get(2)+"/force-off"),operator,map("requestId",req(),"expectedVersion",3,"reason","S1_TEST监管筛选"),200);
        for(String status:List.of("DRAFT","SUBMITTED","APPROVED","REJECTED"))for(String market:List.of("UPPER","DOWN"))for(String stock:List.of("IN_STOCK","OUT_OF_STOCK"))for(boolean forced:new boolean[]{false,true}){
            long expected=0;for(int i=0;i<5;i++)if(status.equals(i<3?"APPROVED":"DRAFT")&&market.equals(i<2?"UPPER":"DOWN")&&stock.equals(i%2==1?"IN_STOCK":"OUT_OF_STOCK")&&forced==(i==2))expected++;
            var response=call(get(Stage1Routes.MANAGER+"/goods").param("targetStore",owner.store()).param("status",status).param("marketStatus",market).param("stockState",stock).param("forced",String.valueOf(forced)),operator,null,200);assertEquals(expected,response.get("total").getAsLong(),status+market+stock+forced);assertEquals(expected,response.getAsJsonArray("records").size());
        }
        var first=call(get(Stage1Routes.STORE+"/goods").param("pageSize","1"),owner.login().session(),null,200);var second=call(get(Stage1Routes.STORE+"/goods").param("pageSize","1").param("pageNumber","2"),owner.login().session(),null,200);assertEquals(5,first.get("total").getAsInt());assertNotEquals(first.getAsJsonArray("records").get(0).getAsJsonObject().get("id"),second.getAsJsonArray("records").get(0).getAsJsonObject().get("id"));assertEquals(first.getAsJsonArray("records").get(0).getAsJsonObject().get("id"),call(get(Stage1Routes.STORE+"/goods").param("pageSize","1"),owner.login().session(),null,200).getAsJsonArray("records").get(0).getAsJsonObject().get("id"));
        for(var pair:List.of(new String[]{"pageNumber","0"},new String[]{"pageSize","0"},new String[]{"pageSize","101"},new String[]{"stockState","UNKNOWN"},new String[]{"marketStatus","ANY"},new String[]{"status","PASS"}))call(get(Stage1Routes.STORE+"/goods").param(pair[0],pair[1]),owner.login().session(),null,422);
        var other=ownerFixture();assertEquals(0,call(get(Stage1Routes.STORE+"/goods").param("targetStore",owner.store()),other.login().session(),null,200).get("total").getAsInt());
        observations.add(map("case","BND-014","filterCombinations",32,"stablePagination",true,"crossStoreIgnoredInjection",true));
    }
}
