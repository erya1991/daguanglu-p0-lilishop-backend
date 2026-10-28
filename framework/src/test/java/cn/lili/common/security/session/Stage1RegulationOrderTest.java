package cn.lili.common.security.session;

import cn.lili.modules.community.p0.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.*;
import java.util.concurrent.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfSystemProperty(named="s1.integration",matches="owned-run")
@Timeout(value=4,unit=TimeUnit.MINUTES)
class Stage1RegulationOrderTest {
    @BeforeAll static void start()throws Exception{initialize();}
    @AfterAll static void end()throws Exception{finish();}
    @AfterEach void clear(){sqlObserver.remove();}
    @Test void bothCommitOrdersResolveTheConflictWithoutLosingRegulation()throws Exception{
        for(boolean restrictionFirst:List.of(false,true)){
            new Stage1SupplyMatrixTest().refresh();var owner=Stage1SupplyMatrixTest.ready();var product=Stage1SupplyMatrixTest.createGoods(owner,3,List.of("MERCHANT_DELIVERY"),false);String id=product.getAsJsonObject("goods").get("id").getAsString();
            call(post(Stage1Routes.STORE+"/goods/"+id+"/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",0),200);
            call(post(Stage1Routes.MANAGER+"/goods/"+id+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"contentVersion",0),200);
            var locked=new CountDownLatch(1);var waiting=new CountDownLatch(1);var release=new CountDownLatch(1);
            java.util.function.Function<Boolean,Integer> command=restriction->{try{
                sqlObserver.set(sql->{String normalized=sql.strip().replaceAll("\\s+"," ").toUpperCase(Locale.ROOT);if(restriction==restrictionFirst&&normalized.startsWith(restriction?"UPDATE LI_P0_GOODS_CONTROL SET FORCED_OFF":"UPDATE LI_GOODS SET ")){sqlObserver.remove();locked.countDown();try{if(!release.await(20,TimeUnit.SECONDS))throw new java.sql.SQLException("S1_TEST barrier expired");}catch(InterruptedException failure){Thread.currentThread().interrupt();throw new java.sql.SQLException(failure);}}else if(restriction!=restrictionFirst&&normalized.startsWith("SELECT ID FROM LI_P0_CONSISTENCY_GUARD")){sqlObserver.remove();waiting.countDown();}});
                String route=(restriction?Stage1Routes.MANAGER:Stage1Routes.STORE)+"/goods/"+id+(restriction?"/force-off":"/list");var body=map("requestId",req(),"expectedVersion",2);if(restriction)body.put("reason","S1_TEST可证实监管提交顺序");return mvc.perform(post(route).header("accessToken",(restriction?operator:owner.login().session()).accessToken()).contentType("application/json").content(write(body))).andReturn().getResponse().getStatus();
            }catch(Exception failure){throw new RuntimeException(failure);}finally{sqlObserver.remove();}};
            try(var threads=Executors.newFixedThreadPool(2)){var first=threads.submit(()->command.apply(restrictionFirst));assertTrue(locked.await(20,TimeUnit.SECONDS));var second=threads.submit(()->command.apply(!restrictionFirst));assertTrue(waiting.await(20,TimeUnit.SECONDS));release.countDown();assertEquals(200,first.get(40,TimeUnit.SECONDS));assertEquals(409,second.get(40,TimeUnit.SECONDS));}finally{release.countDown();}
            if(!restrictionFirst)call(post(Stage1Routes.MANAGER+"/goods/"+id+"/force-off"),operator,map("requestId",req(),"expectedVersion",3,"reason","S1_TEST读取最新版本后完成监管"),200);
            long version=db.count("SELECT version FROM li_p0_goods_control WHERE goods_id=?",id);call(post(Stage1Routes.STORE+"/goods/"+id+"/list"),owner.login().session(),map("requestId",req(),"expectedVersion",version),409);
            assertEquals("BLOCKED",Stage1CrossHostTest.precheck(owner,Stage1CrossHostTest.resident(),Stage1CrossHostTest.sku(product),"MERCHANT_DELIVERY",null,true).get("status").getAsString());
            product.getAsJsonObject("payload").addProperty("subtitle","S1_TEST内容编辑不能清监管");call(put(Stage1Routes.STORE+"/goods/"+id+"/content"),owner.login().session(),map("requestId",req(),"expectedVersion",version,"payload",product.get("payload")),200);assertEquals(1,db.count("SELECT forced_off FROM li_p0_goods_control WHERE goods_id=?",id));
            call(post(Stage1Routes.MANAGER+"/goods/"+id+"/release"),operator,map("requestId",req(),"expectedVersion",version+1,"reason","S1_TEST解除仍需重审上架"),200);assertEquals("DOWN",db.required("SELECT market_enable FROM li_goods WHERE id=?",id).get("market_enable"));assertEquals("DRAFT",db.required("SELECT review_status FROM li_p0_goods_control WHERE goods_id=?",id).get("review_status"));assertEquals(2,db.count("SELECT COUNT(*) FROM li_p0_audit WHERE object_id=? AND action IN ('goods.force-off','goods.release')",id));
            observations.add(map("case","W11-commit-order","restrictionFirst",restrictionFirst,"firstStatus",200,"secondStatus",409,"latestRegulationApplied",true));
        }
    }
}
