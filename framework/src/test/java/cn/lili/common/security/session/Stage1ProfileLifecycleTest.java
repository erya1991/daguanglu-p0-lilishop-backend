package cn.lili.common.security.session;

import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.*;
import java.util.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.common.security.session.Stage1AuthorizationMatrixTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfSystemProperty(named="s1.integration",matches="owned-run")
@Timeout(value=5,unit=java.util.concurrent.TimeUnit.MINUTES)
class Stage1ProfileLifecycleTest {
    @BeforeAll static void start()throws Exception{initialize();}
    @AfterAll static void end()throws Exception{finish();}
    @BeforeEach void refresh(){new Stage1PersistenceTest().freshManagerSessions();}
    @Test void ordinaryChangesAndReviewedChangesShareStoreWithoutStaleApprovalOrEntityReplacement()throws Exception{
        var owner=ownerFixture();var original=call(get(Stage1Routes.STORE+"/profile"),owner.login().session(),null,200);long version=original.get("version").getAsLong();String clerk=id(db.required("SELECT id FROM li_clerk WHERE store_id=? AND shopkeeper=1",owner.store()),"id");long qualifications=db.count("SELECT COUNT(*) FROM li_p0_qualification WHERE store_id=?",owner.store());
        var input=object(db.required("SELECT draft_payload FROM li_p0_application WHERE store_id=? AND kind='ADMISSION'",owner.store()).get("draft_payload"));input.addProperty("storeName","S1_TEST审核后名称");
        var pending=call(post(Stage1Routes.STORE+"/profile-change/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",version,"payload",input),200);String application=pending.get("id").getAsString();assertEquals(original.get("storeName"),call(get(Stage1Routes.MANAGER+"/stores/"+owner.store()),operator,null,200).get("storeName"));assertEquals(qualifications,db.count("SELECT COUNT(*) FROM li_p0_qualification WHERE store_id=?",owner.store()));
        call(post(Stage1Routes.STORE+"/profile-change/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",version+1,"payload",input),409);
        var ordinary=map("requestId",req(),"expectedVersion",version+1,"contactName","S1_TEST新联系人","phone","010-88888888","description","S1_TEST即时资料","businessHours","全天说明");var updated=call(put(Stage1Routes.STORE+"/profile"),owner.login().session(),ordinary,200);assertEquals("S1_TEST新联系人",call(get(Stage1Routes.MANAGER+"/stores/"+owner.store()),operator,null,200).get("contactName").getAsString());
        call(post(Stage1Routes.MANAGER+"/applications/"+application+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"revision",1),409);assertEquals("SUBMITTED",db.required("SELECT status FROM li_p0_application WHERE id=?",application).get("status"));
        call(post(Stage1Routes.MANAGER+"/applications/"+application+"/reject"),reviewer,map("requestId",req(),"expectedVersion",1,"revision",1,"reason","S1_TEST基础资料变化请重新提交"),200);
        input.addProperty("contactName","S1_TEST新联系人");input.addProperty("phone","010-88888888");input.addProperty("description","S1_TEST即时资料");input.addProperty("businessHours","全天说明");
        var resubmitted=call(post(Stage1Routes.STORE+"/profile-change/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",updated.get("version").getAsLong(),"payload",input),200);assertEquals(application,resubmitted.get("id").getAsString());assertEquals(2,resubmitted.get("revision").getAsInt());
        call(post(Stage1Routes.MANAGER+"/applications/"+application+"/approve"),reviewer,map("requestId",req(),"expectedVersion",resubmitted.get("version").getAsLong(),"revision",2),200);
        var profile=call(get(Stage1Routes.STORE+"/profile"),owner.login().session(),null,200);assertEquals("S1_TEST审核后名称",profile.get("storeName").getAsString());assertEquals(owner.store(),profile.get("id").getAsString());assertEquals(clerk,id(db.required("SELECT id FROM li_clerk WHERE store_id=? AND shopkeeper=1",owner.store()),"id"));assertEquals(1,db.count("SELECT COUNT(*) FROM li_store WHERE member_id=?",owner.login().memberId()));assertEquals(2,db.count("SELECT COUNT(*) FROM li_p0_application_revision WHERE application_id=?",application));
        var forged=object(payload(upload(owner.login().session(),Stage1Routes.STORE)));call(post(Stage1Routes.STORE+"/profile-change/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",profile.get("version").getAsLong(),"payload",forged),422);assertEquals(original.get("legalCode"),call(get(Stage1Routes.STORE+"/profile"),owner.login().session(),null,200).get("legalCode"));
        var other=ownerFixture();call(get(Stage1Routes.STORE+"/qualifications/"+application),other.login().session(),null,404);observations.add(map("case","W02-W03-M08-profile-review","storeStable",true,"ownerStable",true,"staleApprovalRejected",true,"twoRevisions",true,"legalEntityImmutable",true));
    }
    @Test void closedStoreAllowsCorrectionsAndShrinkingRightsButCannotExpandOrAutoRelist()throws Exception{
        var owner=ownerFixture();var roleIds=roles(owner);var employee=employee(owner,List.of(roleIds.get("GOODS_OPERATOR"),roleIds.get("INVENTORY")));agreementFixture(owner,0,LocalDate.now(Stage1Rules.ZONE),null);deliveryFixture(owner);var created=call(post(Stage1Routes.STORE+"/goods"),owner.login().session(),map("requestId",req(),"payload",goodsPayload(unitFixture(),upload(owner.login().session(),Stage1Routes.STORE,"GOODS"),5)),200);String goods=created.getAsJsonObject("goods").get("id").getAsString();call(post(Stage1Routes.STORE+"/goods/"+goods+"/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",0),200);call(post(Stage1Routes.MANAGER+"/goods/"+goods+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"contentVersion",0),200);call(post(Stage1Routes.STORE+"/goods/"+goods+"/list"),owner.login().session(),map("requestId",req(),"expectedVersion",2),200);
        long version=db.count("SELECT version FROM li_p0_store_control WHERE store_id=?",owner.store());call(post(Stage1Routes.MANAGER+"/stores/"+owner.store()+"/close"),operator,map("requestId",req(),"expectedVersion",version,"reason","S1_TEST整改原因"),200);call(get(Stage1Routes.STORE+"/workbench"),owner.login().session(),null,403);call(get(Stage1Routes.STORE+"/context"),employee.login().session(),null,403);var corrective=login(owner.open()).session();var context=call(get(Stage1Routes.STORE+"/context"),corrective,null,200);assertEquals("CLOSED",context.get("storeOperationalState").getAsString());assertEquals("S1_TEST整改原因",context.get("closedReason").getAsString());
        call(post(Stage1Routes.STORE+"/goods/"+goods+"/list"),corrective,map("requestId",req(),"expectedVersion",3),403);call(put(Stage1Routes.STORE+"/staff/"+employee.clerk()+"/roles"),corrective,map("requestId",req(),"expectedVersion",0,"roleIds",List.of(roleIds.get("GOODS_OPERATOR")),"reason","S1_TEST缩小授权"),200);call(put(Stage1Routes.STORE+"/staff/"+employee.clerk()+"/roles"),corrective,map("requestId",req(),"expectedVersion",1,"roleIds",List.of(roleIds.get("GOODS_OPERATOR"),roleIds.get("COST_EDITOR")),"reason","S1_TEST不得扩大"),403);
        var profile=call(put(Stage1Routes.STORE+"/profile"),corrective,map("requestId",req(),"expectedVersion",version+1,"contactName","S1_TEST整改联系人","phone","010-12345678","description","S1_TEST整改说明","businessHours","09:00-18:00"),200);call(post(Stage1Routes.MANAGER+"/stores/"+owner.store()+"/reopen"),operator,map("requestId",req(),"expectedVersion",profile.get("version").getAsLong(),"reason","S1_TEST恢复"),200);assertEquals("DOWN",db.required("SELECT market_enable FROM li_goods WHERE id=?",goods).get("market_enable"));assertEquals("APPROVED",db.required("SELECT review_status FROM li_p0_goods_control WHERE goods_id=?",goods).get("review_status"));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_goods_review WHERE goods_id=?",goods));call(get(Stage1Routes.STORE+"/context"),owner.login().session(),null,403);call(get(Stage1Routes.STORE+"/context"),corrective,null,200);observations.add(map("case","C11-closed-corrective","historyPreserved",true,"permissionCanShrinkOnly",true,"reopenKeepsGoodsDown",true));
    }
}
