package cn.lili.common.security.session;

import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Test-source-only synthetic Code assembly, then actual HTTP through the deployed Spring Security chains. */
@EnabledIfSystemProperty(named="s1.deployed",matches="owned-run")
@Timeout(value=5,unit=java.util.concurrent.TimeUnit.MINUTES)
class Stage1DeployedTest {
    static final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    static final List<Object> evidence=new ArrayList<>();
    @BeforeAll static void start()throws Exception{initialize();deployedHttp=true;for(int port:new int[]{19080,19081})assertEquals(200,client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/actuator/health")).GET().timeout(Duration.ofSeconds(10)).build(),HttpResponse.BodyHandlers.discarding()).statusCode());}
    @AfterAll static void end()throws Exception{try{if(run!=null)Files.writeString(run.resolve("evidence/deployed-http-"+testId+".json"),write(evidence));}finally{deployedHttp=false;finish();}}
    @Test void realHttpChainsAndBrowserFixtures()throws Exception{
        var owner=ownerFixture();String agreement=agreementFixture(owner,0,LocalDate.now(Stage1Rules.ZONE),null);ruleFixture(owner,agreement,0);deliveryFixture(owner);
        String config=channelConfigFixture(1000),relation=channelRelationFixture(owner,config);var current=relationCommand(relation,1,"submit");for(int i=0;i<3;i++)current=relationCommand(relation,current.get("version").getAsLong(),"query");relationCommand(relation,current.get("version").getAsLong(),"enable");
        String photo=upload(owner.login().session(),Stage1Routes.STORE,"GOODS"),unit=unitFixture();
        var created=call(post(Stage1Routes.STORE+"/goods"),owner.login().session(),map("requestId",req(),"payload",goodsPayload(unit,photo,40)),200);
        String goods=created.getAsJsonObject("goods").get("id").getAsString(),sku=created.getAsJsonArray("skus").get(0).getAsJsonObject().get("id").getAsString();
        call(post(Stage1Routes.STORE+"/goods/"+goods+"/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",0),200);call(post(Stage1Routes.MANAGER+"/goods/"+goods+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"contentVersion",0),200);call(post(Stage1Routes.STORE+"/goods/"+goods+"/list"),owner.login().session(),map("requestId",req(),"expectedVersion",2),200);
        var license=object(db.required("SELECT details FROM li_p0_qualification WHERE store_id=? AND type='BUSINESS_LICENSE'",owner.store()).get("details"));license.addProperty("validThrough",LocalDate.now(Stage1Rules.ZONE).plusDays(7).toString());var renewal=call(post(Stage1Routes.STORE+"/qualifications"),owner.login().session(),map("requestId",req(),"payload",map("qualifications",List.of(license))),200);String renewalId=renewal.get("id").getAsString();call(post(Stage1Routes.STORE+"/qualifications/"+renewalId+"/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",0),200);call(post(Stage1Routes.MANAGER+"/applications/"+renewalId+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"revision",1),200);
        var inventory=Stage1AuthorizationMatrixTest.employee(owner,List.of(Stage1AuthorizationMatrixTest.roles(owner).get("INVENTORY")));
        var applicant=login("browser_applicant_"+req());
        var invited=login("browser_invited_"+req());
        String code=req();codes.put(code,"browser_resident_"+req());var resident=residentLogin.login(code);
        var other=ownerFixture();
        for(var session:List.of(reviewer,operator,channel))http(19080,"GET","/manager/passport/user/info",session,null,200);
        http(19080,"GET","/manager/permission/menu/memberMenu",reviewer,null,200);
        http(19080,"GET","/store/member/user/info",owner.login().session(),null,200);http(19080,"GET","/store/member/user",owner.login().session(),null,200);
        http(19080,"GET",Stage1Routes.APPLICANT+"/identity",applicant.session(),null,403); // Unknown routes are denied; only the existing Stage0 identity path is served.
        http(19080,"GET","/store/passport/applicant/identity",applicant.session(),null,200);
        for(String route:List.of("/context","/workbench","/profile","/admission-options","/qualifications","/cooperation","/eligibility","/staff","/staff-invitations","/staff-role-options","/goods","/goods/"+goods,"/fulfillment","/points","/categories","/units","/regions"))http(19080,"GET",Stage1Routes.STORE+route,owner.login().session(),null,200);
        for(String route:List.of("/applications","/stores","/goods/reviews"))http(19080,"GET",Stage1Routes.MANAGER+route,reviewer,null,200);
        http(19080,"GET",Stage1Routes.MANAGER+"/agreements",reviewer,null,403);
        http(19080,"GET",Stage1Routes.MANAGER+"/agreements",operator,null,200);
        for(String route:List.of("/channel-configs","/submerchants","/receivers","/commission-rules"))http(19080,"GET",Stage1Routes.MANAGER+route,channel,null,200);
        http(19081,"GET",Stage1Routes.BUYER+"/goods/"+goods,resident.session(),null,200);
        var result=http(19081,"POST",Stage1Routes.BUYER+"/precheck",resident.session(),map("storeId",owner.store(),"items",List.of(map("skuId",sku,"quantity",1)),"method","MERCHANT_DELIVERY","address",map("provinceCode","990000","cityCode","990100","districtCode","990101")),200);assertEquals("PASS",result.getAsJsonObject("result").get("status").getAsString());
        http(19080,"GET",Stage1Routes.STORE+"/goods/"+goods,other.login().session(),null,404);
        http(19080,"GET",Stage1Routes.STORE+"/goods",resident.session(),null,403);http(19081,"GET",Stage1Routes.BUYER+"/goods/"+goods,owner.login().session(),null,403);
        http(19080,"POST","/store/goods/goods",owner.login().session(),map("requestId",req()),403);
        http(19081,"POST","/buyer/trade/order",resident.session(),map("requestId",req()),403);
        Files.writeString(run.resolve("private/browser-fixtures.json"),write(map("source","S1_TEST synthetic Code gateway; actual persistent grants and SessionService","createdAt",clock.instant(),"testId",testId,"categoryId",category,"unitId",unit,"goodsId",goods,"skuId",sku,"photoId",photo,"storeId",owner.store(),"configId",config,"relationId",relation,"owner",owner.login(),"inventory",inventory.login(),"applicant",applicant,"invited",invited,"resident",resident,"reviewer",reviewer,"operator",operator,"channel",channel,"otherOwner",other.login())),StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING);
        evidence.add(map("fixtureExport","private/browser-fixtures.json","syntheticIdentity",true,"realExternalIdentity",false));
    }
    static JsonObject http(int port,String method,String path,SessionTokens session,Object body,int expected)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path)).timeout(Duration.ofSeconds(20)).header("accessToken",session.accessToken());
        if(body==null)builder.method(method,HttpRequest.BodyPublishers.noBody());else builder.header("Content-Type","application/json").method(method,HttpRequest.BodyPublishers.ofString(write(body)));
        var response=client.send(builder.build(),HttpResponse.BodyHandlers.ofString());var value=object(response.body());evidence.add(map("port",port,"method",method,"path",path,"expectedStatus",expected,"actualStatus",response.statusCode(),"code",value.get("code")));
        assertEquals(expected,response.statusCode(),method+" "+path+" code="+value.get("code"));if(expected==200)assertTrue(value.get("success").getAsBoolean(),path);return value;
    }
}
