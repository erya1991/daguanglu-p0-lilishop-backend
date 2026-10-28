package cn.lili.common.security.session;

import cn.lili.modules.community.p0.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.common.security.session.Stage1AuthorizationMatrixTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Failure/barrier hooks exist only on this test DataSource, never on an application bean. */
@EnabledIfSystemProperty(named="s1.integration",matches="owned-run")
@Timeout(value=5,unit=TimeUnit.MINUTES)
class Stage1AtomicityTest {
    @BeforeAll static void start()throws Exception{initialize();}
    @AfterAll static void end()throws Exception{finish();}
    @BeforeEach void refresh(){new Stage1PersistenceTest().freshManagerSessions();}
    @AfterEach void clear(){sqlObserver.remove();}
    static AtomicInteger failAt(String sqlPrefix){var hits=new AtomicInteger();sqlObserver.set(sql->{if(sql.startsWith(sqlPrefix)){hits.incrementAndGet();throw new java.sql.SQLTransientConnectionException("S1_TEST pre-commit storage interruption","08006");}});return hits;}
    @Test void admissionAndInvitationRollbackEveryNativeGrantWhenReceiptCannotCommit()throws Exception{
        var applicant=login("S1_TEST_atomic_"+req());String draft=call(post(Stage1Routes.APPLICANT+"/application"),applicant.session(),map("requestId",req(),"payload",payload(upload(applicant.session(),Stage1Routes.APPLICANT))),200).get("id").getAsString();String store=call(post(Stage1Routes.APPLICANT+"/application/"+draft+"/submit"),applicant.session(),map("requestId",req(),"expectedVersion",0),200).get("storeId").getAsString();
        var command=map("requestId",req(),"expectedVersion",1,"revision",1);var before=Stage1SupplyMatrixTest.factCounts();var hits=failAt("INSERT INTO li_p0_operation");
        call(post(Stage1Routes.MANAGER+"/applications/"+draft+"/approve"),reviewer,command,503);sqlObserver.remove();assertEquals(1,hits.get());assertEquals(before,Stage1SupplyMatrixTest.factCounts());assertEquals(0,db.count("SELECT COUNT(*) FROM li_clerk WHERE store_id=?",store));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_grant_evidence WHERE store_id=?",store));assertEquals("SUBMITTED",db.required("SELECT status FROM li_p0_application WHERE id=?",draft).get("status"));
        call(post(Stage1Routes.MANAGER+"/applications/"+draft+"/approve"),reviewer,command,200);assertEquals(1,db.count("SELECT COUNT(*) FROM li_clerk WHERE store_id=? AND shopkeeper=1",store));
        var owner=ownerFixture();var pending=login("S1_TEST_atomic_invite_"+req());String role=roles(owner).get("INVENTORY");var invitation=call(post(Stage1Routes.STORE+"/staff-invitations"),owner.login().session(),map("requestId",req(),"roleIds",List.of(role),"reason","S1_TEST原子确认"),200);String id=invitation.get("id").getAsString();call(post(Stage1Routes.APPLICANT+"/invitations/accept"),pending.session(),map("requestId",req(),"expectedVersion",0,"token",invitation.get("invitationToken").getAsString()),200);
        var confirm=map("requestId",req(),"expectedVersion",1,"reason","S1_TEST故障确认");before=Stage1SupplyMatrixTest.factCounts();hits=failAt("INSERT INTO li_p0_operation");call(post(Stage1Routes.STORE+"/staff-invitations/"+id+"/confirm"),owner.login().session(),confirm,503);sqlObserver.remove();assertEquals(1,hits.get());assertEquals(before,Stage1SupplyMatrixTest.factCounts());assertEquals(0,db.count("SELECT COUNT(*) FROM li_clerk WHERE member_id=?",pending.memberId()));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_grant_evidence WHERE member_id=?",pending.memberId()));assertEquals("WAIT_OWNER_CONFIRM",db.required("SELECT status FROM li_p0_staff_invitation WHERE id=?",id).get("status"));
        call(post(Stage1Routes.STORE+"/staff-invitations/"+id+"/confirm"),owner.login().session(),confirm,200);assertEquals(1,db.count("SELECT COUNT(*) FROM li_clerk WHERE member_id=?",pending.memberId()));observations.add(map("case","Q010-atomic-grants","faultPoint","receipt insert after native changes","rollbackAndSameRequestRetry",true));
    }
    @Test void inventoryAndRuleRollbackBalanceHistoryAuditAndOutboxTogether()throws Exception{
        var owner=ownerFixture();String agreement=agreementFixture(owner,0,LocalDate.now(Stage1Rules.ZONE),null);var goods=call(post(Stage1Routes.STORE+"/goods"),owner.login().session(),map("requestId",req(),"payload",goodsPayload(unitFixture(),upload(owner.login().session(),Stage1Routes.STORE,"GOODS"),5)),200);String sku=goods.getAsJsonArray("skus").get(0).getAsJsonObject().get("id").getAsString();long version=db.count("SELECT inventory_version FROM li_p0_sku_control WHERE sku_id=?",sku);
        var change=map("requestId",req(),"expectedVersion",version,"targetQuantity",17,"reason","S1_TEST库存事务故障");var before=Stage1SupplyMatrixTest.factCounts();var hits=failAt("INSERT INTO li_p0_outbox");call(put(Stage1Routes.STORE+"/skus/"+sku+"/stock"),owner.login().session(),change,503);sqlObserver.remove();assertEquals(1,hits.get());assertEquals(before,Stage1SupplyMatrixTest.factCounts());assertEquals(5,db.count("SELECT quantity FROM li_goods_sku WHERE id=?",sku));assertEquals(version,db.count("SELECT inventory_version FROM li_p0_sku_control WHERE sku_id=?",sku));call(put(Stage1Routes.STORE+"/skus/"+sku+"/stock"),owner.login().session(),change,200);assertEquals(17,db.count("SELECT quantity FROM li_goods_sku WHERE id=?",sku));
        var terms=object(db.required("SELECT terms FROM li_p0_agreement WHERE id=?",agreement).get("terms"));var rule=call(post(Stage1Routes.MANAGER+"/commission-rules"),channel,map("requestId",req(),"storeId",owner.store(),"agreementId",agreement,"businessType","MALL","payload",map("name","S1_TEST原子规则","rateBps",0,"baseCode",terms.get("baseCode"),"roundCode",terms.get("roundCode"),"startDate",LocalDate.now(Stage1Rules.ZONE).toString(),"endDate",LocalDate.now(Stage1Rules.ZONE).plusDays(90).toString(),"commissionClause",terms.get("commissionClause"),"triggerBasis","ORDER_COMPLETED_NO_BLOCKING_AFTERSALE","executionIntent",false)),200);String id=rule.get("id").getAsString();var publish=map("requestId",req(),"expectedVersion",0);before=Stage1SupplyMatrixTest.factCounts();hits=failAt("INSERT INTO li_p0_outbox");call(post(Stage1Routes.MANAGER+"/commission-rules/"+id+"/publish"),channel,publish,503);sqlObserver.remove();assertEquals(1,hits.get());assertEquals(before,Stage1SupplyMatrixTest.factCounts());assertNull(db.required("SELECT published_at FROM li_p0_commission_rule WHERE id=?",id).get("published_at"));assertEquals(0,db.count("SELECT version FROM li_p0_commission_rule WHERE id=?",id));call(post(Stage1Routes.MANAGER+"/commission-rules/"+id+"/publish"),channel,publish,200);observations.add(map("case","Q010-atomic-stock-rule","faultPoint","outbox insert after facts and audit","rollbackAndSameRequestRetry",true));
    }
    @Test void employeeWriteAndOwnerRevocationSerializeInBothCommitOrders()throws Exception{
        for(boolean revokeFirst:List.of(false,true)){
            var owner=ownerFixture();var employee=employee(owner,List.of(roles(owner).get("INVENTORY")));var goods=call(post(Stage1Routes.STORE+"/goods"),owner.login().session(),map("requestId",req(),"payload",goodsPayload(unitFixture(),upload(owner.login().session(),Stage1Routes.STORE,"GOODS"),5)),200);String sku=goods.getAsJsonArray("skus").get(0).getAsJsonObject().get("id").getAsString();long version=db.count("SELECT inventory_version FROM li_p0_sku_control WHERE sku_id=?",sku);
            String stockRequest=req(),revokeRequest=req();var stock=map("requestId",stockRequest,"expectedVersion",version,"targetQuantity",17,"reason","S1_TEST撤权竞态库存");var revoke=map("requestId",revokeRequest,"expectedVersion",0,"reason","S1_TEST撤销员工");
            var locked=new CountDownLatch(1);var otherStarted=new CountDownLatch(1);var release=new CountDownLatch(1);var timeline=new CopyOnWriteArrayList<String>();
            java.util.function.Function<Boolean,Integer> execute=isRevoke->{try{
                if(isRevoke==revokeFirst)sqlObserver.set(sql->{if(sql.startsWith(isRevoke?"UPDATE li_p0_clerk_control SET version":"UPDATE li_p0_sku_control SET inventory_version")){sqlObserver.remove();timeline.add(isRevoke?"revoke locked":"write locked");locked.countDown();try{if(!release.await(20,TimeUnit.SECONDS))throw new java.sql.SQLException("S1_TEST barrier timeout");}catch(InterruptedException e){Thread.currentThread().interrupt();throw new java.sql.SQLException(e);}}});
                else sqlObserver.set(sql->{if(sql.startsWith("SELECT id FROM li_member WHERE id=? FOR UPDATE")){sqlObserver.remove();timeline.add(isRevoke?"revoke waiting on member lock":"write waiting on member lock");otherStarted.countDown();}});
                var request=isRevoke?post(Stage1Routes.STORE+"/staff/"+employee.clerk()+"/revoke"):put(Stage1Routes.STORE+"/skus/"+sku+"/stock");var token=isRevoke?owner.login().session():employee.login().session();int status=mvc.perform(request.header("accessToken",token.accessToken()).contentType("application/json").content(write(isRevoke?revoke:stock))).andReturn().getResponse().getStatus();timeline.add((isRevoke?"revoke":"write")+" response "+status);return status;
            }catch(Exception e){throw new RuntimeException(e);}finally{sqlObserver.remove();}};
            try(var pool=Executors.newFixedThreadPool(2)){
                var first=pool.submit(()->execute.apply(revokeFirst));assertTrue(locked.await(20,TimeUnit.SECONDS));var second=pool.submit(()->execute.apply(!revokeFirst));assertTrue(otherStarted.await(20,TimeUnit.SECONDS));release.countDown();int firstStatus=first.get(40,TimeUnit.SECONDS),secondStatus=second.get(40,TimeUnit.SECONDS);assertEquals(200,firstStatus);assertEquals(revokeFirst?403:200,secondStatus);
            }finally{release.countDown();}
            assertEquals(revokeFirst?5:17,db.count("SELECT quantity FROM li_goods_sku WHERE id=?",sku));assertEquals(revokeFirst?0:1,db.count("SELECT COUNT(*) FROM li_p0_operation WHERE request_id=?",stockRequest));assertEquals(1,db.count("SELECT revoked FROM li_p0_clerk_control WHERE clerk_id=?",employee.clerk()));call(get(Stage1Routes.STORE+"/context"),employee.login().session(),null,403);assertThrows(SessionException.class,()->sessions.refresh(employee.login().session().refreshToken(),SessionDomain.STORE,apps.application(SessionDomain.STORE)));observations.add(map("case","Q010-commit-order","revokeFirst",revokeFirst,"timeline",timeline,"finalQuantity",revokeFirst?5:17));
        }
    }
}
