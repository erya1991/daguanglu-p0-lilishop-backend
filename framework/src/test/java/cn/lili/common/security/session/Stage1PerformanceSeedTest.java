package cn.lili.common.security.session;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Explicit F-PERF bulk fixture, not evidence of 5,000 normal approvals. No order or funds tables. */
@EnabledIfSystemProperty(named="s1.performance.seed",matches="owned-run")
@Timeout(value=15,unit=java.util.concurrent.TimeUnit.MINUTES)
class Stage1PerformanceSeedTest {
    @BeforeAll static void start()throws Exception{initialize();}
    @AfterAll static void end()throws Exception{finish();}
    @Test void seedFiftyStoresFiveThousandGoodsTwentyThousandSkus()throws Exception{
        Path output=run.resolve("private/performance-fixtures.json");assertFalse(Files.exists(output),"Existing performance fixture must be reused or explicitly versioned");
        String seed="S1_PERF_20261027_A",unit=unitFixture();var stores=new ArrayList<Object>();
        for(int i=0;i<50;i++){
            var owner=ownerFixture();String agreement=agreementFixture(owner,0,LocalDate.now(Stage1Rules.ZONE),null);ruleFixture(owner,agreement,0);deliveryFixture(owner);
            String photo=upload(owner.login().session(),Stage1Routes.STORE,"GOODS");var payload=object(goodsPayload(unit,photo,100));var skus=new JsonArray();
            for(int k=0;k<4;k++)skus.add(object(map("code",seed+"_"+i+"_0_"+k,"specifications",List.of(map("name","合成规格","value",String.valueOf(k))),"priceCent",100+k,"costCent",0,"initialQuantity",100,"weightGram",100,"enabled",true)));
            payload.add("skus",skus);payload.addProperty("name",seed+"_"+i+"_0");
            var created=call(post(Stage1Routes.STORE+"/goods"),owner.login().session(),map("requestId",req(),"payload",payload),200);String original=created.getAsJsonObject("goods").get("id").getAsString();
            call(post(Stage1Routes.STORE+"/goods/"+original+"/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",0),200);call(post(Stage1Routes.MANAGER+"/goods/"+original+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"contentVersion",0),200);call(post(Stage1Routes.STORE+"/goods/"+original+"/list"),owner.login().session(),map("requestId",req(),"expectedVersion",2),200);
            var nativeGoods=db.required("SELECT * FROM li_goods WHERE id=?",original);var control=db.required("SELECT * FROM li_p0_goods_control WHERE goods_id=?",original);var draft=db.required("SELECT * FROM li_p0_goods_draft WHERE goods_id=?",original);var review=db.required("SELECT * FROM li_p0_goods_review WHERE goods_id=?",original);var nativeSkus=db.rows("SELECT * FROM li_goods_sku WHERE goods_id=? ORDER BY id",original);var controls=db.rows("SELECT * FROM li_p0_sku_control WHERE goods_id=? ORDER BY sku_id",original);assertEquals(4,nativeSkus.size());
            for(int j=1;j<100;j++){
                String goods=db.id(),name=seed+"_"+i+"_"+j;var content=object(draft.get("payload"));content.addProperty("name",name);var contentSkus=content.getAsJsonArray("skus");var snapshot=object(review.get("payload"));
                for(int k=0;k<4;k++){String sku=db.id(),code=name+"_"+k;String old=id(nativeSkus.get(k),"id");copy("li_goods_sku",nativeSkus.get(k),map("id",sku,"goods_id",goods,"sn",code,"goods_name",name));copy("li_p0_sku_control",controls.get(k),map("sku_id",sku,"goods_id",goods,"normalized_code",code));for(var item:contentSkus)if(item.getAsJsonObject().get("id").getAsString().equals(old)){item.getAsJsonObject().addProperty("id",sku);item.getAsJsonObject().addProperty("code",code);}for(var item:snapshot.getAsJsonArray("skus"))if(item.getAsJsonObject().get("id").getAsString().equals(old)){item.getAsJsonObject().addProperty("id",sku);item.getAsJsonObject().addProperty("code",code);}}
                snapshot.add("content",content.deepCopy());copy("li_goods",nativeGoods,map("id",goods,"goods_name",name,"sn",name,"create_by","S1_TEST_PERF"));copy("li_p0_goods_control",control,map("goods_id",goods,"content_hash",hash(content)));copy("li_p0_goods_draft",draft,map("goods_id",goods,"payload",write(content)));copy("li_p0_goods_review",review,map("goods_id",goods,"payload",write(snapshot),"content_hash",hash(content)));
            }
            assertEquals(100,db.count("SELECT COUNT(*) FROM li_p0_goods_control WHERE store_id=?",owner.store()));assertEquals(400,db.count("SELECT COUNT(*) FROM li_p0_sku_control WHERE store_id=?",owner.store()));
            stores.add(map("storeId",owner.store(),"open",owner.open(),"owner",owner.login(),"goodsId",original,"skuId",id(nativeSkus.getFirst(),"id"),"inventoryVersion",0));System.out.println("S1_PERF_STORE_READY "+(i+1)+"/50");
        }
        // Refresh after data preparation so all 20 measured sessions have the same full Access window.
        var ready=new ArrayList<Object>();for(Object item:stores){var row=object(item);row.add("owner",object(login(row.get("open").getAsString())));ready.add(row);}
        Files.writeString(output,write(map("seed",seed,"createdAt",clock.instant(),"stores",ready,"counts",map("stores",50,"goods",5000,"skus",20000),"source","F-PERF bulk clones from 50 normally created native template goods; bulk rows are not business acceptance evidence")),StandardOpenOption.CREATE_NEW);
        Files.writeString(run.resolve("evidence/performance-seed-"+testId+".json"),write(map("seed",seed,"counts",map("stores",50,"goods",5000,"skus",20000),"fixturePath","private/performance-fixtures.json","source","S1_TEST_PERF bulk fixture","schemaMigrations",db.count("SELECT COUNT(*) FROM li_p0_schema_history WHERE status='DONE'"))));
    }
    static final Set<String> TABLES=Set.of("li_goods","li_goods_sku","li_p0_goods_control","li_p0_goods_draft","li_p0_goods_review","li_p0_sku_control");
    static void copy(String table,Map<String,Object> source,Map<String,Object> changes){assertTrue(TABLES.contains(table));var row=new LinkedHashMap<>(source);row.putAll(changes);assertTrue(row.keySet().stream().allMatch(s->s.matches("[a-z_]+")));db.update("INSERT INTO "+table+" ("+String.join(",",row.keySet())+") VALUES ("+String.join(",",Collections.nCopies(row.size(),"?"))+")",row.values().toArray());}
}
