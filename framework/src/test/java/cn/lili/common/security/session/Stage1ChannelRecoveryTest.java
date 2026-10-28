package cn.lili.common.security.session;
import cn.lili.modules.community.p0.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfSystemProperty(named="s1.integration",matches="owned-run")
@Timeout(value=5,unit=TimeUnit.MINUTES)
class Stage1ChannelRecoveryTest {
    @BeforeAll static void start()throws Exception{initialize();}
    @AfterAll static void end()throws Exception{finish();}
    @BeforeEach void renew(){new Stage1PersistenceTest().freshManagerSessions();}
    @Test void protectedActionsRequireCurrentPermissionVersionAndFreshQuery()throws Exception{
        var owner=ownerFixture();String config=channelConfigFixture(1000),relation=channelRelationFixture(owner,config);var current=relationCommand(relation,1,"submit");
        for(int i=0;i<2;i++)current=relationCommand(relation,current.get("version").getAsLong(),"query");
        assertEquals("NEED_USER_ACTION",current.get("applicationState").getAsString());
        String route=Stage1Routes.MANAGER+"/submerchants/"+relation+"/required-action",version=current.get("version").getAsString();
        var action=call(get(route).param("expectedVersion",version),channel,null,200);assertEquals("ADMIN_SIGNING",action.get("action").getAsString());assertTrue(action.get("url").isJsonNull());assertEquals("MOCK",action.get("mode").getAsString());
        var response=mvc.perform(get(route).param("expectedVersion",version).header("accessToken",channel.accessToken())).andReturn().getResponse();assertEquals("no-store",response.getHeader("Cache-Control"));assertEquals("no-referrer",response.getHeader("Referrer-Policy"));
        call(get(route).param("expectedVersion",version),operator,null,403);call(get(route).param("expectedVersion",version),owner.login().session(),null,403);call(get(route).param("expectedVersion","0"),channel,null,409);
        current=relationCommand(relation,Long.parseLong(version),"disable");call(get(route).param("expectedVersion",current.get("version").getAsString()),channel,null,409);
        db.update("UPDATE li_p0_channel_mock SET scenario='VERIFY_ACCOUNT' WHERE config_id=?",config);current=relationCommand(relation,current.get("version").getAsLong(),"query");action=call(get(route).param("expectedVersion",current.get("version").getAsString()),channel,null,200);assertEquals("LEGAL_VERIFICATION",action.get("action").getAsString());
        db.update("UPDATE li_p0_channel_operation SET completed_at=? WHERE id=?",clock.instant().minusSeconds(901),action.get("sourceOperationId").getAsString());call(get(route).param("expectedVersion",current.get("version").getAsString()),channel,null,409);
        assertTrue(db.count("SELECT COUNT(*) FROM li_p0_audit WHERE object_id=? AND action='submerchant.action.view'",relation)>=3);
    }
    @Test void interruptedDispatchRetainsApplicationAndRecoversOnlyByQuery()throws Exception{
        var owner=ownerFixture();String config=channelConfigFixture(1000),relation=channelRelationFixture(owner,config);var current=relationCommand(relation,1,"submit");String original=current.get("outRequestNo").getAsString();
        String actor=sessions.verifyAccess(channel.accessToken(),SessionDomain.MANAGER,apps.application(SessionDomain.MANAGER)).identity().subject();var op=interrupted(relation,actor);
        channels.recoverInterrupted();assertEquals("UNKNOWN",db.required("SELECT status FROM li_p0_channel_operation WHERE id=?",op.get("id")).get("status"));
        var row=db.required("SELECT version,last_sync_state FROM li_p0_submerchant_relation WHERE id=?",relation);assertEquals("UNKNOWN",row.get("last_sync_state"));
        call(post(Stage1Routes.MANAGER+"/submerchants/"+relation+"/submit"),channel,map("requestId",req(),"expectedVersion",number(row,"version"),"reason","S1_TEST不能盲重提"),409);
        current=relationCommand(relation,number(row,"version"),"query");assertEquals(original,current.get("outRequestNo").getAsString());assertEquals("SUCCESS",current.get("lastSyncState").getAsString());assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_channel_mock WHERE config_id=?",config));
    }
    @Test void permissionRevocationSerializesWithChannelResultCommit()throws Exception{
        var owner=ownerFixture();String config=channelConfigFixture(1000),relation=channelRelationFixture(owner,config);var current=relationCommand(relation,1,"submit");var restricted=manager("710000000000000003");String actor=sessions.verifyAccess(restricted.accessToken(),SessionDomain.MANAGER,apps.application(SessionDomain.MANAGER)).identity().subject();var op=interrupted(relation,actor);
        var app=db.required("SELECT * FROM li_p0_channel_application WHERE id=?",current.get("applicationId").getAsString());
        try(var connection=pool.getConnection();var executor=Executors.newSingleThreadExecutor()){
            connection.setAutoCommit(false);try(var statement=connection.prepareStatement("SELECT id FROM li_admin_user WHERE id=? FOR UPDATE")){statement.setString(1,actor);assertTrue(statement.executeQuery().next());}
            var started=new CountDownLatch(1);var finish=executor.submit(()->{started.countDown();ReflectionTestUtils.invokeMethod(channels,"finish",op,object(map("applyment_id",id(app,"applyment_id"),"out_request_no",app.get("out_request_no"),"applyment_state","FINISH","sub_mchid","MOCK_SUB_123456789")),map("verificationState","VERIFIED","sourceMode","MOCK"),null,null);});
            assertTrue(started.await(5,TimeUnit.SECONDS));assertThrows(TimeoutException.class,()->finish.get(250,TimeUnit.MILLISECONDS));
            try(var statement=connection.prepareStatement("UPDATE li_admin_user SET status=0 WHERE id=?")){statement.setString(1,actor);assertEquals(1,statement.executeUpdate());}connection.commit();finish.get(15,TimeUnit.SECONDS);
        }
        assertEquals("STALE",db.required("SELECT status FROM li_p0_channel_operation WHERE id=?",op.get("id")).get("status"));assertNotEquals("VERIFIED",db.required("SELECT capability_state FROM li_p0_submerchant_relation WHERE id=?",relation).get("capability_state"));
    }
    @Test void duplicateAndDelayedResultsCannotOverrideDisableNewConfigurationOrNewerFailure()throws Exception{
        for(String change:List.of("disable","configuration","newer-failure")){
            var owner=ownerFixture();String config=channelConfigFixture(1000),relation=channelRelationFixture(owner,config);var current=relationCommand(relation,1,"submit");String actor=sessions.verifyAccess(channel.accessToken(),SessionDomain.MANAGER,apps.application(SessionDomain.MANAGER)).identity().subject();var old=interrupted(relation,actor);var application=db.required("SELECT * FROM li_p0_channel_application WHERE id=?",current.get("applicationId").getAsString());
            if(change.equals("disable"))relationCommand(relation,number(old,"relation_version"),"disable");
            else if(change.equals("configuration"))call(post(Stage1Routes.MANAGER+"/channel-configs/"+config+"/disable"),channel,map("requestId",req(),"expectedVersion",db.count("SELECT config_version FROM li_p0_channel_config WHERE id=?",config),"reason","S1_TEST旧成功不能恢复配置"),200);
            else{var newer=interrupted(relation,actor);ReflectionTestUtils.invokeMethod(channels,"finish",newer,null,null,null,new cn.lili.modules.community.p0.channel.ChannelFailure("CHANNEL_AUTH_REJECTED",false,false));}
            var before=db.required("SELECT version,enabled,capability_state,last_sync_state,accepted_seq FROM li_p0_submerchant_relation WHERE id=?",relation);var response=object(map("applyment_id",id(application,"applyment_id"),"out_request_no",application.get("out_request_no"),"applyment_state","FINISH","sub_mchid","MOCK_SUB_123456789"));
            for(int i=0;i<2;i++)ReflectionTestUtils.invokeMethod(channels,"finish",old,response,map("verificationState","VERIFIED","sourceMode","MOCK"),null,null);
            assertEquals("STALE",db.required("SELECT status FROM li_p0_channel_operation WHERE id=?",old.get("id")).get("status"));assertEquals(before,db.required("SELECT version,enabled,capability_state,last_sync_state,accepted_seq FROM li_p0_submerchant_relation WHERE id=?",relation));assertFalse((Boolean)eligibility.evaluate(owner.store(),category,null).get("MOCK_READY"));
        }
    }

    static Map<String,Object> interrupted(String relation,String actor){
        var row=db.required("SELECT * FROM li_p0_submerchant_relation WHERE id=?",relation);long seq=number(row,"request_seq")+1;String operation=db.id();db.update("UPDATE li_p0_submerchant_relation SET request_seq=?,version=version+1,last_sync_state='UNKNOWN',capability_state='UNVERIFIED' WHERE id=?",seq,relation);
        db.update("INSERT INTO li_p0_channel_operation(id,application_id,action,request_hash,request_seq,config_version,status,object_type,object_id,actor_id,request_id,relation_version,material_revision,created_at) VALUES(?,?,'QUERY',?,?,?,'DISPATCHING','APPLICATION',?,?,?,?,1,?)",operation,row.get("application_id"),hash(map("fixture","S1_TEST_INTERRUPTION")),seq,row.get("config_version"),relation,actor,req(),number(row,"version")+1,clock.instant().minusSeconds(125));return db.required("SELECT * FROM li_p0_channel_operation WHERE id=?",operation);
    }
}
