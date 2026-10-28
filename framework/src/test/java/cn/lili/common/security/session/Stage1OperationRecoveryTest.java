package cn.lili.common.security.session;

import cn.lili.modules.community.p0.support.Stage1Routes;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.*;
import java.util.concurrent.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfSystemProperty(named="s1.integration",matches="owned-run")
@Timeout(value=5,unit=TimeUnit.MINUTES)
class Stage1OperationRecoveryTest {
    @BeforeAll static void start()throws Exception{initialize();}
    @AfterAll static void end()throws Exception{finish();}
    @Test void tombstoneRejectsLateOriginalAndIsIsolatedByActor()throws Exception{
        var person=login("receipt_"+req());String id=req();var body=map("requestId",id,"payload",Map.of());
        var closed=call(post(Stage1Routes.APPLICANT+"/operations/"+id+"/abandon"),person.session(),map("requestId",id),200);assertEquals("ABANDONED",closed.get("status").getAsString());
        assertEquals("OPERATION_ABANDONED",call(post(Stage1Routes.APPLICANT+"/application"),person.session(),body,409).get("code").getAsString());
        assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_application WHERE applicant_id=?",person.memberId()));
        assertEquals("ABANDONED",call(get(Stage1Routes.APPLICANT+"/operations/"+id),person.session(),null,200).get("status").getAsString());
        var other=login("receipt_other_"+req());call(get(Stage1Routes.APPLICANT+"/operations/"+id),other.session(),null,404);
        assertNotNull(call(post(Stage1Routes.APPLICANT+"/application"),other.session(),body,200).get("id"));
    }
    @Test void committedResultWinsOverClosureAndIdenticalReplay()throws Exception{
        var person=login("receipt_committed_"+req());String id=req();var body=map("requestId",id,"payload",Map.of());
        var original=call(post(Stage1Routes.APPLICANT+"/application"),person.session(),body,200);
        var resolved=call(post(Stage1Routes.APPLICANT+"/operations/"+id+"/abandon"),person.session(),map("requestId",id),200);
        assertEquals("COMMITTED",resolved.get("status").getAsString());assertEquals(original,resolved.getAsJsonObject("result"));
        assertEquals(original,call(post(Stage1Routes.APPLICANT+"/application"),person.session(),body,200));
        assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_application WHERE applicant_id=?",person.memberId()));
    }
    @Test void simultaneousCommitAndClosureHaveOneDurableOutcome()throws Exception{
        for(int i=0;i<12;i++){
            var person=login("receipt_race_"+req());String id=req();var barrier=new CyclicBarrier(2);
            try(var executor=Executors.newFixedThreadPool(2)){
                var write=executor.submit(()->{barrier.await(10,TimeUnit.SECONDS);return mvc.perform(post(Stage1Routes.APPLICANT+"/application").header("accessToken",person.session().accessToken()).contentType("application/json").content(write(map("requestId",id,"payload",Map.of())))).andReturn().getResponse().getStatus();});
                var close=executor.submit(()->{barrier.await(10,TimeUnit.SECONDS);return call(post(Stage1Routes.APPLICANT+"/operations/"+id+"/abandon"),person.session(),map("requestId",id),200);});
                int status=write.get(30,TimeUnit.SECONDS);var result=close.get(30,TimeUnit.SECONDS);assertTrue(status==200||status==409);
                long rows=db.count("SELECT COUNT(*) FROM li_p0_application WHERE applicant_id=?",person.memberId());
                assertEquals(status==200?1:0,rows);assertEquals(status==200?"COMMITTED":"ABANDONED",result.get("status").getAsString());
                assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_operation WHERE identity_domain='APPLICANT' AND actor_id=? AND request_id=?",person.memberId(),id));
            }
        }
        observations.add(map("case","unknown-receipt-commit-closure-race","iterations",12,"outcome","Exactly one fact or tombstone"));
    }
}
