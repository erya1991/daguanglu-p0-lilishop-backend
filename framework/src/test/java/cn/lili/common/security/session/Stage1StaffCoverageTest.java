package cn.lili.common.security.session;
import cn.lili.modules.community.p0.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.common.security.session.Stage1AuthorizationMatrixTest.*;
import static cn.lili.common.security.session.Stage1ConcurrencyTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
@EnabledIfSystemProperty(named="s1.integration",matches="owned-run")
class Stage1StaffCoverageTest {
    @BeforeAll static void start()throws Exception{initialize();}
    @AfterAll static void end()throws Exception{finish();}
    @BeforeEach void refresh(){new Stage1PersistenceTest().freshManagerSessions();}
    @Test void m02OwnerImmutabilityCrossStoreAndInjectedIdentityFieldsNeverGrant()throws Exception{
        var owner=ownerFixture();var other=ownerFixture();var role=roles(owner);var worker=employee(owner,List.of(role.get("READ_ONLY")));String ownerClerk=id(db.required("SELECT id FROM li_clerk WHERE store_id=? AND shopkeeper=1",owner.store()),"id");
        for(String action:List.of("disable","revoke"))call(post(Stage1Routes.STORE+"/staff/"+ownerClerk+"/"+action),owner.login().session(),map("requestId",req(),"expectedVersion",0,"reason","S1_TEST不能撤自己"),409);
        call(put(Stage1Routes.STORE+"/staff/"+ownerClerk+"/roles"),owner.login().session(),map("requestId",req(),"expectedVersion",0,"roleIds",List.of(role.get("READ_ONLY")),"reason","S1_TEST唯一Owner"),409);
        for(String field:List.of("memberId","phone","nickname","shopkeeper","isSuper","storeId"))call(post(Stage1Routes.STORE+"/staff-invitations"),owner.login().session(),map("requestId",req(),"roleIds",List.of(role.get("READ_ONLY")),"reason","S1_TEST不能凭前端授予",field,"1"),422);
        call(put(Stage1Routes.STORE+"/staff/"+worker.clerk()+"/roles"),worker.login().session(),map("requestId",req(),"expectedVersion",0,"roleIds",List.of(role.get("COST_EDITOR")),"reason","S1_TEST自提权"),403);
        call(get(Stage1Routes.STORE+"/staff/"+worker.clerk()),other.login().session(),null,404);
        call(post(Stage1Routes.STORE+"/owner/transfer"),owner.login().session(),map("requestId",req()),403);
        assertEquals(1,db.count("SELECT COUNT(*) FROM li_clerk WHERE store_id=? AND shopkeeper=1 AND status=1",owner.store()));assertEquals(0,db.count("SELECT version FROM li_p0_clerk_control WHERE clerk_id=?",ownerClerk));
    }
    @Test void m02InvitationRaceAndRoleReplacementHaveOneWinnerWithoutUnion()throws Exception{
        var owner=ownerFixture();var role=roles(owner);var a=login("S1_TEST_recipient_A_"+req());var b=login("S1_TEST_recipient_B_"+req());
        var invite=call(post(Stage1Routes.STORE+"/staff-invitations"),owner.login().session(),map("requestId",req(),"roleIds",List.of(role.get("READ_ONLY")),"reason","S1_TEST同邀请竞争"),200);String id=invite.get("id").getAsString(),token=invite.get("invitationToken").getAsString();
        // Both authentic recipients may attempt an unused invitation; a loser receives
        // a hidden-object response once the first recipient owns it.
        var begin=new java.util.concurrent.CountDownLatch(1);try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)){
            var results=new ArrayList<java.util.concurrent.Future<Integer>>();for(var person:List.of(a,b))results.add(pool.submit(()->{begin.await();return mvc.perform(post(Stage1Routes.APPLICANT+"/invitations/accept").header("accessToken",person.session().accessToken()).contentType("application/json").content(write(map("requestId",req(),"expectedVersion",0,"token",token)))).andReturn().getResponse().getStatus();}));begin.countDown();var statuses=List.of(results.get(0).get(),results.get(1).get());assertEquals(1,statuses.stream().filter(v->v==200).count());assertTrue(statuses.contains(404));
        }
        var command=map("requestId",req(),"expectedVersion",1,"reason","S1_TEST复核一个人");var first=call(post(Stage1Routes.STORE+"/staff-invitations/"+id+"/confirm"),owner.login().session(),command,200);assertEquals(first,call(post(Stage1Routes.STORE+"/staff-invitations/"+id+"/confirm"),owner.login().session(),command,200));
        String clerk=id(db.required("SELECT id FROM li_clerk WHERE store_id=? AND shopkeeper=0",owner.store()),"id");
        race(new Command("PUT",Stage1Routes.STORE+"/staff/"+clerk+"/roles",owner.login().session(),map("requestId",req(),"expectedVersion",0,"roleIds",List.of(role.get("INVENTORY")),"reason","S1_TEST集合一")),new Command("PUT",Stage1Routes.STORE+"/staff/"+clerk+"/roles",login(owner.open()).session(),map("requestId",req(),"expectedVersion",0,"roleIds",List.of(role.get("GOODS_OPERATOR")),"reason","S1_TEST集合二")));
        assertEquals(1,db.count("SELECT COUNT(*) FROM li_clerk_role WHERE clerk_id=?",clerk));assertEquals(0,db.count("SELECT COUNT(*) FROM li_clerk_role WHERE clerk_id=? AND role_id=?",clerk,role.get("READ_ONLY")));assertEquals(2,db.count("SELECT COUNT(*) FROM li_p0_staff_grant_history WHERE clerk_id=?",clerk));
    }
    @Test void m02RepeatedSameStoreInvitationCannotExpandRightsOrMoveAnotherOwner()throws Exception{
        var owner=ownerFixture();var other=ownerFixture();var role=roles(owner);var worker=employee(owner,List.of(role.get("READ_ONLY")));
        var invite=call(post(Stage1Routes.STORE+"/staff-invitations"),owner.login().session(),map("requestId",req(),"roleIds",List.of(role.get("COST_EDITOR")),"reason","S1_TEST重邀不自动扩权"),200);String id=invite.get("id").getAsString();
        var body=map("requestId",req(),"expectedVersion",0,"token",invite.get("invitationToken").getAsString());assertEquals("ALREADY_JOINED",call(post(Stage1Routes.STORE+"/invitations/accept"),worker.login().session(),body,200).get("status").getAsString());
        call(post(Stage1Routes.STORE+"/staff-invitations/"+id+"/confirm"),owner.login().session(),map("requestId",req(),"expectedVersion",0,"reason","S1_TEST仍不能凭邀请激活"),409);
        call(post(Stage1Routes.STORE+"/invitations/accept"),other.login().session(),map("requestId",req(),"expectedVersion",0,"token",invite.get("invitationToken").getAsString()),409);
        assertEquals(1,db.count("SELECT COUNT(*) FROM li_clerk_role WHERE clerk_id=? AND role_id=?",worker.clerk(),role.get("READ_ONLY")));assertEquals(0,db.count("SELECT COUNT(*) FROM li_clerk_role WHERE clerk_id=? AND role_id=?",worker.clerk(),role.get("COST_EDITOR")));assertEquals(1,db.count("SELECT COUNT(*) FROM li_clerk WHERE member_id=?",worker.login().memberId()));
    }
}
