package cn.lili.common.security.session;

import cn.lili.modules.community.p0.support.*;
import cn.lili.modules.connect.wechat.MerchantWechatLoginService;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.common.security.session.Stage1ConcurrencyTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Additional frozen-case variants, isolated to this run's synthetic identities and files. */
@EnabledIfSystemProperty(named="s1.integration",matches="owned-run")
@Timeout(value=5,unit=TimeUnit.MINUTES)
class Stage1AdmissionCoverageTest {
    @BeforeAll static void start()throws Exception{initialize();}
    @AfterAll static void end()throws Exception{finish();}
    @BeforeEach void refresh(){new Stage1PersistenceTest().freshManagerSessions();}
    @AfterEach void clear(){sqlObserver.remove();}
    record Pending(MerchantWechatLoginService.LoginResult login,String application,String store,String file,JsonObject payload){}
    static Pending pending()throws Exception{
        var person=login("S1_TEST_admission_"+req());String file=upload(person.session(),Stage1Routes.APPLICANT);var material=object(payload(file));
        var draft=call(post(Stage1Routes.APPLICANT+"/application"),person.session(),map("requestId",req(),"payload",material),200);String id=draft.get("id").getAsString();
        var submitted=call(post(Stage1Routes.APPLICANT+"/application/"+id+"/submit"),person.session(),map("requestId",req(),"expectedVersion",0),200);
        return new Pending(person,id,submitted.get("storeId").getAsString(),file,material);
    }
    static JsonObject approve(Pending p,int status)throws Exception{return call(post(Stage1Routes.MANAGER+"/applications/"+p.application()+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"revision",1),status);}
    static void unchanged(Pending p){assertEquals("SUBMITTED",db.required("SELECT status FROM li_p0_application WHERE id=?",p.application()).get("status"));assertEquals(1,db.count("SELECT version FROM li_p0_application WHERE id=?",p.application()));assertEquals(0,db.count("SELECT COUNT(*) FROM li_clerk WHERE member_id=?",p.login().memberId()));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_grant_evidence WHERE store_id=?",p.store()));}

    @Test void w02CurrentStateRevisionAndBlankRejectionCannotCreateDecisions()throws Exception{
        var person=login("S1_TEST_states_"+req());var material=object(payload(upload(person.session(),Stage1Routes.APPLICANT)));
        String id=call(post(Stage1Routes.APPLICANT+"/application"),person.session(),map("requestId",req(),"payload",material),200).get("id").getAsString();
        call(post(Stage1Routes.MANAGER+"/applications/"+id+"/approve"),reviewer,map("requestId",req(),"expectedVersion",0,"revision",1),409);
        call(post(Stage1Routes.APPLICANT+"/application/"+id+"/submit"),person.session(),map("requestId",req(),"expectedVersion",0),200);
        call(put(Stage1Routes.APPLICANT+"/application/"+id+"/draft"),person.session(),map("requestId",req(),"expectedVersion",1,"payload",material),409);
        call(post(Stage1Routes.MANAGER+"/applications/"+id+"/approve"),operator,map("requestId",req(),"expectedVersion",1,"revision",1),403);
        call(post(Stage1Routes.MANAGER+"/applications/"+id+"/reject"),reviewer,map("requestId",req(),"expectedVersion",1,"revision",1,"reason","  "),422);
        assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_audit WHERE object_id=? AND action LIKE 'application.%ject'",id));
        call(post(Stage1Routes.MANAGER+"/applications/"+id+"/reject"),reviewer,map("requestId",req(),"expectedVersion",1,"revision",1,"reason","S1_TEST营业证明缺页"),200);
        var first=db.required("SELECT payload,reason,reviewer_id,reviewed_at FROM li_p0_application_revision WHERE application_id=? AND revision=1",id);
        assertNotNull(first.get("reviewer_id"));assertNotNull(first.get("reviewed_at"));assertEquals("S1_TEST营业证明缺页",first.get("reason"));
        call(post(Stage1Routes.MANAGER+"/applications/"+id+"/approve"),reviewer,map("requestId",req(),"expectedVersion",2,"revision",1),409);
        material.addProperty("description","S1_TEST修正说明");call(put(Stage1Routes.APPLICANT+"/application/"+id+"/draft"),person.session(),map("requestId",req(),"expectedVersion",2,"payload",material),200);
        call(post(Stage1Routes.APPLICANT+"/application/"+id+"/submit"),person.session(),map("requestId",req(),"expectedVersion",3),200);
        call(post(Stage1Routes.MANAGER+"/applications/"+id+"/approve"),reviewer,map("requestId",req(),"expectedVersion",4,"revision",1),409);
        call(post(Stage1Routes.MANAGER+"/applications/"+id+"/approve"),reviewer,map("requestId",req(),"expectedVersion",4,"revision",2),200);
        call(post(Stage1Routes.MANAGER+"/applications/"+id+"/approve"),reviewer,map("requestId",req(),"expectedVersion",5,"revision",2),409);
        assertEquals(first,db.required("SELECT payload,reason,reviewer_id,reviewed_at FROM li_p0_application_revision WHERE application_id=? AND revision=1",id));
        assertEquals(2,db.count("SELECT COUNT(*) FROM li_p0_application_revision WHERE application_id=?",id));
    }

    @Test void w02ApprovalRevalidatesFileExpirySubjectAndCurrentOwnership()throws Exception{
        var other=ownerFixture();String foreignFile=upload(other.login().session(),Stage1Routes.STORE);
        for(String variant:List.of("unreadable","expired","subject-conflict","ownership-conflict","foreign-file")){
            var p=pending();var before=db.required("SELECT payload,content_hash FROM li_p0_application_revision WHERE application_id=? AND revision=1",p.application());
            var rules=ReflectionTestUtils.getField(admission,"rules");String injected=null;
            try{
                int expected=409;
                if(variant.equals("unreadable")){db.update("UPDATE li_p0_file_control SET byte_size=byte_size+1 WHERE file_id=?",p.file());expected=503;}
                if(variant.equals("expired")){ReflectionTestUtils.setField(rules,"clock",Clock.fixed(LocalDate.now(Stage1Rules.ZONE).plusYears(2).atStartOfDay(Stage1Rules.ZONE).toInstant(),ZoneOffset.UTC));expected=422;}
                if(variant.equals("subject-conflict")){var modified=p.payload().deepCopy();String code=String.valueOf(db.required("SELECT legal_code_normalized FROM li_p0_store_control WHERE store_id=?",other.store()).get("legal_code_normalized"));modified.addProperty("legalCode",code);modified.getAsJsonArray("qualifications").get(0).getAsJsonObject().addProperty("number",code);db.update("UPDATE li_p0_application_revision SET payload=? WHERE application_id=? AND revision=1",write(modified),p.application());}
                if(variant.equals("ownership-conflict")){injected=db.id();db.nativeInsert("li_clerk",map("id",injected,"create_time",clock.instant(),"delete_flag",false,"member_id",p.login().memberId(),"store_id",other.store(),"shopkeeper",false,"is_super",false,"status",true));}
                if(variant.equals("foreign-file")){var modified=p.payload().deepCopy();modified.getAsJsonArray("qualifications").get(0).getAsJsonObject().add("fileIds",new Gson().toJsonTree(List.of(foreignFile)));db.update("UPDATE li_p0_application_revision SET payload=? WHERE application_id=? AND revision=1",write(modified),p.application());expected=404;}
                var snapshot=db.required("SELECT payload,content_hash FROM li_p0_application_revision WHERE application_id=? AND revision=1",p.application());approve(p,expected);
                assertEquals(snapshot,db.required("SELECT payload,content_hash FROM li_p0_application_revision WHERE application_id=? AND revision=1",p.application()));
                assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_audit WHERE object_id=? AND action='application.approve'",p.application()));
            }finally{
                ReflectionTestUtils.setField(rules,"clock",clock);
                if(variant.equals("unreadable"))db.update("UPDATE li_p0_file_control SET byte_size=byte_size-1 WHERE file_id=?",p.file());
                if(injected!=null)db.update("DELETE FROM li_clerk WHERE id=?",injected);
                db.update("UPDATE li_p0_application_revision SET payload=?,content_hash=? WHERE application_id=? AND revision=1",before.get("payload"),before.get("content_hash"),p.application());
            }
            unchanged(p);approve(p,200);assertEquals(1,db.count("SELECT COUNT(*) FROM li_clerk WHERE member_id=? AND shopkeeper=1",p.login().memberId()));
        }
    }

    @Test void m03HundredConcurrentSubmissionsAndW02ApprovalReplayKeepOneRevisionAndOwner()throws Exception{
        var person=login("S1_TEST_100_submit_"+req());String id=call(post(Stage1Routes.APPLICANT+"/application"),person.session(),map("requestId",req(),"payload",payload(upload(person.session(),Stage1Routes.APPLICANT))),200).get("id").getAsString();
        var body=map("requestId",req(),"expectedVersion",0);var barrier=new CountDownLatch(100);var go=new CountDownLatch(1);
        try(var pool=Executors.newFixedThreadPool(100)){var futures=new ArrayList<Future<JsonObject>>();for(int i=0;i<100;i++)futures.add(pool.submit(()->{barrier.countDown();assertTrue(go.await(20,TimeUnit.SECONDS));return call(post(Stage1Routes.APPLICANT+"/application/"+id+"/submit"),person.session(),body,200);}));assertTrue(barrier.await(20,TimeUnit.SECONDS));go.countDown();JsonObject original=futures.getFirst().get(90,TimeUnit.SECONDS);for(var future:futures)assertEquals(original,future.get(90,TimeUnit.SECONDS));}
        assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_application_revision WHERE application_id=?",id));assertEquals(1,db.count("SELECT COUNT(*) FROM li_store WHERE member_id=?",person.memberId()));
        call(post(Stage1Routes.APPLICANT+"/application/"+id+"/submit"),person.session(),map("requestId",req(),"expectedVersion",0),409);
        assertEquals("COMMITTED",call(get(Stage1Routes.APPLICANT+"/operations/"+body.get("requestId")),person.session(),null,200).get("status").getAsString());
        var first=map("requestId",req(),"expectedVersion",1,"revision",1);var second=map("requestId",req(),"expectedVersion",1,"revision",1);var other=manager("710000000000000001");
        var results=race(new Command("POST",Stage1Routes.MANAGER+"/applications/"+id+"/approve",reviewer,first),new Command("POST",Stage1Routes.MANAGER+"/applications/"+id+"/approve",other,second));
        var winner=results.getFirst()==200?reviewer:other;var winningBody=results.getFirst()==200?first:second;var response=call(post(Stage1Routes.MANAGER+"/applications/"+id+"/approve"),winner,winningBody,200);
        for(int i=0;i<100;i++)assertEquals(response,call(post(Stage1Routes.MANAGER+"/applications/"+id+"/approve"),winner,winningBody,200));
        assertEquals(1,db.count("SELECT COUNT(*) FROM li_clerk WHERE member_id=? AND shopkeeper=1",person.memberId()));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_audit WHERE object_id=? AND action='application.approve'",id));
        observations.add(map("case","M03-004/W02-003","concurrentIdenticalSubmits",100,"approveReplay",100,"uniqueRevisionAndOwner",true));
    }

    @Test void w02EachNativeGrantFailureRollsBackAndOriginalRequestCanRecover()throws Exception{
        for(String sql:List.of("UPDATE li_store SET store_name", "INSERT INTO li_clerk", "UPDATE li_member SET have_store", "INSERT INTO li_p0_audit", "INSERT INTO li_p0_operation")){
            var p=pending();var command=map("requestId",req(),"expectedVersion",1,"revision",1);var before=Stage1SupplyMatrixTest.factCounts();var hits=Stage1AtomicityTest.failAt(sql);
            try{call(post(Stage1Routes.MANAGER+"/applications/"+p.application()+"/approve"),reviewer,command,503);}finally{sqlObserver.remove();}
            assertTrue(hits.get()>0,"Fault must be reached: "+sql);assertEquals(before,Stage1SupplyMatrixTest.factCounts());unchanged(p);assertEquals(0,db.count("SELECT have_store FROM li_member WHERE id=?",p.login().memberId()));
            var result=call(post(Stage1Routes.MANAGER+"/applications/"+p.application()+"/approve"),reviewer,command,200);assertEquals(result,call(get(Stage1Routes.MANAGER+"/operations/"+command.get("requestId")),reviewer,null,200).getAsJsonObject("result"));
        }
    }

    @Test void m03EveryRequiredFieldLongTermAndClosedDuplicateUseOneApplication()throws Exception{
        var person=login("S1_TEST_required_"+req());var material=object(payload(upload(person.session(),Stage1Routes.APPLICANT)));
        String id=call(post(Stage1Routes.APPLICANT+"/application"),person.session(),map("requestId",req(),"payload",Map.of()),200).get("id").getAsString();long version=0;
        for(String field:List.of("subjectType","legalName","legalCode","legalPerson","storeName","contactName","phone","provinceCode","cityCode","districtCode","address","categoryIds","qualifications")){
            var missing=material.deepCopy();missing.remove(field);
            if(Set.of("provinceCode","cityCode","districtCode").contains(field)){call(put(Stage1Routes.APPLICANT+"/application/"+id+"/draft"),person.session(),map("requestId",req(),"expectedVersion",version,"payload",missing),422);assertEquals(version,db.count("SELECT version FROM li_p0_application WHERE id=?",id));continue;}
            call(put(Stage1Routes.APPLICANT+"/application/"+id+"/draft"),person.session(),map("requestId",req(),"expectedVersion",version++,"payload",missing),200);
            call(post(Stage1Routes.APPLICANT+"/application/"+id+"/submit"),person.session(),map("requestId",req(),"expectedVersion",version),422);assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_application_revision WHERE application_id=?",id));
        }
        var longTerm=material.deepCopy();var q=longTerm.getAsJsonArray("qualifications").get(0).getAsJsonObject();q.addProperty("longTerm",true);q.remove("validThrough");
        call(put(Stage1Routes.APPLICANT+"/application/"+id+"/draft"),person.session(),map("requestId",req(),"expectedVersion",version++,"payload",longTerm),200);
        var submitted=call(post(Stage1Routes.APPLICANT+"/application/"+id+"/submit"),person.session(),map("requestId",req(),"expectedVersion",version++),200);String store=submitted.get("storeId").getAsString();call(post(Stage1Routes.MANAGER+"/applications/"+id+"/approve"),reviewer,map("requestId",req(),"expectedVersion",version,"revision",1),200);
        call(post(Stage1Routes.MANAGER+"/stores/"+store+"/close"),operator,map("requestId",req(),"expectedVersion",1,"reason","S1_TEST主体仍被占用"),200);
        var next=login("S1_TEST_duplicate_closed_"+req());var duplicate=object(payload(upload(next.session(),Stage1Routes.APPLICANT)));String code=material.get("legalCode").getAsString();duplicate.addProperty("legalCode","  "+code.toLowerCase(Locale.ROOT)+"  ");duplicate.getAsJsonArray("qualifications").get(0).getAsJsonObject().addProperty("number",code);
        String application=call(post(Stage1Routes.APPLICANT+"/application"),next.session(),map("requestId",req(),"payload",duplicate),200).get("id").getAsString();call(post(Stage1Routes.APPLICANT+"/application/"+application+"/submit"),next.session(),map("requestId",req(),"expectedVersion",0),409);
        assertEquals(0,db.count("SELECT COUNT(*) FROM li_store WHERE member_id=?",next.memberId()));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_application WHERE applicant_id=?",next.memberId()));
    }
}
