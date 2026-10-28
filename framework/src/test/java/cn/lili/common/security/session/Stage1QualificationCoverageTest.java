package cn.lili.common.security.session;
import cn.lili.modules.community.p0.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
@EnabledIfSystemProperty(named="s1.integration",matches="owned-run")
class Stage1QualificationCoverageTest {
    @BeforeAll static void start()throws Exception{initialize();}
    @AfterAll static void end()throws Exception{finish();}
    @BeforeEach void refresh(){new Stage1PersistenceTest().freshManagerSessions();}
    @Test void w04UnavailableAgreementAttachmentImmediatelyRemovesBasicWithoutDeletingHistory()throws Exception{
        var owner=Stage1SupplyMatrixTest.ready();var before=object(eligibility.evaluate(owner.store(),category,null));assertTrue(before.get("BASIC_ELIGIBLE").getAsBoolean());
        String agreement=before.getAsJsonObject("sourceVersions").getAsJsonObject("agreement").get("id").getAsString();var row=db.required("SELECT terms,file_refs FROM li_p0_agreement WHERE id=?",agreement);String file=array(row.get("file_refs")).get(0).getAsString();
        try{
            db.update("UPDATE li_p0_file_control SET byte_size=byte_size+1 WHERE file_id=?",file);var changed=object(eligibility.evaluate(owner.store(),category,null));assertFalse(changed.get("BASIC_ELIGIBLE").getAsBoolean());assertFalse(changed.get("MOCK_READY").getAsBoolean());assertTrue(changed.get("reasons").toString().contains("AGREEMENT_ATTACHMENT_UNAVAILABLE"));assertNotEquals(before.get("sourceFingerprint"),changed.get("sourceFingerprint"));
            var goods=Stage1SupplyMatrixTest.createGoods(owner,1,List.of("MERCHANT_DELIVERY"),false);call(post(Stage1Routes.STORE+"/goods/"+goods.getAsJsonObject("goods").get("id").getAsString()+"/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",0),409);
            assertEquals(row,db.required("SELECT terms,file_refs FROM li_p0_agreement WHERE id=?",agreement));
        }finally{db.update("UPDATE li_p0_file_control SET byte_size=byte_size-1 WHERE file_id=?",file);}
        assertTrue((Boolean)eligibility.evaluate(owner.store(),category,null).get("BASIC_ELIGIBLE"));
    }
    @Test void m08RejectedRenewalDoesNotExtendOldCertificateThenApprovedNewRevisionRestoresIt()throws Exception{
        var owner=ownerFixture();agreementFixture(owner,0,LocalDate.now(Stage1Rules.ZONE),null);var old=call(get(Stage1Routes.STORE+"/qualifications"),owner.login().session(),null,200).getAsJsonArray("versions").get(0).getAsJsonObject();String oldId=old.get("id").getAsString();
        // The synthetic old certificate expires tomorrow, so the agreement stays valid
        // on both sides of the certificate boundary. Production time is never changed.
        Instant end=LocalDate.now(Stage1Rules.ZONE).plusDays(1).atStartOfDay(Stage1Rules.ZONE).toInstant();db.update("UPDATE li_p0_qualification SET valid_to=? WHERE id=?",end,oldId);
        var details=old.getAsJsonObject("details").deepCopy();details.addProperty("validFrom",end.atZone(Stage1Rules.ZONE).toLocalDate().toString());details.addProperty("validThrough",end.atZone(Stage1Rules.ZONE).toLocalDate().plusMonths(6).toString());
        String id=call(post(Stage1Routes.STORE+"/qualifications"),owner.login().session(),map("requestId",req(),"payload",map("qualifications",List.of(details))),200).get("id").getAsString();call(post(Stage1Routes.STORE+"/qualifications/"+id+"/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",0),200);
        assertTrue((Boolean)eligibility.evaluate(owner.store(),category,end.minusMillis(1)).get("BASIC_ELIGIBLE"));assertFalse((Boolean)eligibility.evaluate(owner.store(),category,end).get("BASIC_ELIGIBLE"));
        call(post(Stage1Routes.MANAGER+"/applications/"+id+"/reject"),reviewer,map("requestId",req(),"expectedVersion",1,"revision",1,"reason","S1_TEST续期材料缺页"),200);assertNull(db.required("SELECT superseded_at FROM li_p0_qualification WHERE id=?",oldId).get("superseded_at"));assertFalse((Boolean)eligibility.evaluate(owner.store(),category,end).get("BASIC_ELIGIBLE"));
        call(put(Stage1Routes.STORE+"/qualifications/"+id+"/draft"),owner.login().session(),map("requestId",req(),"expectedVersion",2,"payload",map("qualifications",List.of(details))),200);call(post(Stage1Routes.STORE+"/qualifications/"+id+"/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",3),200);call(post(Stage1Routes.MANAGER+"/applications/"+id+"/approve"),reviewer,map("requestId",req(),"expectedVersion",4,"revision",2),200);
        assertEquals("REJECTED",db.required("SELECT status FROM li_p0_application_revision WHERE application_id=? AND revision=1",id).get("status"));assertEquals(2,db.count("SELECT COUNT(*) FROM li_p0_qualification WHERE store_id=?",owner.store()));assertTrue((Boolean)eligibility.evaluate(owner.store(),category,end).get("BASIC_ELIGIBLE"));assertEquals(end,instant(db.required("SELECT valid_to FROM li_p0_qualification WHERE id=?",oldId).get("valid_to")));
    }
}
