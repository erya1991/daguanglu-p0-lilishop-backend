package cn.lili.modules.community.p0.support;

import cn.lili.common.security.session.*;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.session.http.SessionApplications;
import cn.lili.modules.community.p0.member.ResidentManagerService;
import cn.lili.modules.community.p0.member.ResidentManagerController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Requires a disposable, explicitly provisioned database; never targets an existing UAT database. */
@EnabledIfEnvironmentVariable(named="DGL_RESIDENT_TEST_JDBC",matches="jdbc:mysql://127\\.0\\.0\\.1:19816/resident_test.*")
class ResidentManagerDatabaseTest {
    @Test void disableAndEnablePreserveOtherMembersAndPersistRevocationAndAudit(){
        var source=new DriverManagerDataSource(System.getenv("DGL_RESIDENT_TEST_JDBC"),"root","");
        var jdbc=new JdbcTemplate(source);var db=new Stage1Db(jdbc);
        var actor=new Stage1Context.Actor(new SessionIdentity("900001",SessionDomain.MANAGER,"manager",null,null),null,"POST","/manager/p0/s1/residents/900010/disable");
        var context=mock(Stage1Context.class);when(context.current()).thenReturn(actor);when(context.require(SessionDomain.MANAGER)).thenReturn(actor);
        var clock=Clock.fixed(Instant.parse("2026-11-12T07:00:00Z"),ZoneOffset.UTC);
        var mutations=new Stage1Mutations(db,context,new DataSourceTransactionManager(source),clock);
        var apps=new SessionApplications();apps.setResidentApplication("resident-app");apps.setMerchantApplication("merchant-app");
        var service=new ResidentManagerService(db,context,mutations,apps,clock);
        Map<?,?> before=(Map<?,?>)service.impact("900010");assertEquals(true,before.get("residentIdentity"));assertEquals(true,before.get("merchantIdentity"));
        Map<?,?> disabled=(Map<?,?>)service.change("900010",Map.of("requestId","disable01","version",before.get("version"),"reason","测试停用"),false);
        assertEquals("DISABLED",disabled.get("status"));assertEquals(0,jdbc.queryForObject("SELECT disabled+0 FROM li_member WHERE id=900010",Integer.class));
        assertEquals(1,jdbc.queryForObject("SELECT disabled+0 FROM li_member WHERE id=900011",Integer.class));
        assertEquals(clock.millis(),jdbc.queryForObject("SELECT session_cutoff FROM li_p0_member_security WHERE member_id=900010",Long.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM li_wechat_identity WHERE member_id=900010",Integer.class));
        assertThrows(Stage1Exception.class,()->service.change("900010",Map.of("requestId","disable02","version",before.get("version"),"reason","重复测试"),false));
        Map<?,?> after=(Map<?,?>)service.impact("900010");service.change("900010",Map.of("requestId","enable001","version",after.get("version"),"reason","测试启用"),true);
        assertEquals(1,jdbc.queryForObject("SELECT disabled+0 FROM li_member WHERE id=900010",Integer.class));
        assertEquals(clock.millis(),jdbc.queryForObject("SELECT session_cutoff FROM li_p0_member_security WHERE member_id=900010",Long.class));
        assertEquals(2,jdbc.queryForObject("SELECT COUNT(*) FROM li_p0_audit WHERE object_id=900010 AND action IN ('resident.disable','resident.enable')",Integer.class));
    }
    @Test void concurrentDisableUsesMemberLockAndExactlyOneResult() throws Exception {
        var source=new DriverManagerDataSource(System.getenv("DGL_RESIDENT_TEST_JDBC"),"root","");
        var jdbc=new JdbcTemplate(source);var db=new Stage1Db(jdbc);
        var actor=new Stage1Context.Actor(new SessionIdentity("900001",SessionDomain.MANAGER,"manager",null,null),null,"POST","/manager/p0/s1/residents/900011/disable");
        var context=mock(Stage1Context.class);when(context.current()).thenReturn(actor);when(context.require(SessionDomain.MANAGER)).thenReturn(actor);
        var clock=Clock.fixed(Instant.parse("2026-11-12T07:00:00Z"),ZoneOffset.UTC);
        var mutations=new Stage1Mutations(db,context,new DataSourceTransactionManager(source),clock);
        var apps=new SessionApplications();apps.setResidentApplication("resident-app");apps.setMerchantApplication("merchant-app");
        var service=new ResidentManagerService(db,context,mutations,apps,clock);
        Object version=((Map<?,?>)service.impact("900011")).get("version");
        var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
        try{
            List<Future<Boolean>> futures=new ArrayList<>();
            for(String request:List.of("concur001","concur002"))futures.add(pool.submit(()->{start.await();try{service.change("900011",Map.of("requestId",request,"version",version,"reason","并发测试"),false);return true;}catch(Stage1Exception expected){return false;}}));
            start.countDown();assertEquals(1,futures.stream().filter(f->{try{return f.get(10,TimeUnit.SECONDS);}catch(Exception e){throw new RuntimeException(e);}}).count());
            assertEquals(0,jdbc.queryForObject("SELECT disabled+0 FROM li_member WHERE id=900011",Integer.class));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM li_p0_audit WHERE object_id=900011 AND action='resident.disable'",Integer.class));
        }finally{pool.shutdownNow();}
    }
    @Test void memberDisableWaitsForInFlightGuardedBusinessTransaction() throws Exception {
        var source=new DriverManagerDataSource(System.getenv("DGL_RESIDENT_TEST_JDBC"),"root","");var jdbc=new JdbcTemplate(source);var db=new Stage1Db(jdbc);
        var actor=new Stage1Context.Actor(new SessionIdentity("900001",SessionDomain.MANAGER,"manager",null,null),null,"POST","/manager/p0/s1/residents/900012/disable");
        var context=mock(Stage1Context.class);when(context.current()).thenReturn(actor);when(context.require(SessionDomain.MANAGER)).thenReturn(actor);
        var clock=Clock.fixed(Instant.parse("2026-11-12T07:00:00Z"),ZoneOffset.UTC);
        var service=new ResidentManagerService(db,context,new Stage1Mutations(db,context,new DataSourceTransactionManager(source),clock),apps(),clock);
        Object version=((Map<?,?>)service.impact("900012")).get("version");var pool=Executors.newSingleThreadExecutor();
        try(var connection=source.getConnection()){
            connection.setAutoCommit(false);try(var statement=connection.prepareStatement("SELECT id FROM li_p0_consistency_guard WHERE id=1 FOR SHARE")){statement.executeQuery().close();}
            Future<?> task=pool.submit(()->service.change("900012",Map.of("requestId","guard0001","version",version,"reason","业务并发"),false));
            assertThrows(TimeoutException.class,()->task.get(400,TimeUnit.MILLISECONDS));
            connection.commit();assertNotNull(task.get(10,TimeUnit.SECONDS));
            assertEquals(0,jdbc.queryForObject("SELECT disabled+0 FROM li_member WHERE id=900012",Integer.class));
        }finally{pool.shutdownNow();}
    }
    @Test void searchUsesOnlyResidentApplicationAndKeepsPendingAndPhoneMasked(){
        var source=new DriverManagerDataSource(System.getenv("DGL_RESIDENT_TEST_JDBC"),"root","");var db=new Stage1Db(new JdbcTemplate(source));
        var actor=new Stage1Context.Actor(new SessionIdentity("900001",SessionDomain.MANAGER,"manager",null,null),null,"POST","/manager/p0/s1/residents/search");
        var context=mock(Stage1Context.class);when(context.require(SessionDomain.MANAGER)).thenReturn(actor);
        var service=new ResidentManagerService(db,context,mock(Stage1Mutations.class),apps(),Clock.systemUTC());
        Map<?,?> defaults=(Map<?,?>)service.search(Map.of("pageNumber",1));
        assertEquals(20L,defaults.get("pageSize"));assertTrue((Long)defaults.get("total")>=2);
        assertThrows(Stage1Exception.class,()->service.search(Map.of("pageNumber",1,"pageSize",101)));
        Map<?,?> absent=(Map<?,?>)service.search(Map.of("memberId","900013","pageNumber",1,"pageSize",20));assertEquals(0L,absent.get("total"));
        Map<?,?> pending=(Map<?,?>)service.search(Map.of("memberId","900014","pageNumber",1,"pageSize",20));
        assertEquals(1L,pending.get("total"));assertEquals("PENDING",((Map<?,?>)((List<?>)pending.get("records")).getFirst()).get("bindingStatus"));
        Map<?,?> phone=(Map<?,?>)service.search(Map.of("mobile","13812345678","pageNumber",1,"pageSize",20));
        assertEquals(1L,phone.get("total"));var row=(Map<?,?>)((List<?>)phone.get("records")).getFirst();assertEquals("138****5678",row.get("mobileMasked"));assertFalse(row.containsKey("mobile"));
    }
    @Test void mvcSearchProjectsDatabaseAndRejectsUnauthorizedContext() throws Exception {
        var source=new DriverManagerDataSource(System.getenv("DGL_RESIDENT_TEST_JDBC"),"root","");var db=new Stage1Db(new JdbcTemplate(source));
        var actor=new Stage1Context.Actor(new SessionIdentity("900001",SessionDomain.MANAGER,"manager",null,null),null,"POST","/manager/p0/s1/residents/search");
        var context=mock(Stage1Context.class);when(context.require(SessionDomain.MANAGER)).thenReturn(actor);
        var service=new ResidentManagerService(db,context,mock(Stage1Mutations.class),apps(),Clock.systemUTC());
        var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new ResidentManagerController(service)).setControllerAdvice(new Stage1Responses()).build();
        mvc.perform(post("/manager/p0/s1/residents/search").contentType("application/json").content("{\"pageNumber\":1}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.pageSize").value(20))
                .andExpect(jsonPath("$.result.total").isNumber());
        mvc.perform(post("/manager/p0/s1/residents/search").contentType("application/json").content("{\"mobile\":\"13812345678\",\"pageNumber\":1,\"pageSize\":20}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.result.total").value(1))
                .andExpect(jsonPath("$.result.records[0].mobileMasked").value("138****5678"))
                .andExpect(jsonPath("$.result.records[0].mobile").doesNotExist());
        when(context.require(SessionDomain.MANAGER)).thenThrow(new Stage1Exception(403,"FORBIDDEN"));
        mvc.perform(post("/manager/p0/s1/residents/search").contentType("application/json").content("{\"pageNumber\":1,\"pageSize\":20}"))
                .andExpect(status().isForbidden());
    }
    @Test void merchantAssociationRequiresRelatedGrantsAndRechecksThem(){
        var source=new DriverManagerDataSource(System.getenv("DGL_RESIDENT_TEST_JDBC"),"root","");var db=new Stage1Db(new JdbcTemplate(source));
        var actor=new Stage1Context.Actor(new SessionIdentity("900001",SessionDomain.MANAGER,"manager",null,null),null,"POST","/manager/p0/s1/residents/search");
        var context=mock(Stage1Context.class);when(context.require(SessionDomain.MANAGER)).thenReturn(actor);
        var service=new ResidentManagerService(db,context,mock(Stage1Mutations.class),apps(),Clock.systemUTC());
        var access=mock(Stage1Access.class);org.springframework.test.util.ReflectionTestUtils.setField(service,"access",access);
        when(access.managerPermissions("900001")).thenReturn(Set.of());
        var request=Map.of("memberId","900010","pageNumber",1,"pageSize",20);
        var rows=(List<?>)((Map<?,?>)service.search(request)).get("records");
        assertFalse(((Map<?,?>)rows.getFirst()).containsKey("merchantAssociation"));
        when(access.managerPermissions("900001")).thenReturn(Set.of("store.read"));
        rows=(List<?>)((Map<?,?>)service.search(request)).get("records");
        assertEquals("UNVERIFIED",((Map<?,?>)rows.getFirst()).get("merchantAssociation"));
        when(access.managerPermissions("900001")).thenReturn(Set.of("store.read","admission.review"));
        rows=(List<?>)((Map<?,?>)service.search(request)).get("records");
        assertEquals("NONE",((Map<?,?>)rows.getFirst()).get("merchantAssociation"));
        when(access.managerPermissions("900001")).thenReturn(Set.of("store.read"),Set.of());
        assertEquals(403,assertThrows(Stage1Exception.class,()->service.search(request)).status());
    }
    @Test void changedBindingIsRejectedEvenWhenOuterTransactionHasOlderSnapshot() throws Exception {
        var source=new DriverManagerDataSource(System.getenv("DGL_RESIDENT_TEST_JDBC"),"root","");
        var jdbc=new JdbcTemplate(source);var db=new Stage1Db(jdbc);var manager=new DataSourceTransactionManager(source);
        var actor=new Stage1Context.Actor(new SessionIdentity("900001",SessionDomain.MANAGER,"manager",null,null),null,"POST","/manager/p0/s1/residents/900014/disable");
        var context=mock(Stage1Context.class);when(context.current()).thenReturn(actor);when(context.require(SessionDomain.MANAGER)).thenReturn(actor);
        var clock=Clock.fixed(Instant.parse("2026-11-12T07:00:00Z"),ZoneOffset.UTC);
        var service=new ResidentManagerService(db,context,new Stage1Mutations(db,context,manager,clock),apps(),clock);
        Object version=((Map<?,?>)service.impact("900014")).get("version");var pool=Executors.newSingleThreadExecutor();
        try{
            var failure=assertThrows(Stage1Exception.class,()->new org.springframework.transaction.support.TransactionTemplate(manager).execute(status->{
                jdbc.queryForObject("SELECT COUNT(*) FROM li_wechat_identity",Long.class); // Establish an earlier repeatable-read snapshot.
                try{pool.submit(()->jdbc.update("UPDATE li_wechat_identity SET binding_version=binding_version+1 WHERE id=900025")).get(10,TimeUnit.SECONDS);}
                catch(Exception e){throw new IllegalStateException(e);}
                return service.change("900014",Map.of("requestId","stale0001","version",version,"reason","并发绑定变更"),false);
            }));
            assertEquals("VERSION_CONFLICT",failure.code());
            assertEquals(1,jdbc.queryForObject("SELECT disabled+0 FROM li_member WHERE id=900014",Integer.class));
            assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM li_p0_audit WHERE object_id=900014 AND action='resident.disable'",Integer.class));
        }finally{pool.shutdownNow();}
    }
    @Test void nativeRoleChangesConvergeWithoutImplicitWritePermission(){
        var source=new DriverManagerDataSource(System.getenv("DGL_RESIDENT_TEST_JDBC"),"root","");var jdbc=new JdbcTemplate(source);
        var access=new Stage1Access(new Stage1Db(jdbc),new Stage1Settings(),mock(cn.lili.modules.member.mapper.MerchantGrantMapper.class));
        org.springframework.test.util.ReflectionTestUtils.setField(access,"nativeManager",mock(cn.lili.modules.permission.manager.NativeManagerAccess.class));
        var manager=AuthUser.builder().id("900003").role(UserEnums.MANAGER).build();
        assertTrue(access.checkRoute(manager,"POST","/manager/p0/s1/residents/search",true));
        assertThrows(SessionException.class,()->access.checkRoute(manager,"POST","/manager/p0/s1/residents/900010/disable",true));
        assertEquals(1,jdbc.update("UPDATE li_role_menu SET is_super=1 WHERE id=900034 AND delete_flag=0"));
        assertTrue(access.checkRoute(manager,"POST","/manager/p0/s1/residents/900010/disable",true));
        assertEquals(1,jdbc.update("UPDATE li_role_menu SET delete_flag=1 WHERE id=900033"));
        assertThrows(SessionException.class,()->access.checkRoute(manager,"POST","/manager/p0/s1/residents/search",true));
    }
    private static SessionApplications apps(){var result=new SessionApplications();result.setResidentApplication("resident-app");result.setMerchantApplication("merchant-app");return result;}
}
