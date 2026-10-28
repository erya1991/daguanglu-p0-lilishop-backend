package cn.lili.common.security.session;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
@EnabledIfSystemProperty(named="s1.integration",matches="owned-run")
@Timeout(value=5,unit=TimeUnit.MINUTES)
class Stage1ConcurrencyTest {
    @BeforeAll static void start()throws Exception{initialize();}
    @AfterAll static void end()throws Exception{finish();}
    @BeforeEach void refresh(){new Stage1PersistenceTest().freshManagerSessions();}
    record Command(String method,String route,SessionTokens token,Object body){}
    static List<Integer> race(Command... commands)throws Exception{
        var ready=new CountDownLatch(commands.length);var go=new CountDownLatch(1);var results=new ArrayList<Integer>();
        try(var executor=Executors.newFixedThreadPool(commands.length)){var futures=new ArrayList<Future<Integer>>();for(var command:commands)futures.add(executor.submit(()->{ready.countDown();assertTrue(go.await(20,TimeUnit.SECONDS));if(deployedHttp)return liveResponse(command.method(),command.route(),command.token(),"application/json",write(command.body()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).statusCode();var builder=command.method().equals("PUT")?put(command.route()):post(command.route());return mvc.perform(builder.header("accessToken",command.token().accessToken()).contentType("application/json").content(write(command.body()))).andReturn().getResponse().getStatus();}));assertTrue(ready.await(20,TimeUnit.SECONDS));go.countDown();for(var future:futures)results.add(future.get(60,TimeUnit.SECONDS));}
        assertEquals(1,results.stream().filter(v->v==200).count(),results.toString());assertTrue(results.stream().allMatch(v->v==200||v==409),results.toString());observations.add(map("case","barrier-concurrency","routes",Arrays.stream(commands).map(Command::route).toList(),"statuses",results));return results;
    }
    @Test void q010CompetingAdmissionDecisionsCreateAtMostOneOwnerAndOneDecision()throws Exception{
        for(int n=0;n<4;n++){var applicant=login("race_application_"+req());var draft=call(post(Stage1Routes.APPLICANT+"/application"),applicant.session(),map("requestId",req(),"payload",payload(upload(applicant.session(),Stage1Routes.APPLICANT))),200);String id=draft.get("id").getAsString();var submitted=call(post(Stage1Routes.APPLICANT+"/application/"+id+"/submit"),applicant.session(),map("requestId",req(),"expectedVersion",0),200);String store=submitted.get("storeId").getAsString();
            var result=race(new Command("POST",Stage1Routes.MANAGER+"/applications/"+id+"/approve",reviewer,map("requestId",req(),"expectedVersion",1,"revision",1)),new Command("POST",Stage1Routes.MANAGER+"/applications/"+id+"/reject",manager("710000000000000001"),map("requestId",req(),"expectedVersion",1,"revision",1,"reason","S1_TEST竞争驳回")));
            assertEquals(result.get(0)==200?1:0,db.count("SELECT COUNT(*) FROM li_clerk WHERE store_id=? AND shopkeeper=1",store));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_application_revision WHERE application_id=? AND status IN ('APPROVED','REJECTED')",id));assertEquals(1,db.count("SELECT COUNT(*) FROM li_store WHERE id=?",store));
        }
    }
    @Test void q010FirstAgreementAndRulePublishLockTheStoreWithoutExistingRows()throws Exception{
        var owner=ownerFixture();var first=createAgreement(owner,0,LocalDate.now(Stage1Rules.ZONE),null);var second=createAgreement(owner,0,LocalDate.now(Stage1Rules.ZONE),null);String a=first.get("id").getAsString(),b=second.get("id").getAsString();
        var decision=race(new Command("POST",Stage1Routes.MANAGER+"/agreements/"+a+"/confirm",operator,map("requestId",req(),"expectedVersion",0)),new Command("POST",Stage1Routes.MANAGER+"/agreements/"+b+"/confirm",manager("710000000000000002"),map("requestId",req(),"expectedVersion",0)));
        String agreement=decision.get(0)==200?a:b;assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_agreement WHERE store_id=? AND confirmed_at IS NOT NULL",owner.store()));
        var terms=map("name","S1_TEST首次规则","rateBps",0,"baseCode","PRODUCT_PAID_EXCLUDING_SHIPPING","roundCode","FLOOR_CENT","startDate",LocalDate.now(Stage1Rules.ZONE).toString(),"endDate",LocalDate.now(Stage1Rules.ZONE).plusMonths(1).toString(),"commissionClause","S1_TEST双方确认佣金条款","triggerBasis","ORDER_COMPLETED_NO_BLOCKING_AFTERSALE","executionIntent",false);
        // Use the exact approved clause; no test-only relaxation of matching rules.
        terms.put("commissionClause",object(db.required("SELECT terms FROM li_p0_agreement WHERE id=?",agreement).get("terms")).get("commissionClause").getAsString());
        String r1=call(post(Stage1Routes.MANAGER+"/commission-rules"),channel,map("requestId",req(),"storeId",owner.store(),"agreementId",agreement,"businessType","MALL","payload",terms),200).get("id").getAsString();String r2=call(post(Stage1Routes.MANAGER+"/commission-rules"),channel,map("requestId",req(),"storeId",owner.store(),"agreementId",agreement,"businessType","MALL","payload",terms),200).get("id").getAsString();
        race(new Command("POST",Stage1Routes.MANAGER+"/commission-rules/"+r1+"/publish",channel,map("requestId",req(),"expectedVersion",0)),new Command("POST",Stage1Routes.MANAGER+"/commission-rules/"+r2+"/publish",manager("710000000000000003"),map("requestId",req(),"expectedVersion",0)));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_commission_rule WHERE store_id=? AND published_at IS NOT NULL",owner.store()));
    }
    @Test void q010InvitationConfirmAndRevokeKeepOneGrantOrNoGrant()throws Exception{
        var owner=ownerFixture();var applicant=login("race_invitation_"+req());String role=id(db.required("SELECT id FROM li_store_role WHERE store_id=? AND name='READ_ONLY'",owner.store()),"id");var invitation=call(post(Stage1Routes.STORE+"/staff-invitations"),owner.login().session(),map("requestId",req(),"roleIds",List.of(role),"reason","S1_TEST竞争邀请"),200);String id=invitation.get("id").getAsString();call(post(Stage1Routes.APPLICANT+"/invitations/accept"),applicant.session(),map("requestId",req(),"expectedVersion",0,"token",invitation.get("invitationToken").getAsString()),200);
        var decision=race(new Command("POST",Stage1Routes.STORE+"/staff-invitations/"+id+"/confirm",owner.login().session(),map("requestId",req(),"expectedVersion",1,"reason","S1_TEST确认")),new Command("POST",Stage1Routes.STORE+"/staff-invitations/"+id+"/revoke",owner.login().session(),map("requestId",req(),"expectedVersion",1,"reason","S1_TEST撤销")));
        assertEquals(decision.get(0)==200?1:0,db.count("SELECT COUNT(*) FROM li_p0_grant_evidence WHERE member_id=? AND active=1",applicant.memberId()));assertEquals(decision.get(0)==200?1:0,db.count("SELECT COUNT(*) FROM li_clerk WHERE member_id=? AND store_id=?",applicant.memberId(),owner.store()));
    }
    @Test void q010ReviewVersusEditAndListingVersusRegulationPreserveContentAndSafety()throws Exception{
        var owner=ownerFixture();agreementFixture(owner,0,LocalDate.now(Stage1Rules.ZONE),null);deliveryFixture(owner);var created=call(post(Stage1Routes.STORE+"/goods"),owner.login().session(),map("requestId",req(),"payload",goodsPayload(unitFixture(),upload(owner.login().session(),Stage1Routes.STORE,"GOODS"),10)),200);String goods=created.getAsJsonObject("goods").get("id").getAsString();call(post(Stage1Routes.STORE+"/goods/"+goods+"/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",0),200);var changed=created.getAsJsonObject("payload").deepCopy();changed.addProperty("name","S1_TEST不可覆盖待审内容");
        var review=race(new Command("POST",Stage1Routes.MANAGER+"/goods/"+goods+"/approve",reviewer,map("requestId",req(),"expectedVersion",1,"contentVersion",0)),new Command("PUT",Stage1Routes.STORE+"/goods/"+goods+"/content",owner.login().session(),map("requestId",req(),"expectedVersion",1,"payload",changed)));assertEquals(200,review.get(0));assertEquals(0,db.count("SELECT content_version FROM li_p0_goods_control WHERE goods_id=?",goods));
        var sale=race(new Command("POST",Stage1Routes.STORE+"/goods/"+goods+"/list",owner.login().session(),map("requestId",req(),"expectedVersion",2)),new Command("POST",Stage1Routes.MANAGER+"/goods/"+goods+"/force-off",operator,map("requestId",req(),"expectedVersion",2,"reason","S1_TEST监管")));
        if(sale.get(1)==409)call(post(Stage1Routes.MANAGER+"/goods/"+goods+"/force-off"),operator,map("requestId",req(),"expectedVersion",3,"reason","S1_TEST读取新版本后监管"),200);
        assertEquals(1,db.count("SELECT forced_off FROM li_p0_goods_control WHERE goods_id=?",goods));assertEquals("DOWN",db.required("SELECT market_enable FROM li_goods WHERE id=?",goods).get("market_enable"));
    }
}
