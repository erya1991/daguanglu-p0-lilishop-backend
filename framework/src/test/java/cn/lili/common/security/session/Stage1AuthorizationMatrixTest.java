package cn.lili.common.security.session;

import cn.lili.modules.community.p0.support.*;
import cn.lili.modules.community.p0.staff.StaffTemplates;
import cn.lili.modules.connect.wechat.MerchantWechatLoginService;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfSystemProperty(named="s1.integration",matches="owned-run")
@Timeout(value=8,unit=java.util.concurrent.TimeUnit.MINUTES)
class Stage1AuthorizationMatrixTest {
    @BeforeAll static void start()throws Exception{initialize();}
    @AfterAll static void end()throws Exception{finish();}
    @BeforeEach void refresh(){new Stage1PersistenceTest().freshManagerSessions();}
    record Employee(String open,String clerk,MerchantWechatLoginService.LoginResult login) { }
    static Map<String,String> roles(OwnerFixture owner)throws Exception{
        var result=new TreeMap<String,String>();
        for(var row:call(get(Stage1Routes.STORE+"/staff-role-options"),owner.login().session(),null,200).getAsJsonArray("result"))result.put(row.getAsJsonObject().get("name").getAsString(),row.getAsJsonObject().get("id").getAsString());
        return result;
    }
    static Employee employee(OwnerFixture owner,List<String> roleIds)throws Exception{
        String open="S1_TEST_staff_matrix_"+req();var applicant=login(open);
        var invited=call(post(Stage1Routes.STORE+"/staff-invitations"),owner.login().session(),map("requestId",req(),"roleIds",roleIds,"reason","S1_TEST权限矩阵邀请"),200);
        call(post(Stage1Routes.APPLICANT+"/invitations/accept"),applicant.session(),map("requestId",req(),"expectedVersion",0,"token",invited.get("invitationToken").getAsString()),200);
        call(post(Stage1Routes.STORE+"/staff-invitations/"+invited.get("id").getAsString()+"/confirm"),owner.login().session(),map("requestId",req(),"expectedVersion",1,"reason","S1_TEST复核矩阵人员"),200);
        var joined=login(open);String clerk=call(get(Stage1Routes.STORE+"/context"),joined.session(),null,200).get("clerkId").getAsString();
        return new Employee(open,clerk,joined);
    }
    @Test void receiptsAndIdenticalReplaysUseCurrentFieldPermissionsAfterPersistentRoleChange()throws Exception{
        var owner=ownerFixture();var roles=roles(owner);var employee=employee(owner,List.of(roles.get("GOODS_OPERATOR"),roles.get("COST_EDITOR"),roles.get("PROFILE_MAINTAINER")));
        String createRequest=req();var body=map("requestId",createRequest,"payload",goodsPayload(unitFixture(),upload(employee.login().session(),Stage1Routes.STORE,"GOODS"),5));
        var goods=call(post(Stage1Routes.STORE+"/goods"),employee.login().session(),body,200);String sku=goods.getAsJsonArray("skus").get(0).getAsJsonObject().get("id").getAsString();assertTrue(goods.toString().contains("costCent"));
        String costRequest=req();call(put(Stage1Routes.STORE+"/skus/"+sku+"/cost"),employee.login().session(),map("requestId",costRequest,"expectedVersion",0,"costCent",12345678,"reason","S1_TEST私有成本"),200);
        String profileRequest=req();long version=db.count("SELECT version FROM li_p0_store_control WHERE store_id=?",owner.store());call(put(Stage1Routes.STORE+"/profile"),employee.login().session(),map("requestId",profileRequest,"expectedVersion",version,"contactName","S1_TEST合成联系人","phone","010-12345678","description","S1_TEST资料","businessHours","09:00-18:00"),200);
        call(put(Stage1Routes.STORE+"/staff/"+employee.clerk()+"/roles"),owner.login().session(),map("requestId",req(),"expectedVersion",0,"roleIds",List.of(roles.get("GOODS_OPERATOR")),"reason","S1_TEST撤销成本和资料授权"),200);
        call(get(Stage1Routes.STORE+"/operations/"+createRequest),employee.login().session(),null,403);
        assertThrows(SessionException.class,()->sessions.refresh(employee.login().session().refreshToken(),SessionDomain.STORE,apps.application(SessionDomain.STORE)));
        var current=login(employee.open()).session();var facts=Stage1SupplyMatrixTest.factCounts();
        call(get(Stage1Routes.STORE+"/operations/"+costRequest),current,null,403);call(get(Stage1Routes.STORE+"/operations/"+profileRequest),current,null,403);
        var receipt=call(get(Stage1Routes.STORE+"/operations/"+createRequest),current,null,200);assertEquals("COMMITTED",receipt.get("status").getAsString());assertFalse(receipt.toString().contains("costCent"));assertFalse(receipt.toString().contains("costVersion"));
        var replay=call(post(Stage1Routes.STORE+"/goods"),current,body,200);assertEquals(goods.getAsJsonObject("goods").get("id"),replay.getAsJsonObject("goods").get("id"));assertFalse(replay.toString().contains("costCent"));assertEquals(facts,Stage1SupplyMatrixTest.factCounts());
        assertTrue(id(db.required("SELECT response_json FROM li_p0_operation WHERE request_id=?",costRequest),"response_json").contains("12345678"),"Historical receipt is preserved, not rewritten");
        call(get(Stage1Routes.STORE+"/operations/"+createRequest),owner.login().session(),null,404);
        observations.add(map("case","Q007-receipt-downgrade","currentPermissions",true,"nestedCostsRedacted",true,"historicalFactsPreserved",true,"replayWrites",0));
    }
    @Test void everyFiniteRouteRejectsMissingAndOtherDomainSessions()throws Exception{
        var owner=ownerFixture();var applicant=login("S1_TEST_applicant_matrix_"+req());String code=req();codes.put(code,"S1_TEST_resident_matrix_"+req());var resident=residentLogin.login(code).session();
        var tokens=Map.of(SessionDomain.MANAGER,reviewer,SessionDomain.STORE,owner.login().session(),SessionDomain.APPLICANT,applicant.session(),SessionDomain.RESIDENT,resident);
        long denied=0;var before=Stage1SupplyMatrixTest.factCounts();
        for(var route:Stage1Routes.all()){
            String path=route.template().replace("{id}","99999999999999999").replace("{fileId}","99999999999999998").replace("{requestId}","s1_unknown_request");
            call(request(org.springframework.http.HttpMethod.valueOf(route.method()),path),null,null,403);denied++;
            for(var entry:tokens.entrySet())if(entry.getKey()!=route.domain()){call(request(org.springframework.http.HttpMethod.valueOf(route.method()),path),entry.getValue(),null,403);denied++;}
        }
        assertEquals(before,Stage1SupplyMatrixTest.factCounts());observations.add(map("case","Q007-four-domains-all-routes","routes",Stage1Routes.all().size(),"deniedRequests",denied,"factsUnchanged",true));
    }
    @Test void allNineNativeRoleTemplatesEnforceEveryStoreActionAndManagerRolesRemainDistinct()throws Exception{
        var owner=ownerFixture();var roleIds=roles(owner);long verified=0,denied=0;
        for(var entry:StaffTemplates.TEMPLATES.entrySet()){
            var employee=employee(owner,List.of(roleIds.get(entry.getKey())));var session=sessions.verifyAccess(employee.login().session().accessToken(),SessionDomain.STORE,apps.application(SessionDomain.STORE));var user=authorization.current(session.identity());
            var expected=new TreeSet<>(entry.getValue());expected.addAll(Set.of("summary.read","operation.read","region.read","invitation.accept"));assertEquals(expected,stage1Access.storePermissions(user));
            for(var route:Stage1Routes.all())if(route.domain()==SessionDomain.STORE){String path=route.template().replace("{id}","99999999999999999").replace("{fileId}","99999999999999998").replace("{requestId}","s1_unknown_request");
                if(expected.contains(route.action()))assertTrue(stage1Access.checkRoute(user,route.method(),path,true));
                else{assertThrows(SessionException.class,()->stage1Access.checkRoute(user,route.method(),path,true));call(request(org.springframework.http.HttpMethod.valueOf(route.method()),path),employee.login().session(),null,403);denied++;}verified++;
            }
            call(put(Stage1Routes.STORE+"/profile").param("storeId",owner.store()),employee.login().session(),map("requestId",req()),403);
        }
        var distinct=new HashSet<Set<String>>();for(var token:List.of(reviewer,operator,channel)){
            var session=sessions.verifyAccess(token.accessToken(),SessionDomain.MANAGER,apps.application(SessionDomain.MANAGER));var user=authorization.current(session.identity());var grants=stage1Access.managerPermissions(user.getId());distinct.add(grants);
            for(var route:Stage1Routes.all())if(route.domain()==SessionDomain.MANAGER){String path=route.template().replace("{id}","99999999999999999").replace("{fileId}","99999999999999998").replace("{requestId}","s1_unknown_request");boolean permitted=grants.contains(route.action())||Set.of("operation.read","file.read","file.upload","region.read").contains(route.action());if(permitted)assertTrue(stage1Access.checkRoute(user,route.method(),path,true));else{call(request(org.springframework.http.HttpMethod.valueOf(route.method()),path),token,null,403);denied++;}verified++;}
        }
        assertEquals(3,distinct.size());observations.add(map("case","Q007-native-role-matrix","storeTemplates",9,"managerRoles",3,"routeDecisions",verified,"deniedHttpRequests",denied));
    }
}
