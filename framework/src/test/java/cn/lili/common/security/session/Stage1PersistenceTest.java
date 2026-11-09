package cn.lili.common.security.session;

import cn.lili.common.properties.JWTTokenProperties;
import cn.lili.common.security.session.http.*;
import cn.lili.common.security.token.SecretKeyUtil;
import cn.lili.common.utils.SnowFlake;
import cn.lili.modules.community.p0.admission.*;
import cn.lili.modules.community.p0.staff.*;
import cn.lili.modules.community.p0.agreement.*;
import cn.lili.modules.community.p0.eligibility.*;
import cn.lili.modules.community.p0.channel.*;
import cn.lili.modules.community.p0.catalog.*;
import cn.lili.modules.community.p0.fulfillment.*;
import cn.lili.modules.goods.mapper.GoodsMapper;
import cn.lili.modules.goods.mapper.GoodsSkuMapper;
import cn.lili.modules.goods.serviceimpl.GoodsServiceImpl;
import cn.lili.modules.goods.serviceimpl.GoodsSkuServiceImpl;
import cn.lili.modules.community.p0.support.*;
import cn.lili.modules.connect.mapper.*;
import cn.lili.modules.connect.wechat.*;
import cn.lili.modules.member.mapper.*;
import cn.lili.modules.member.profile.ResidentProfileProperties;
import cn.lili.modules.member.service.*;
import cn.lili.modules.member.serviceimpl.*;
import cn.lili.modules.member.merchant.*;
import cn.lili.modules.member.token.StoreTokenGenerate;
import cn.lili.modules.permission.mapper.AdminUserMapper;
import cn.lili.modules.permission.service.*;
import cn.lili.modules.permission.serviceimpl.AdminUserServiceImpl;
import cn.lili.modules.store.mapper.StoreMapper;
import cn.lili.modules.store.serviceimpl.StoreServiceImpl;
import cn.lili.modules.system.token.ManagerTokenGenerate;
import cn.lili.mybatis.mybatisplus.MyMetaObjectHandler;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.google.gson.*;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.type.JdbcType;
import org.mybatis.spring.SqlSessionTemplate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.RequestContextHolder;
import jakarta.servlet.Filter;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Owned-run only: real MySQL, MyBatis/native services, identity resolver, Redis Session and production HTTP filter.
 * This fixture does not start a server, migrate/reset data or contact WeChat. Only its Code gateway is synthetic.
 * Full deployed security-chain, browser and device evidence is recorded separately. */
@EnabledIfSystemProperty(named="s1.integration",matches="owned-run")
@Timeout(value=5,unit=java.util.concurrent.TimeUnit.MINUTES)
class Stage1PersistenceTest {
    static boolean deployedHttp=false;
    static boolean crossHostHttp=false;
    static java.net.http.HttpClient crossHostClient;
    static List<Map<String,Object>> recoveryCapture;
    static final java.net.http.HttpClient liveClient=java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();
    static Path run;static Stage1Db db;static SessionService sessions;static SessionApplications apps;
    static MerchantWechatLoginService login;static ResidentWechatLoginService residentLogin;static CurrentSessionAuthorization authorization;static MockMvc mvc;
    static LettuceConnectionFactory redisFactory;static StringRedisTemplate redis;static SecretKeyUtil keys;
    static String testId,category;static SessionTokens reviewer,operator,channel;static Clock clock=Clock.systemUTC();
    static final Map<String,String> codes=new ConcurrentHashMap<>();static final List<Object> observations=Collections.synchronizedList(new ArrayList<>());
    static Object previousSnowflake;static com.zaxxer.hikari.HikariDataSource pool;static Stage1Access stage1Access;static final ThreadLocal<Boolean> forbidSqlWrites=ThreadLocal.withInitial(()->false);static final java.util.concurrent.atomic.AtomicLong observedSqlReads=new java.util.concurrent.atomic.AtomicLong();
    @FunctionalInterface interface SqlObserver {void before(String sql)throws java.sql.SQLException;}
    static final ThreadLocal<SqlObserver> sqlObserver=new ThreadLocal<>();
    static Stage1Settings settings;
    static EligibilityService eligibility;static CommissionRuleService commissions;static ChannelService channels;static ReceiverService receivers;static StaffService staff;static AdmissionService admission;
    @BeforeAll static void initialize() throws Exception {
        run=Path.of(Objects.requireNonNull(System.getenv("S1_RUN"))).toRealPath();
        assertTrue(run.getFileName().toString().matches("S1_[0-9]{8}_[0-9]{6}"));
        assertEquals(Path.of("D:/git/daguanglu-p0/.stage1-runtime").toRealPath(),run.getParent());
        assertEquals(run.getFileName().toString(),object(Files.readString(run.resolve("owner.json"))).get("runId").getAsString());
        var resource=object(Files.readString(run.resolve("runtime/resources.json")));
        var secret=object(Files.readString(run.resolve("private/db.json")));
        pool=new com.zaxxer.hikari.HikariDataSource();pool.setJdbcUrl("jdbc:mysql://127.0.0.1:19306/dgl_p0_s1?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true");pool.setUsername("s1_app");pool.setPassword(secret.get("app").getAsString());pool.setMaximumPoolSize(24);pool.setMinimumIdle(2);pool.setConnectionTimeout(30000);pool.setPoolName("Stage1OwnedIntegration");var source=new DelegatingDataSource(pool){@Override public java.sql.Connection getConnection()throws java.sql.SQLException{return observedConnection(super.getConnection());}};
        db=new Stage1Db(new JdbcTemplate(source));assertEquals(resource.get("mysqlUuid").getAsString(),db.required("SELECT @@server_uuid AS uuid").get("uuid"));
        assertEquals("dgl_p0_s1",db.required("SELECT DATABASE() AS name").get("name"));
        assertTrue(db.count("SELECT COUNT(*) FROM li_p0_schema_history WHERE status='DONE'")>=9);
        settings=new Stage1Settings();settings.setEnabled(true);settings.setRunId(run.getFileName().toString());settings.setMysqlUuid(resource.get("mysqlUuid").getAsString());settings.setFileRoot(run.resolve("files").toString());settings.setManagerRoleIds(Set.of("710000000000000001","710000000000000002","710000000000000003"));
        new Stage1ResourceGuard(db,settings,new MockEnvironment().withProperty("spring.data.redis.host","127.0.0.1").withProperty("spring.data.redis.port","19379").withProperty("lili.message.consumer.enabled","false").withProperty("lili.promotion.delay-listener.enabled","false").withProperty("lili.mqtt.enabled","false").withProperty("lili.schedule.every-minute-cron","-").withProperty("lili.schedule.every-hour-cron","-").withProperty("lili.schedule.every-day-cron","-")).afterSingletonsInstantiated();
        testId=UUID.randomUUID().toString().replace("-","");previousSnowflake=ReflectionTestUtils.getField(SnowFlake.class,"snowflake");SnowFlake.initialize(29,30);
        var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);configuration.setJdbcTypeForNull(JdbcType.NULL);configuration.setLogImpl(NoLoggingImpl.class);
        var global=new GlobalConfig();global.setMetaObjectHandler(new MyMetaObjectHandler());
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(configuration);factory.setGlobalConfig(global);
        var sf=factory.getObject();for(Class<?> type:List.of(MemberMapper.class,ClerkMapper.class,StoreMapper.class,AdminUserMapper.class,MerchantGrantMapper.class,WechatIdentityMapper.class,MemberIdentityLinkMapper.class,GoodsMapper.class,GoodsSkuMapper.class))sf.getConfiguration().addMapper(type);
        var sql=new SqlSessionTemplate(sf);var members=sql.getMapper(MemberMapper.class);var bindings=sql.getMapper(WechatIdentityMapper.class);
        var memberService=new MemberServiceImpl();ReflectionTestUtils.setField(memberService,"baseMapper",members);
        var clerkService=new ClerkServiceImpl();ReflectionTestUtils.setField(clerkService,"baseMapper",sql.getMapper(ClerkMapper.class));
        var storeService=new StoreServiceImpl();ReflectionTestUtils.setField(storeService,"baseMapper",sql.getMapper(StoreMapper.class));
        var adminService=new AdminUserServiceImpl();ReflectionTestUtils.setField(adminService,"baseMapper",sql.getMapper(AdminUserMapper.class));
        var manager=new DataSourceTransactionManager(source);
        apps=new SessionApplications();apps.setMerchantApplication(settings.getRunId()+"_merchant");apps.setResidentApplication(settings.getRunId()+"_resident");apps.setManagerApplication(settings.getRunId()+"_manager");
        var merchantProperties=new MerchantWechatProperties();merchantProperties.setSchemaReady(true);merchantProperties.setEnabled(true);merchantProperties.setAppSecret(UUID.randomUUID().toString().replace("-",""));
        var residentProperties=new ResidentWechatProperties();residentProperties.setSchemaReady(true);residentProperties.setEnabled(true);residentProperties.setAppSecret(UUID.randomUUID().toString().replace("-",""));
        var identities=new WechatIdentityService(bindings,members,residentProperties,merchantProperties,manager);
        var verifier=new WechatSessionIdentityVerifier(identities,apps,sql.getMapper(MemberIdentityLinkMapper.class),new ResidentProfileProperties(),merchantProperties);
        var access=new Stage1Access(db,settings,sql.getMapper(MerchantGrantMapper.class));
        stage1Access=access;
        var proofBeans=new StaticListableBeanFactory(Map.of("proof",access));
        var merchant=new MerchantAuthorizationService(apps,memberService,clerkService,storeService,sql.getMapper(MerchantGrantMapper.class),proofBeans.getBeanProvider(MerchantOwnershipProof.class));merchant.setLifecycle(access);
        var verifierBeans=new StaticListableBeanFactory(Map.of("verifier",verifier));
        authorization=new CurrentSessionAuthorization(apps,verifierBeans.getBeanProvider(SessionIdentityVerifier.class),memberService,clerkService,storeService,adminService,mock(MenuService.class),mock(StoreMenuService.class),new ManagerTokenGenerate(),new StoreTokenGenerate());
        authorization.setMerchantAuthorization(merchant);authorization.setSessionAccessExtension(access);
        redisFactory=new LettuceConnectionFactory(new RedisStandaloneConfiguration("127.0.0.1",19379));redisFactory.afterPropertiesSet();redisFactory.start();redis=new StringRedisTemplate(redisFactory);
        try(var connection=redisFactory.getConnection()){assertEquals("PONG",connection.ping());assertEquals(resource.get("redisRunId").getAsString(),Objects.requireNonNull(connection.serverCommands().info("server")).getProperty("run_id"));}
        Path keyFile=run.resolve("private/session-signing-key");if(!Files.exists(keyFile)){byte[] bytes=new byte[48];new SecureRandom().nextBytes(bytes);Files.writeString(keyFile,Base64.getEncoder().encodeToString(bytes),StandardOpenOption.CREATE_NEW);Arrays.fill(bytes,(byte)0);}
        var jwt=new JWTTokenProperties();jwt.setSecretKey(Files.readString(keyFile));keys=new SecretKeyUtil(jwt);jwt.setSecretKey(null);
        sessions=new SessionService(new RedisSessionStore(redis),keys,authorization,clock);
        var gateway=new WechatCodeGateway(){public Identity authenticate(String code){String open=codes.remove(code);if(open==null)throw new ResidentLoginException(ResidentLoginException.Code.WECHAT_REJECTED);return new Identity(apps.application(SessionDomain.RESIDENT),open,null);}public Identity authenticate(String code,SessionDomain domain){assertEquals(SessionDomain.APPLICANT,domain);String open=codes.remove(code);if(open==null)throw new ResidentLoginException(ResidentLoginException.Code.WECHAT_REJECTED);return new Identity(apps.application(domain),open,null);}};
        login=new MerchantWechatLoginService(gateway,identities,sessions,apps,merchantProperties,merchant);
        residentLogin=new ResidentWechatLoginService(gateway,identities,sessions,apps,residentProperties);
        var context=new Stage1Context(authorization,access);var mutations=new Stage1Mutations(db,context,manager,clock);var files=new Stage1Files(db,context,access,mutations,settings,clock);var regions=new Stage1Regions(db);
        var rules=new AdmissionRules(db,regions,files,clock);var templates=new StaffTemplates(db,clock);admission=new AdmissionService(db,context,mutations,rules,regions,templates,clock);staff=new StaffService(db,context,mutations,templates,admission,keys,clock);
        var agreements=new AgreementService(db,context,mutations,files,clock);commissions=new CommissionRuleService(db,context,mutations,agreements,clock);eligibility=new EligibilityService(db,agreements,commissions,settings,manager,clock,apps);
        var vault=new ChannelVault(settings);var channelConfigs=new ChannelConfigService(db,context,mutations,settings,vault,manager,clock,apps);var channelMaterials=new ChannelMaterials(db,files,clock);channels=new ChannelService(db,context,access,mutations,channelConfigs,channelMaterials,vault,files,settings,manager,clock);receivers=new ReceiverService(db,context,access,mutations,channelConfigs,vault,manager,clock);
        var catalog=new CatalogService(db,context,mutations,clock);var fulfillment=new FulfillmentService(db,context,mutations,regions,clock);var goodsRules=new GoodsContentRules(db,files,catalog);
        var nativeGoods=new GoodsServiceImpl();ReflectionTestUtils.setField(nativeGoods,"baseMapper",sql.getMapper(GoodsMapper.class));var nativeSkus=new GoodsSkuServiceImpl();ReflectionTestUtils.setField(nativeSkus,"baseMapper",sql.getMapper(GoodsSkuMapper.class));var projector=new NativeGoodsProjector(db,nativeGoods,nativeSkus,goodsRules,catalog,files,settings,clock);var goods=new GoodsManagementService(db,context,access,mutations,goodsRules,projector,eligibility,fulfillment,clock);var inventory=new InventoryService(db,context,mutations,projector,eligibility,settings,redis,clock);
        var filters=new EnumMap<SessionDomain,SessionHttpFilter>(SessionDomain.class);for(var domain:SessionDomain.values())filters.put(domain,new SessionHttpFilter(sessions,authorization,apps,domain));
        Filter dispatch=(request,response,chain)->{
            var req=(jakarta.servlet.http.HttpServletRequest)request;String path=req.getRequestURI();SessionDomain domain=path.startsWith(Stage1Routes.APPLICANT)?SessionDomain.APPLICANT:path.startsWith("/store/")?SessionDomain.STORE:path.startsWith("/manager/")?SessionDomain.MANAGER:SessionDomain.RESIDENT;
            try{filters.get(domain).doFilter(request,response,chain);}finally{SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}
        };
        var consumer=new ConsumerGoodsService(db,context,eligibility,fulfillment,settings,manager,clock);
        mvc=MockMvcBuilders.standaloneSetup(new ApplicantController(admission),new ManagerAdmissionController(admission,context),new MerchantProfileController(admission,context,access),new StaffController(staff),new InvitationController(staff),new Stage1CommonController(mutations,files,regions,context),new ManagerQualificationController(admission),new ManagerCooperationController(agreements,commissions),new EligibilityController(context,eligibility,agreements,commissions),new ManagerChannelController(channelConfigs,channels,receivers),new CatalogController(catalog),new FulfillmentController(fulfillment,context),new GoodsManagementController(goods,inventory),new ConsumerGoodsController(consumer),new MerchantWorkbench(context,access,eligibility,goods,clock)).setControllerAdvice(new Stage1Responses()).addFilters(new Stage1EntranceGuard(),dispatch).build();
        reviewer=manager("710000000000000001");operator=manager("710000000000000002");channel=manager("710000000000000003");
        category=db.id();db.nativeInsert("li_category",map("id",category,"delete_flag",false,"create_by","S1_TEST","name","S1_TEST_"+testId,"parent_id",0,"level",1));
        db.update("INSERT INTO li_p0_catalog_control(object_type,object_id,enabled,normalized_name) VALUES('CATEGORY',?,TRUE,?)",category,"S1_TEST_"+testId);
        observations.add(map("fixture",testId,"mode","real MySQL/MyBatis/Redis and Session filter; synthetic Code gateway only","databaseUuid",settings.getMysqlUuid(),"category",category));
    }
    @BeforeEach void freshManagerSessions(){reviewer=sessions.refresh(reviewer.refreshToken(),SessionDomain.MANAGER,apps.application(SessionDomain.MANAGER));operator=sessions.refresh(operator.refreshToken(),SessionDomain.MANAGER,apps.application(SessionDomain.MANAGER));channel=sessions.refresh(channel.refreshToken(),SessionDomain.MANAGER,apps.application(SessionDomain.MANAGER));var principal=sessions.verifyAccess(channel.accessToken(),SessionDomain.MANAGER,apps.application(SessionDomain.MANAGER));assertFalse(stage1Access.managerPermissions(principal.identity().subject()).contains("admission.review"));assertThrows(SessionException.class,()->authorization.checkRoute(authorization.current(principal.identity()),"POST",Stage1Routes.MANAGER+"/applications/123/approve",true));}
    @AfterAll static void finish() throws Exception {
        if(run!=null)Files.writeString(run.resolve("evidence/stage1-persistence-"+(testId==null?"setup-failed":testId)+".json"),write(observations));
        if(redisFactory!=null)redisFactory.destroy();if(keys!=null)keys.destroy();if(previousSnowflake!=null)ReflectionTestUtils.setField(SnowFlake.class,"snowflake",previousSnowflake);
        if(pool!=null)pool.close();
        SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();
    }
    @Test void admissionCreatesOneNativeStoreAndOnlyNewVerifiedLoginBecomesOwner() throws Exception {
        String open="owner_"+UUID.randomUUID().toString().replace("-","");var applicant=login(open);assertEquals("APPLICANT",applicant.identity());
        var created=call(post(Stage1Routes.APPLICANT+"/application"),applicant.session(),map("requestId",req(),"payload",Map.of()),200);String application=created.get("id").getAsString();
        assertTrue(created.get("storeId").isJsonNull());String file=upload(applicant.session(),Stage1Routes.APPLICANT);
        var payload=payload(file);call(put(Stage1Routes.APPLICANT+"/application/"+application+"/draft"),applicant.session(),map("requestId",req(),"expectedVersion",0,"payload",payload),200);
        String request=req();var command=map("requestId",request,"expectedVersion",1);
        var submitted=call(post(Stage1Routes.APPLICANT+"/application/"+application+"/submit"),applicant.session(),command,200);String store=submitted.get("storeId").getAsString();
        assertEquals(store,call(post(Stage1Routes.APPLICANT+"/application/"+application+"/submit"),applicant.session(),command,200).get("storeId").getAsString());
        assertEquals(0,db.count("SELECT COUNT(*) FROM li_clerk WHERE member_id=?",applicant.memberId()));
        call(post(Stage1Routes.MANAGER+"/applications/"+application+"/approve"),channel,map("requestId",req(),"expectedVersion",2,"revision",1),403);
        call(post(Stage1Routes.MANAGER+"/applications/"+application+"/approve"),reviewer,map("requestId",req(),"expectedVersion",2,"revision",1),200);
        assertEquals(1,db.count("SELECT COUNT(*) FROM li_store WHERE member_id=?",applicant.memberId()));assertEquals(1,db.count("SELECT COUNT(*) FROM li_clerk WHERE member_id=? AND shopkeeper=1",applicant.memberId()));
        assertThrows(SessionException.class,()->sessions.refresh(applicant.session().refreshToken(),SessionDomain.APPLICANT,apps.application(SessionDomain.APPLICANT)));
        var owner=login(open);assertEquals("STORE",owner.identity());assertEquals("OWNER",owner.role());
        var profile=call(get(Stage1Routes.STORE+"/profile"),owner.session(),null,200);assertEquals(store,profile.get("id").getAsString());assertEquals(1,profile.getAsJsonObject("qualifications").getAsJsonArray("versions").size());
        var stranger=login("stranger_"+UUID.randomUUID().toString().replace("-",""));call(get(Stage1Routes.APPLICANT+"/files/"+file+"/preview"),stranger.session(),null,404);
        call(post(Stage1Routes.MANAGER+"/applications/"+application+"/approve"),reviewer,map("requestId",req(),"expectedVersion",2,"revision",1),409);
        observations.add(map("case","admission-owner","storeId",store,"applicationId",application,"uniqueStore",true,"oldApplicantRefreshDenied",true));
    }
    @Test void malformedAndForeignCommandsDoNotWriteNativeStore() throws Exception {
        var applicant=login("invalid_"+UUID.randomUUID().toString().replace("-",""));long before=db.count("SELECT COUNT(*) FROM li_store");
        call(post(Stage1Routes.APPLICANT+"/application"),applicant.session(),map("requestId",req(),"payload",map("storeId","123")),422);
        var created=call(post(Stage1Routes.APPLICANT+"/application"),applicant.session(),map("requestId",req(),"payload",Map.of()),200);String id=created.get("id").getAsString();
        call(post(Stage1Routes.APPLICANT+"/application/"+id+"/submit"),applicant.session(),map("requestId",req(),"expectedVersion",0),422);
        var other=login("other_"+UUID.randomUUID().toString().replace("-",""));call(put(Stage1Routes.APPLICANT+"/application/"+id+"/draft"),other.session(),map("requestId",req(),"expectedVersion",0,"payload",Map.of()),404);
        assertEquals(before,db.count("SELECT COUNT(*) FROM li_store"));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_application_revision WHERE application_id=?",id));
    }
    @Test void invitationRequiresAcceptanceAndOwnerConfirmationAndRevocationCannotResurrectOldSession() throws Exception {
        var owner=ownerFixture();String open="staff_"+UUID.randomUUID().toString().replace("-","");var applicant=login(open);
        var roleRows=call(get(Stage1Routes.STORE+"/staff-role-options"),owner.login.session(),null,200).getAsJsonArray("result");String role=null;
        for(var row:roleRows)if(row.getAsJsonObject().get("name").getAsString().equals("READ_ONLY"))role=row.getAsJsonObject().get("id").getAsString();assertNotNull(role);
        var invitation=call(post(Stage1Routes.STORE+"/staff-invitations"),owner.login.session(),map("requestId",req(),"roleIds",List.of(role),"reason","S1_TEST邀请只读人员"),200);
        String id=invitation.get("id").getAsString(),token=invitation.get("invitationToken").getAsString();
        assertEquals(0,db.count("SELECT COUNT(*) FROM li_clerk WHERE member_id=?",applicant.memberId()));
        call(post(Stage1Routes.APPLICANT+"/invitations/accept"),applicant.session(),map("requestId",req(),"expectedVersion",0,"token",token),200);
        assertEquals("APPLICANT",login(open).identity());assertEquals(0,db.count("SELECT COUNT(*) FROM li_clerk WHERE member_id=?",applicant.memberId()));
        call(post(Stage1Routes.STORE+"/staff-invitations/"+id+"/confirm"),owner.login.session(),map("requestId",req(),"expectedVersion",1,"reason","S1_TEST复核可信身份"),200);
        var staff=login(open);assertEquals("STAFF",staff.role());var current=call(get(Stage1Routes.STORE+"/context"),staff.session(),null,200);String clerk=current.get("clerkId").getAsString();
        assertFalse(current.getAsJsonArray("permissions").toString().contains("cost.read"));
        call(post(Stage1Routes.STORE+"/staff-invitations"),staff.session(),map("requestId",req(),"roleIds",List.of(role),"reason","越权尝试"),403);
        call(post(Stage1Routes.STORE+"/staff/"+clerk+"/disable"),owner.login.session(),map("requestId",req(),"expectedVersion",0,"reason","S1_TEST停用"),200);
        call(get(Stage1Routes.STORE+"/context"),staff.session(),null,403);
        call(post(Stage1Routes.STORE+"/staff/"+clerk+"/enable"),owner.login.session(),map("requestId",req(),"expectedVersion",1,"reason","S1_TEST重新启用"),200);
        call(get(Stage1Routes.STORE+"/context"),staff.session(),null,403);assertThrows(SessionException.class,()->sessions.refresh(staff.session().refreshToken(),SessionDomain.STORE,apps.application(SessionDomain.STORE)));
        var enabled=login(open);call(get(Stage1Routes.STORE+"/context"),enabled.session(),null,200);
        call(post(Stage1Routes.STORE+"/staff/"+clerk+"/revoke"),owner.login.session(),map("requestId",req(),"expectedVersion",2,"reason","S1_TEST撤销"),200);
        call(get(Stage1Routes.STORE+"/context"),enabled.session(),null,403);assertEquals("APPLICANT",login(open).identity());
        assertEquals(4,db.count("SELECT COUNT(*) FROM li_p0_staff_grant_history WHERE clerk_id=?",clerk));
        assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_grant_evidence WHERE clerk_id=? AND active=0",clerk));
        assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_operation WHERE response_json LIKE ?","%"+token+"%"));
        observations.add(map("case","staff-invitation-revoke","storeId",owner.store,"clerkId",clerk,"grantHistory",4,"tokenPersisted",false));
    }
    @Test void closingAndReopeningStoreAllowsNewCorrectiveSessionWithoutRevivingOldCredentials() throws Exception {
        var owner=ownerFixture();var profile=call(get(Stage1Routes.STORE+"/profile"),owner.login.session(),null,200);
        call(post(Stage1Routes.MANAGER+"/stores/"+owner.store+"/close"),operator,map("requestId",req(),"expectedVersion",profile.get("version").getAsLong(),"reason","S1_TEST整改"),200);
        call(get(Stage1Routes.STORE+"/profile"),owner.login.session(),null,403);
        var corrective=login(owner.open);profile=call(get(Stage1Routes.STORE+"/profile"),corrective.session(),null,200);assertEquals("CLOSED",profile.get("state").getAsString());
        call(post(Stage1Routes.STORE+"/staff-invitations"),corrective.session(),map("requestId",req(),"roleIds",List.of("1"),"reason","停业不得新增授权"),403);
        call(post(Stage1Routes.MANAGER+"/stores/"+owner.store+"/reopen"),operator,map("requestId",req(),"expectedVersion",profile.get("version").getAsLong(),"reason","S1_TEST整改复核"),200);
        call(get(Stage1Routes.STORE+"/profile"),owner.login.session(),null,403);call(get(Stage1Routes.STORE+"/profile"),corrective.session(),null,200);
    }
    @Test void agreementIsIndependentOfChannelAndRuleIsExplicitImmutableAndHistoricallyReadable() throws Exception {
        var owner=ownerFixture();String agreement=agreementFixture(owner,0,LocalDate.now(Stage1Rules.ZONE),null);
        var ready=call(get(Stage1Routes.STORE+"/eligibility").param("categoryId",category),owner.login.session(),null,200);
        assertTrue(ready.get("BASIC_ELIGIBLE").getAsBoolean());assertFalse(ready.get("MOCK_READY").getAsBoolean());assertFalse(ready.get("REAL_READY").getAsBoolean());
        assertTrue(ready.getAsJsonObject("reasons").getAsJsonArray("REAL").toString().contains("CHANNEL_PENDING"));
        call(put(Stage1Routes.MANAGER+"/agreements/"+agreement+"/draft"),operator,map("requestId",req(),"expectedVersion",1,"payload",Map.of()),409);
        String start=LocalDate.now(Stage1Rules.ZONE).toString(),end=LocalDate.now(Stage1Rules.ZONE).plusDays(90).toString();
        var payload=map("name","S1_TEST明确零佣金","rateBps",0,"baseCode",AgreementService.BASE,"roundCode",AgreementService.ROUND,"startDate",start,"endDate",end,"commissionClause","S1_TEST明确0基点条款","triggerBasis","ORDER_COMPLETED_NO_BLOCKING_AFTERSALE","executionIntent",true);
        call(post(Stage1Routes.MANAGER+"/commission-rules"),operator,map("requestId",req(),"storeId",owner.store,"agreementId",agreement,"businessType","MALL","payload",payload),403);
        var rule=call(post(Stage1Routes.MANAGER+"/commission-rules"),channel,map("requestId",req(),"storeId",owner.store,"agreementId",agreement,"businessType","MALL","payload",payload),200);String id=rule.get("id").getAsString();
        rule=call(post(Stage1Routes.MANAGER+"/commission-rules/"+id+"/publish"),channel,map("requestId",req(),"expectedVersion",0),200);assertFalse(rule.get("fundExecutionEnabled").getAsBoolean());
        Instant before=clock.instant();assertEquals(id,Stage1Json.id(commissions.findEffectiveRule(owner.store,"MALL",before),"id"));
        call(post(Stage1Routes.MANAGER+"/commission-rules/"+id+"/disable"),channel,map("requestId",req(),"expectedVersion",1,"reason","S1_TEST停止未来资格"),200);
        assertNull(commissions.findEffectiveRule(owner.store,"MALL",clock.instant()));assertEquals(id,Stage1Json.id(commissions.findEffectiveRule(owner.store,"MALL",before),"id"));assertNotNull(commissions.getImmutableVersion(id,1));
        call(post(Stage1Routes.MANAGER+"/agreements/"+agreement+"/terminate"),operator,map("requestId",req(),"expectedVersion",1,"reason","S1_TEST结束合作"),200);
        assertFalse((Boolean)eligibility.evaluate(owner.store,category,null).get("BASIC_ELIGIBLE"));
        assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_commission_rule WHERE id=? AND rate_bps=0",id));
        observations.add(map("case","cooperation-rule-history","storeId",owner.store,"agreementId",agreement,"ruleId",id,"moneyExecution",false,"realReady",false));
    }
    @Test void futureAgreementReplacementPreservesOriginalDatesAndRejectsOverlap() throws Exception {
        var owner=ownerFixture();LocalDate today=LocalDate.now(Stage1Rules.ZONE);String old=agreementFixture(owner,500,today,null);
        String next=agreementFixture(owner,600,today.plusDays(7),old);Instant boundary=today.plusDays(7).atStartOfDay(Stage1Rules.ZONE).toInstant();
        assertEquals(boundary,instant(db.required("SELECT replaced_at FROM li_p0_agreement WHERE id=?",old).get("replaced_at")));
        assertEquals(today.plusYears(1).plusDays(1).atStartOfDay(Stage1Rules.ZONE).toInstant(),instant(db.required("SELECT effective_to FROM li_p0_agreement WHERE id=?",old).get("effective_to")));
        assertTrue((Boolean)eligibility.evaluate(owner.store,category,boundary.minusNanos(1000)).get("BASIC_ELIGIBLE"));assertTrue((Boolean)eligibility.evaluate(owner.store,category,boundary).get("BASIC_ELIGIBLE"));
        assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_cooperation_cutover WHERE previous_id=? AND replacement_id=?",old,next));
        var extra=createAgreement(owner,500,today.plusDays(2),null);String id=extra.get("id").getAsString();
        call(post(Stage1Routes.MANAGER+"/agreements/"+id+"/confirm"),operator,map("requestId",req(),"expectedVersion",0),409);assertNull(db.required("SELECT confirmed_at FROM li_p0_agreement WHERE id=?",id).get("confirmed_at"));
    }
    @Test void qualificationRenewalStaysPendingThenSwitchesOnlyAtApprovedFutureBoundary() throws Exception {
        var owner=ownerFixture();agreementFixture(owner,0,LocalDate.now(Stage1Rules.ZONE),null);
        var profile=call(get(Stage1Routes.STORE+"/profile"),owner.login.session(),null,200);var old=profile.getAsJsonObject("qualifications").getAsJsonArray("versions").get(0).getAsJsonObject();
        var details=old.getAsJsonObject("details").deepCopy();LocalDate start=LocalDate.now(Stage1Rules.ZONE).plusDays(30);details.addProperty("validFrom",start.toString());details.addProperty("validThrough",start.plusYears(1).toString());
        var draft=call(post(Stage1Routes.STORE+"/qualifications"),owner.login.session(),map("requestId",req(),"payload",map("qualifications",List.of(details))),200);String id=draft.get("id").getAsString();
        call(post(Stage1Routes.STORE+"/qualifications/"+id+"/submit"),owner.login.session(),map("requestId",req(),"expectedVersion",0),200);
        assertNull(db.required("SELECT superseded_at FROM li_p0_qualification WHERE id=?",old.get("id").getAsString()).get("superseded_at"));
        call(post(Stage1Routes.MANAGER+"/applications/"+id+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"revision",1),200);
        assertEquals(start.atStartOfDay(Stage1Rules.ZONE).toInstant(),instant(db.required("SELECT superseded_at FROM li_p0_qualification WHERE id=?",old.get("id").getAsString()).get("superseded_at")));
        assertTrue((Boolean)eligibility.evaluate(owner.store,category,null).get("BASIC_ELIGIBLE"));assertTrue((Boolean)eligibility.evaluate(owner.store,category,start.atStartOfDay(Stage1Rules.ZONE).toInstant()).get("BASIC_ELIGIBLE"));
        call(post(Stage1Routes.MANAGER+"/qualifications/"+old.get("id").getAsString()+"/revoke"),reviewer,map("requestId",req(),"expectedVersion",1,"reason","S1_TEST当前资质撤销"),200);
        assertFalse((Boolean)eligibility.evaluate(owner.store,category,null).get("BASIC_ELIGIBLE"));
    }
    @Test void durableChannelOnboardingAndPlatformReceiverGateMockReadinessWithoutRealEvidence() throws Exception {
        var owner=ownerFixture();String agreement=agreementFixture(owner,500,LocalDate.now(Stage1Rules.ZONE),null);ruleFixture(owner,agreement,500);
        String config=channelConfigFixture(1000),relation=channelRelationFixture(owner,config);long version=1;
        var submitBody=map("requestId",req(),"expectedVersion",version,"reason","S1_TEST提交合成进件");var submitted=call(post(Stage1Routes.MANAGER+"/submerchants/"+relation+"/submit"),channel,submitBody,200);assertEquals("SUCCEEDED",submitted.getAsJsonObject("operation").get("status").getAsString());
        String operation=submitted.getAsJsonObject("operation").get("id").getAsString();assertEquals(operation,call(post(Stage1Routes.MANAGER+"/submerchants/"+relation+"/submit"),channel,submitBody,200).getAsJsonObject("operation").get("id").getAsString());assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_channel_mock WHERE config_id=?",config));
        JsonObject current=submitted.getAsJsonObject("relation");for(int i=0;i<3;i++)current=relationCommand(relation,current.get("version").getAsLong(),"query");assertEquals("COMPLETED",current.get("applicationState").getAsString());assertEquals("VERIFIED",current.get("capabilityState").getAsString());assertFalse(current.get("enabled").getAsBoolean());
        current=relationCommand(relation,current.get("version").getAsLong(),"enable");var result=eligibility.evaluate(owner.store,category,null);assertFalse((Boolean)result.get("MOCK_READY"));assertTrue(write(result.get("reasons")).contains("RECEIVER_UNVERIFIED"));
        String account="MOCK_RECEIVER_"+testId;var receiverBody=map("requestId",req(),"configId",config,"displayName","S1_TEST平台佣金","mockAccount",account,"reason","S1_TEST平台级接收方");var receiver=call(post(Stage1Routes.MANAGER+"/receivers"),channel,receiverBody,200);String receiverId=receiver.get("id").getAsString();
        receiverBody.put("requestId",req());call(post(Stage1Routes.MANAGER+"/receivers"),channel,receiverBody,409);
        receiver=call(post(Stage1Routes.MANAGER+"/receivers/"+receiverId+"/sync"),channel,map("requestId",req(),"expectedVersion",0,"reason","S1_TEST可信Mock同步"),200).getAsJsonObject("receiver");assertEquals("VERIFIED",receiver.get("state").getAsString());
        call(post(Stage1Routes.MANAGER+"/receivers/"+receiverId+"/enable"),channel,map("requestId",req(),"expectedVersion",receiver.get("version").getAsLong(),"reason","S1_TEST启用已验证关系"),200);
        call(put(Stage1Routes.MANAGER+"/submerchants/"+relation+"/receiver"),channel,map("requestId",req(),"expectedVersion",current.get("version").getAsLong(),"receiverId",receiverId,"reason","S1_TEST引用平台关系"),200);
        result=eligibility.evaluate(owner.store,category,null);assertTrue((Boolean)result.get("MOCK_READY"));assertFalse((Boolean)result.get("REAL_READY"));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_operation WHERE response_json LIKE ?","%"+account+"%"));
        call(post(Stage1Routes.MANAGER+"/channel-configs/"+config+"/disable"),channel,map("requestId",req(),"expectedVersion",2,"reason","S1_TEST配置关闭"),200);assertFalse((Boolean)eligibility.evaluate(owner.store,category,null).get("MOCK_READY"));
        assertEquals("UNVERIFIED",db.required("SELECT state FROM li_p0_receiver_relation WHERE id=?",receiverId).get("state"));observations.add(map("case","onboarding-platform-receiver","storeId",owner.store,"configId",config,"relationId",relation,"receiverId",receiverId,"mockReadyBeforeDisable",true,"realReady",false));
    }
    @Test void channelUnknownAndCorrectionUseOriginalApplicationAndRejectModeInjection() throws Exception {
        var owner=ownerFixture();String config=channelConfigFixture(1000),relation=channelRelationFixture(owner,config);var current=relationCommand(relation,1,"submit");String out=current.get("outRequestNo").getAsString();
        call(post(Stage1Routes.MANAGER+"/submerchants/"+relation+"/query"),channel,map("requestId",req(),"expectedVersion",current.get("version").getAsLong(),"reason","S1_TEST","mode","REAL"),422);
        db.update("UPDATE li_p0_channel_mock SET scenario='TIMEOUT' WHERE config_id=? AND out_request_no=?",config,out);current=relationCommand(relation,current.get("version").getAsLong(),"query");assertEquals("UNKNOWN",current.get("lastSyncState").getAsString());
        call(post(Stage1Routes.MANAGER+"/submerchants/"+relation+"/submit"),channel,map("requestId",req(),"expectedVersion",current.get("version").getAsLong(),"reason","未知结果不得重提"),409);
        db.update("UPDATE li_p0_channel_mock SET scenario='NEED_CORRECTION' WHERE config_id=? AND out_request_no=?",config,out);current=relationCommand(relation,current.get("version").getAsLong(),"query");assertEquals("NEED_CORRECTION",current.get("applicationState").getAsString());assertTrue(current.getAsJsonObject("requiredActions").toString().contains("CORRECT_MATERIALS"));
        var payload=channelMaterialsFixture(owner);current=call(put(Stage1Routes.MANAGER+"/submerchants/"+relation+"/materials"),channel,map("requestId",req(),"expectedVersion",current.get("version").getAsLong(),"materials",payload,"reason","S1_TEST补正版本"),200);current=relationCommand(relation,current.get("version").getAsLong(),"submit");assertEquals(out,current.get("outRequestNo").getAsString());assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_channel_mock WHERE config_id=?",config));assertEquals(2,db.count("SELECT COUNT(*) FROM li_p0_channel_material_revision WHERE application_id=?",current.get("applicationId").getAsString()));
        call(get(Stage1Routes.MANAGER+"/submerchants/"+relation),operator,null,403);call(get(Stage1Routes.MANAGER+"/submerchants/"+relation),owner.login.session(),null,403);
    }
    @Test void staleSuccessfulChannelResponseCannotOverrideLaterDisable() throws Exception {
        var owner=ownerFixture();String config=channelConfigFixture(1000),relation=channelRelationFixture(owner,config);var current=relationCommand(relation,1,"submit");current=relationCommand(relation,current.get("version").getAsLong(),"query");String application=current.get("applicationId").getAsString();
        // A committed dispatch is represented exactly as it would be at a process interruption boundary.
        String operation=db.id();var row=db.required("SELECT * FROM li_p0_submerchant_relation WHERE id=?",relation);long sequence=number(row,"request_seq")+1;String actor=sessions.verifyAccess(channel.accessToken(),SessionDomain.MANAGER,apps.application(SessionDomain.MANAGER)).identity().subject();
        db.update("UPDATE li_p0_submerchant_relation SET request_seq=?,version=version+1,last_sync_state='UNKNOWN' WHERE id=?",sequence,relation);
        db.update("INSERT INTO li_p0_channel_operation(id,application_id,action,request_hash,request_seq,config_version,status,object_type,object_id,actor_id,request_id,relation_version,material_revision,created_at) VALUES(?,?,'QUERY',?,?,?,'DISPATCHING','APPLICATION',?,?,?,?,1,?)",operation,application,hash(map("fixture","interruption")),sequence,row.get("config_version"),relation,actor,req(),number(row,"version")+1,clock.instant());
        var op=db.required("SELECT * FROM li_p0_channel_operation WHERE id=?",operation);current=relationCommand(relation,number(row,"version")+1,"disable");var app=db.required("SELECT * FROM li_p0_channel_application WHERE id=?",application);
        ReflectionTestUtils.invokeMethod(channels,"finish",op,object(map("applyment_id",id(app,"applyment_id"),"out_request_no",app.get("out_request_no"),"applyment_state","FINISH","sub_mchid","MOCK_SUB_123456")),map("verificationState","VERIFIED","sourceMode","MOCK"),null,null);
        assertEquals("STALE",db.required("SELECT status FROM li_p0_channel_operation WHERE id=?",operation).get("status"));row=db.required("SELECT * FROM li_p0_submerchant_relation WHERE id=?",relation);assertFalse(flag(row,"enabled"));assertEquals(current.get("version").getAsLong(),number(row,"version"));assertNotEquals("MOCK_SUB_123456",id(row,"sub_mchid"));
    }
    @Test void realConfigurationCanBePreparedButCannotClaimReadinessOrSendWithoutCredentials() throws Exception {
        var configuration=call(post(Stage1Routes.MANAGER+"/channel-configs"),channel,map("requestId",req(),"name","S1_TEST真实配置待凭据","mode","REAL","providerMchid","1900000001","paymentAppId","wx0123456789abcdef","reason","S1_TEST准备资料"),200);String id=configuration.get("id").getAsString();
        configuration=call(post(Stage1Routes.MANAGER+"/channel-configs/"+id+"/check"),channel,map("requestId",req(),"expectedVersion",0,"reason","S1_TEST本地完整性校验"),200);assertEquals("CREDENTIAL_PENDING",configuration.get("checkState").getAsString());assertFalse(configuration.get("enabled").getAsBoolean());assertFalse(configuration.toString().contains("1900000001"));
        call(post(Stage1Routes.MANAGER+"/channel-configs/"+id+"/enable"),channel,map("requestId",req(),"expectedVersion",1,"reason","不具备条件"),409);
    }
    @Test void nativeGoodsReviewAndLogicalListingAllowZeroStockAndProtectCriticalContent() throws Exception {
        var owner=ownerFixture();agreementFixture(owner,0,LocalDate.now(Stage1Rules.ZONE),null);deliveryFixture(owner);String unit=unitFixture();String photo=upload(owner.login.session(),Stage1Routes.STORE,"GOODS");
        var created=call(post(Stage1Routes.STORE+"/goods"),owner.login.session(),map("requestId",req(),"payload",goodsPayload(unit,photo,0)),200);String goods=created.getAsJsonObject("goods").get("id").getAsString(),sku=created.getAsJsonArray("skus").get(0).getAsJsonObject().get("id").getAsString();assertEquals(1,db.count("SELECT COUNT(*) FROM li_goods WHERE id=? AND store_id=?",goods,owner.store));assertEquals(1,db.count("SELECT COUNT(*) FROM li_goods_sku WHERE id=? AND goods_id=?",sku,goods));
        var submitted=call(post(Stage1Routes.STORE+"/goods/"+goods+"/submit"),owner.login.session(),map("requestId",req(),"expectedVersion",0),200);assertEquals("SUBMITTED",submitted.getAsJsonObject("goods").get("reviewStatus").getAsString());
        call(put(Stage1Routes.STORE+"/goods/"+goods+"/content"),owner.login.session(),map("requestId",req(),"expectedVersion",1,"payload",created.get("payload")),409);
        call(post(Stage1Routes.MANAGER+"/goods/"+goods+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"contentVersion",0),200);
        var listed=call(post(Stage1Routes.STORE+"/goods/"+goods+"/list"),owner.login.session(),map("requestId",req(),"expectedVersion",2),200);assertEquals("UPPER",listed.getAsJsonObject("goods").get("marketStatus").getAsString());assertFalse(listed.getAsJsonObject("goods").get("mockSaleable").getAsBoolean());assertEquals("PASS",db.required("SELECT auth_flag FROM li_goods_sku WHERE id=?",sku).get("auth_flag"));
        var edit=listed.getAsJsonObject("payload").deepCopy();edit.addProperty("name","S1_TEST修订后的商品");call(put(Stage1Routes.STORE+"/goods/"+goods+"/content"),owner.login.session(),map("requestId",req(),"expectedVersion",3,"payload",edit),409);
        var saved=call(put(Stage1Routes.STORE+"/goods/"+goods+"/content"),owner.login.session(),map("requestId",req(),"expectedVersion",3,"payload",edit,"confirmUnlist",true),200);assertEquals("DOWN",saved.getAsJsonObject("goods").get("marketStatus").getAsString());assertEquals("DRAFT",saved.getAsJsonObject("goods").get("reviewStatus").getAsString());assertEquals(sku,saved.getAsJsonArray("skus").get(0).getAsJsonObject().get("id").getAsString());assertEquals(1,saved.getAsJsonArray("reviews").size());
        var other=ownerFixture();call(get(Stage1Routes.STORE+"/goods/"+goods),other.login.session(),null,404);call(put(Stage1Routes.STORE+"/skus/"+sku+"/stock"),other.login.session(),map("requestId",req(),"expectedVersion",1,"targetQuantity",1,"reason","跨店尝试"),404);
        call(put(Stage1Routes.MANAGER+"/units/"+unit),operator,map("requestId",req(),"expectedVersion",1,"name","改变含义","reason","已引用单位不能改名"),409);
    }
    @Test void independentStockAndCostCannotBeOverwrittenByOldProductFormOrLegacyCache() throws Exception {
        var owner=ownerFixture();var created=call(post(Stage1Routes.STORE+"/goods"),owner.login.session(),map("requestId",req(),"payload",goodsPayload(unitFixture(),upload(owner.login.session(),Stage1Routes.STORE,"GOODS"),4)),200);String goods=created.getAsJsonObject("goods").get("id").getAsString(),sku=created.getAsJsonArray("skus").get(0).getAsJsonObject().get("id").getAsString();
        var stock=map("requestId",req(),"expectedVersion",1,"targetQuantity",7,"reason","S1_TEST补充非公开草稿库存");call(put(Stage1Routes.STORE+"/skus/"+sku+"/stock"),owner.login.session(),stock,200);call(put(Stage1Routes.STORE+"/skus/"+sku+"/stock"),owner.login.session(),stock,200);assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_inventory_adjustment WHERE sku_id=? AND request_id=?",sku,stock.get("requestId")));
        call(put(Stage1Routes.STORE+"/skus/"+sku+"/cost"),owner.login.session(),map("requestId",req(),"expectedVersion",0,"costCent",99999999,"reason","S1_TEST成本可以高于售价"),200);
        var stale=created.getAsJsonObject("payload").deepCopy();stale.getAsJsonArray("skus").get(0).getAsJsonObject().addProperty("initialQuantity",4);call(put(Stage1Routes.STORE+"/goods/"+goods+"/content"),owner.login.session(),map("requestId",req(),"expectedVersion",0,"payload",stale),422);assertEquals(7,db.count("SELECT quantity FROM li_goods_sku WHERE id=?",sku));assertEquals(99999999,cents(db.required("SELECT cost FROM li_goods_sku WHERE id=?",sku).get("cost")));
        String key=cn.lili.modules.goods.service.GoodsSkuService.getStockCacheKey(sku);redis.opsForValue().set(key,"3");try{call(put(Stage1Routes.STORE+"/skus/"+sku+"/stock"),owner.login.session(),map("requestId",req(),"expectedVersion",2,"targetQuantity",1,"reason","不覆盖来源未明的旧库存差异"),409);assertEquals("3",redis.opsForValue().get(key));assertEquals(7,db.count("SELECT quantity FROM li_goods_sku WHERE id=?",sku));}finally{redis.delete(key);}
    }
    @Test void lastPointDisablePreservesOtherMethodsAndImmutableAddressHistory() throws Exception {
        var owner=ownerFixture();String point=createPointFixture(owner,"COMMUNITY_PICKUP");call(post(Stage1Routes.STORE+"/points/"+point+"/enable"),owner.login.session(),map("requestId",req(),"expectedVersion",0,"reason","S1_TEST启用"),200);
        var methods=deliveryMethods();methods.put("COMMUNITY_PICKUP",map("enabled",true,"feeCent",0));call(put(Stage1Routes.STORE+"/fulfillment"),owner.login.session(),map("requestId",req(),"expectedVersion",0,"methods",methods,"reason","S1_TEST配送与自提"),200);
        call(post(Stage1Routes.STORE+"/points/"+point+"/disable"),owner.login.session(),map("requestId",req(),"expectedVersion",1,"reason","S1_TEST最后点位风险停用"),200);var result=call(get(Stage1Routes.STORE+"/fulfillment"),owner.login.session(),null,200);assertTrue(result.getAsJsonArray("effectiveMethods").toString().contains("MERCHANT_DELIVERY"));assertFalse(result.getAsJsonArray("effectiveMethods").toString().contains("COMMUNITY_PICKUP"));assertEquals(3,db.count("SELECT COUNT(*) FROM li_p0_point_history WHERE point_id=?",point));
        var managerView=call(get(Stage1Routes.MANAGER+"/stores/"+owner.store+"/points"),reviewer,null,200);assertEquals(point,managerView.getAsJsonArray("records").get(0).getAsJsonObject().get("id").getAsString());call(delete(Stage1Routes.STORE+"/points/"+point),owner.login.session(),null,403);
    }
    @Test void hundredConcurrentInventoryCommandsHaveOneWinnerAndOneDurableIdempotentChange()throws Exception{
        var owner=ownerFixture();var created=call(post(Stage1Routes.STORE+"/goods"),owner.login.session(),map("requestId",req(),"payload",goodsPayload(unitFixture(),upload(owner.login.session(),Stage1Routes.STORE,"GOODS"),4)),200);String sku=created.getAsJsonArray("skus").get(0).getAsJsonObject().get("id").getAsString();
        var first=concurrentStock(owner,sku,1,null,8);assertEquals(1,first.stream().filter(s->s==200).count());assertEquals(99,first.stream().filter(s->s==409).count());assertEquals(8,db.count("SELECT quantity FROM li_goods_sku WHERE id=?",sku));assertEquals(2,db.count("SELECT inventory_version FROM li_p0_sku_control WHERE sku_id=?",sku));
        String request=req();var second=concurrentStock(owner,sku,2,request,12);assertEquals(100,second.stream().filter(s->s==200).count());assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_inventory_adjustment WHERE sku_id=? AND request_id=?",sku,request));assertEquals(12,db.count("SELECT quantity FROM li_goods_sku WHERE id=?",sku));assertEquals(3,db.count("SELECT inventory_version FROM li_p0_sku_control WHERE sku_id=?",sku));observations.add(map("case","100-inventory-concurrency","differentRequests",map("success",1,"conflict",99),"sameRequest",map("successResponses",100,"ledgerRows",1),"skuId",sku));
    }
    static List<Integer> concurrentStock(OwnerFixture owner,String sku,long version,String sameRequest,int target)throws Exception{
        var ready=new java.util.concurrent.CountDownLatch(100);var release=new java.util.concurrent.CountDownLatch(1);try(var executor=java.util.concurrent.Executors.newFixedThreadPool(100)){
            var futures=new ArrayList<java.util.concurrent.Future<Integer>>();for(int i=0;i<100;i++)futures.add(executor.submit(()->{ready.countDown();assertTrue(release.await(30,java.util.concurrent.TimeUnit.SECONDS));String route=Stage1Routes.STORE+"/skus/"+sku+"/stock",body=write(map("requestId",sameRequest==null?req():sameRequest,"expectedVersion",version,"targetQuantity",target,"reason","S1_TEST100并发屏障"));int status=deployedHttp?liveResponse("PUT",route,owner.login.session(),"application/json",body.getBytes(StandardCharsets.UTF_8)).statusCode():mvc.perform(put(route).header("accessToken",owner.login.session().accessToken()).contentType("application/json").content(body)).andReturn().getResponse().getStatus();assertTrue(status==200||status==409,"Unexpected concurrency status "+status);return status;}));assertTrue(ready.await(30,java.util.concurrent.TimeUnit.SECONDS));release.countDown();var statuses=new ArrayList<Integer>();for(var future:futures)statuses.add(future.get(90,java.util.concurrent.TimeUnit.SECONDS));return statuses;
        }
    }
    @Test void realGoodsMockPrecheckAggregatesQuantitiesRequiresInputAndDoesNotWrite()throws Exception{
        var owner=ownerFixture();String agreement=agreementFixture(owner,0,LocalDate.now(Stage1Rules.ZONE),null);ruleFixture(owner,agreement,0);deliveryFixture(owner);String config=channelConfigFixture(1000),relation=channelRelationFixture(owner,config);var current=relationCommand(relation,1,"submit");for(int i=0;i<3;i++)current=relationCommand(relation,current.get("version").getAsLong(),"query");relationCommand(relation,current.get("version").getAsLong(),"enable");
        var created=call(post(Stage1Routes.STORE+"/goods"),owner.login.session(),map("requestId",req(),"payload",goodsPayload(unitFixture(),upload(owner.login.session(),Stage1Routes.STORE,"GOODS"),4)),200);String goods=created.getAsJsonObject("goods").get("id").getAsString(),sku=created.getAsJsonArray("skus").get(0).getAsJsonObject().get("id").getAsString();call(post(Stage1Routes.STORE+"/goods/"+goods+"/submit"),owner.login.session(),map("requestId",req(),"expectedVersion",0),200);call(post(Stage1Routes.MANAGER+"/goods/"+goods+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"contentVersion",0),200);call(post(Stage1Routes.STORE+"/goods/"+goods+"/list"),owner.login.session(),map("requestId",req(),"expectedVersion",2),200);
        String code=req();codes.put(code,"resident_"+req());var resident=residentLogin.login(code).session();var detail=call(get(Stage1Routes.BUYER+"/goods/"+goods),resident,null,200);assertTrue(detail.get("available").getAsBoolean());assertFalse(detail.toString().contains("costCent"));assertFalse(detail.toString().contains("rateBps"));assertEquals(1,call(get(Stage1Routes.BUYER+"/stores/"+owner.store+"/goods"),resident,null,200).get("total").getAsInt());
        var command=map("storeId",owner.store,"items",List.of(map("skuId",sku,"quantity",2),map("skuId",sku,"quantity",2)),"method","MERCHANT_DELIVERY");var precheck=call(post(Stage1Routes.BUYER+"/precheck"),resident,command,200);assertEquals("INPUT_REQUIRED",precheck.get("status").getAsString());assertEquals(1,precheck.getAsJsonArray("items").size());command.put("address",map("provinceCode","990000","cityCode","990100","districtCode","990101"));
        long operations=db.count("SELECT COUNT(*) FROM li_p0_operation"),audit=db.count("SELECT COUNT(*) FROM li_p0_audit"),outbox=db.count("SELECT COUNT(*) FROM li_p0_outbox"),ledger=db.count("SELECT COUNT(*) FROM li_p0_inventory_adjustment");var redisBefore=new TreeMap<String,String>();for(String key:Objects.requireNonNull(redis.keys("*")))redisBefore.put(key,digest(Objects.requireNonNull(redis.dump(key))));
        long observedBefore=observedSqlReads.get();forbidSqlWrites.set(true);try{for(int i=0;i<100;i++){precheck=call(post(Stage1Routes.BUYER+"/precheck"),resident,command,200);assertEquals("PASS",precheck.get("status").getAsString());}}finally{forbidSqlWrites.remove();}assertTrue(observedSqlReads.get()>observedBefore+100);
        assertEquals(operations,db.count("SELECT COUNT(*) FROM li_p0_operation"));assertEquals(audit,db.count("SELECT COUNT(*) FROM li_p0_audit"));assertEquals(outbox,db.count("SELECT COUNT(*) FROM li_p0_outbox"));assertEquals(ledger,db.count("SELECT COUNT(*) FROM li_p0_inventory_adjustment"));assertEquals(4,db.count("SELECT quantity FROM li_goods_sku WHERE id=?",sku));assertEquals(1,db.count("SELECT inventory_version FROM li_p0_sku_control WHERE sku_id=?",sku));var redisAfter=new TreeMap<String,String>();for(String key:Objects.requireNonNull(redis.keys("*")))redisAfter.put(key,digest(Objects.requireNonNull(redis.dump(key))));assertEquals(redisBefore,redisAfter);
        command.put("items",List.of(map("skuId",sku,"quantity",3),map("skuId",sku,"quantity",2)));assertTrue(call(post(Stage1Routes.BUYER+"/precheck"),resident,command,200).getAsJsonArray("reasonCodes").toString().contains("OUT_OF_STOCK"));command.put("mode","REAL");call(post(Stage1Routes.BUYER+"/precheck"),resident,command,422);command.remove("mode");command.put("storeId",ownerFixture().store);call(post(Stage1Routes.BUYER+"/precheck"),resident,command,404);
        observations.add(map("case","native-goods-mock-precheck","goodsId",goods,"skuId",sku,"repeatPass",100,"jdbcStatementsObserved",observedSqlReads.get()-observedBefore,"nonReadSqlRejectedByProbe",true,"operationsAuditOutboxInventoryUnchanged",true,"redisDumpHashesUnchanged",true,"realChannelVerified",false));
    }
    static String unitFixture() throws Exception {String name;int attempts=0;do{if(++attempts>8)throw new IllegalStateException("TEST_UNIT_NAME_EXHAUSTED");name="S"+UUID.randomUUID().toString().replace("-","").substring(0,9);}while(db.count("SELECT COUNT(*) FROM li_goods_unit WHERE name=?",name)>0);var unit=call(post(Stage1Routes.MANAGER+"/units"),operator,map("requestId",req(),"name",name,"reason","S1_TEST单位"),200);String id=unit.get("id").getAsString();call(post(Stage1Routes.MANAGER+"/units/"+id+"/enable"),operator,map("requestId",req(),"expectedVersion",0,"reason","S1_TEST启用"),200);return id;}
    static Map<String,Object> deliveryMethods(){return map("MERCHANT_DELIVERY",map("enabled",true,"feeCent",0,"districtCodes",List.of("990101")),"EXPRESS",map("enabled",false),"COMMUNITY_PICKUP",map("enabled",false),"STORE_REDEMPTION",map("enabled",false));}
    static void deliveryFixture(OwnerFixture owner) throws Exception {call(put(Stage1Routes.STORE+"/fulfillment"),owner.login.session(),map("requestId",req(),"expectedVersion",0,"methods",deliveryMethods(),"reason","S1_TEST合成区县配送"),200);}
    static Object goodsPayload(String unit,String photo,int quantity){return map("name","S1_TEST原生商品","categoryId",category,"unitId",unit,"galleryFileIds",List.of(photo),"detailBlocks",List.of(map("type","text","text","S1_TEST受控文字 <script>仅作为文字显示</script>")),"methods",List.of("MERCHANT_DELIVERY"),"skus",List.of(map("code","S1_"+db.id(),"specifications",List.of(),"priceCent",1,"costCent",0,"initialQuantity",quantity,"weightGram",0)));}
    static String createPointFixture(OwnerFixture owner,String type) throws Exception {return call(post(Stage1Routes.STORE+"/points"),owner.login.session(),map("requestId",req(),"type",type,"name","S1_TEST合成点位","provinceCode","990000","cityCode","990100","districtCode","990101","address","S1_TEST合成地址二号","contactName","合成联系人","phone","13800000000","opening",map("weekdays",List.of(1,2,3,4,5,6,7),"allDay",false,"start","22:00","end","06:00","crossDay",true),"reason","S1_TEST创建默认禁用"),200).get("id").getAsString();}
    static String channelConfigFixture(int cap) throws Exception {
        for(var old:db.rows("SELECT id,name,config_version FROM li_p0_channel_config WHERE active_route=?",settings.getEnvironment())){assertTrue(id(old,"name").startsWith("S1_TEST"));call(post(Stage1Routes.MANAGER+"/channel-configs/"+id(old,"id")+"/disable"),channel,map("requestId",req(),"expectedVersion",number(old,"config_version"),"reason","S1_TEST下一合成用例"),200);}
        var row=call(post(Stage1Routes.MANAGER+"/channel-configs"),channel,map("requestId",req(),"name","S1_TEST持久渠道","mode","MOCK","providerMchid","MOCK_PROVIDER_"+testId,"paymentAppId","MOCK_APP_"+testId,"mockMaxRateBps",cap,"reason","S1_TEST明确模拟上限"),200);String id=row.get("id").getAsString();call(post(Stage1Routes.MANAGER+"/channel-configs/"+id+"/check"),channel,map("requestId",req(),"expectedVersion",0,"reason","S1_TEST配置校验"),200);call(post(Stage1Routes.MANAGER+"/channel-configs/"+id+"/enable"),channel,map("requestId",req(),"expectedVersion",1,"reason","S1_TEST启用本机Mock"),200);return id;
    }
    static String channelRelationFixture(OwnerFixture owner,String config) throws Exception {
        var row=call(post(Stage1Routes.MANAGER+"/submerchants"),channel,map("requestId",req(),"storeId",owner.store,"configId",config,"reason","S1_TEST独立进件"),200);String id=row.get("id").getAsString();call(put(Stage1Routes.MANAGER+"/submerchants/"+id+"/materials"),channel,map("requestId",req(),"expectedVersion",0,"materials",channelMaterialsFixture(owner),"reason","S1_TEST仅合成资料"),200);return id;
    }
    static JsonObject relationCommand(String id,long version,String action) throws Exception {return call(post(Stage1Routes.MANAGER+"/submerchants/"+id+"/"+action),channel,map("requestId",req(),"expectedVersion",version,"reason","S1_TEST渠道"+action),200).getAsJsonObject("relation");}
    static Object channelMaterialsFixture(OwnerFixture owner) throws Exception {
        var profile=call(get(Stage1Routes.STORE+"/profile"),owner.login.session(),null,200);String license=profile.getAsJsonObject("qualifications").getAsJsonArray("versions").get(0).getAsJsonObject().getAsJsonObject("details").getAsJsonArray("fileIds").get(0).getAsString(),front=upload(channel,Stage1Routes.MANAGER,"CHANNEL"),back=upload(channel,Stage1Routes.MANAGER,"CHANNEL");
        return map("schemaVersion",ChannelMaterials.SCHEMA,"authorizationBasis","S1_TEST合成授权无真实个人资料","licenseFileId",license,"legalId",map("type","MAINLAND_IDCARD","name","合成人员","number","110101199001010011","frontFileId",front,"backFileId",back,"validFrom","2020-01-01","validThrough","2035-01-01","longTerm",false),"soleBeneficialOwner",true,"contact",map("type","LEGAL","name","合成人员","phone","13800000000"),"account",map("type","PUBLIC","name",profile.get("legalName").getAsString(),"number","1234567890123456","bankCode","1002","branchRequired",true,"bankName","S1_TEST合成支行"),"sales",map("name","S1_TEST合成商城","url","https://synthetic.invalid/shop"),"shortName","S1_TEST合成","settlementId",719,"industryName","S1_TEST合成行业");
    }
    static String ruleFixture(OwnerFixture owner,String agreement,int rate) throws Exception {
        var payload=map("name","S1_TEST合成规则","rateBps",rate,"baseCode",AgreementService.BASE,"roundCode",AgreementService.ROUND,"startDate",LocalDate.now(Stage1Rules.ZONE).toString(),"endDate",LocalDate.now(Stage1Rules.ZONE).plusDays(90).toString(),"commissionClause","S1_TEST明确"+rate+"基点条款","triggerBasis","ORDER_COMPLETED_NO_BLOCKING_AFTERSALE","executionIntent",true);var rule=call(post(Stage1Routes.MANAGER+"/commission-rules"),channel,map("requestId",req(),"storeId",owner.store,"agreementId",agreement,"businessType","MALL","payload",payload),200);String id=rule.get("id").getAsString();call(post(Stage1Routes.MANAGER+"/commission-rules/"+id+"/publish"),channel,map("requestId",req(),"expectedVersion",0),200);return id;
    }
    static String agreementFixture(OwnerFixture owner,int rate,LocalDate start,String previous) throws Exception {
        var agreement=createAgreement(owner,rate,start,previous);String id=agreement.get("id").getAsString();call(post(Stage1Routes.MANAGER+"/agreements/"+id+"/confirm"),operator,map("requestId",req(),"expectedVersion",0),200);return id;
    }
    static JsonObject createAgreement(OwnerFixture owner,int rate,LocalDate start,String previous) throws Exception {
        var profile=call(get(Stage1Routes.STORE+"/profile"),owner.login.session(),null,200);String file=upload(operator,Stage1Routes.MANAGER,"AGREEMENT");
        var payload=map("name","S1_TEST合成合作协议","platformLegalName","S1_TEST合成平台主体","merchantLegalName",profile.get("legalName").getAsString(),"merchantLegalCode",profile.get("legalCode").getAsString(),"categoryIds",List.of(category),"startDate",start.toString(),"endDate",start.plusYears(1).toString(),"fileIds",List.of(file),"confirmationReference","S1_TEST合成双方确认依据","rateBps",rate,"baseCode",AgreementService.BASE,"roundCode",AgreementService.ROUND,"commissionClause","S1_TEST明确"+rate+"基点条款","refundSummary","S1_TEST退款按未来冻结依据处理");
        return call(post(Stage1Routes.MANAGER+"/agreements"),operator,map("requestId",req(),"storeId",owner.store,"businessType","MALL","predecessorId",previous,"payload",payload),200);
    }
    record OwnerFixture(String open,String store,MerchantWechatLoginService.LoginResult login) { }
    static OwnerFixture ownerFixture() throws Exception {
        String open="owner_"+UUID.randomUUID().toString().replace("-","");var applicant=login(open);String file=upload(applicant.session(),Stage1Routes.APPLICANT);
        var created=call(post(Stage1Routes.APPLICANT+"/application"),applicant.session(),map("requestId",req(),"payload",payload(file)),200);String id=created.get("id").getAsString();
        var submitted=call(post(Stage1Routes.APPLICANT+"/application/"+id+"/submit"),applicant.session(),map("requestId",req(),"expectedVersion",0),200);
        call(post(Stage1Routes.MANAGER+"/applications/"+id+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"revision",1),200);
        return new OwnerFixture(open,submitted.get("storeId").getAsString(),login(open));
    }
    static MerchantWechatLoginService.LoginResult login(String open){String code=req();codes.put(code,open);return login.login(code);}
    static SessionTokens manager(String role){String id=db.id();db.update("INSERT INTO li_admin_user(id,create_by,username,nick_name,status,delete_flag,is_super) VALUES(?,'S1_TEST',?,?,1,0,0)",id,"s1_test_"+id,"S1_TEST");db.update("INSERT INTO li_user_role(id,user_id,role_id) VALUES(?,?,?)",db.id(),id,role);return sessions.create(new SessionIdentity(id,SessionDomain.MANAGER,apps.application(SessionDomain.MANAGER),null,null));}
    static String upload(SessionTokens token,String prefix) throws Exception {
        return upload(token,prefix,"QUALIFICATION");
    }
    static String upload(SessionTokens token,String prefix,String purpose) throws Exception {
        var image=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",image);
        if(deployedHttp){
            String boundary="S1_"+req();var bytes=new ByteArrayOutputStream();
            bytes.write(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"metadata\"\r\n\r\n"+write(map("requestId",req(),"purpose",purpose))+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));bytes.write(image.toByteArray());bytes.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
            return live("POST",prefix+"/files",token,"multipart/form-data; boundary="+boundary,bytes.toByteArray(),200).get("id").getAsString();
        }
        var result=mvc.perform(multipart(prefix+"/files").file(new MockMultipartFile("file","synthetic.png","image/png",image.toByteArray())).param("metadata",write(map("requestId",req(),"purpose",purpose))).header("accessToken",token.accessToken())).andReturn();
        return checked(result,200).get("id").getAsString();
    }
    static JsonObject call(MockHttpServletRequestBuilder request,SessionTokens token,Object body,int expected) throws Exception {
        if(token!=null)request.header("accessToken",token.accessToken());if(body!=null)request.contentType("application/json").content(write(body));
        var servlet=request.buildRequest(new MockServletContext());JsonObject result;
        if(deployedHttp){String query=servlet.getParameterMap().entrySet().stream().flatMap(entry->Arrays.stream(entry.getValue()).map(value->java.net.URLEncoder.encode(entry.getKey(),StandardCharsets.UTF_8)+"="+java.net.URLEncoder.encode(value,StandardCharsets.UTF_8))).collect(java.util.stream.Collectors.joining("&"));result=live(servlet.getMethod(),servlet.getRequestURI()+(query.isEmpty()?"":"?"+query),token,servlet.getContentType(),servlet.getContentAsByteArray(),expected);}
        else result=checked(mvc.perform(request).andReturn(),expected);
        if(recoveryCapture!=null&&expected==200&&body!=null&&object(body).has("requestId"))recoveryCapture.add(map("method",servlet.getMethod(),"route",servlet.getRequestURI(),"accessToken",token.accessToken(),"body",object(body),"expected",result.deepCopy()));
        return result;
    }
    static JsonObject live(String method,String route,SessionTokens token,String type,byte[] body,int expected)throws Exception{
        var response=liveResponse(method,route,token,type,body);var json=object(response.body());assertEquals(expected,response.statusCode(),method+" "+route.split("\\?")[0]+" code="+json.get("code"));if(expected>=400)return json;assertTrue(json.get("success").getAsBoolean());return json.has("result")&&json.get("result").isJsonObject()?json.getAsJsonObject("result"):json;
    }
    static java.net.http.HttpResponse<String> liveResponse(String method,String route,SessionTokens token,String type,byte[] body)throws Exception{
        String base=crossHostHttp?(route.startsWith("/buyer/")?"https://localhost:19084/api":route.startsWith("/manager/")?"http://localhost:19082/api":"https://localhost:19083/api"):("http://127.0.0.1:"+(route.startsWith("/buyer/")?19081:19080));
        var builder=java.net.http.HttpRequest.newBuilder(java.net.URI.create(base+route)).timeout(Duration.ofSeconds(45));if(token!=null)builder.header("accessToken",token.accessToken());if(type!=null)builder.header("Content-Type",type);builder.method(method,body==null?java.net.http.HttpRequest.BodyPublishers.noBody():java.net.http.HttpRequest.BodyPublishers.ofByteArray(body));var response=(crossHostHttp?crossHostClient:liveClient).send(builder.build(),java.net.http.HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        observations.add(map("transport",crossHostHttp?"ACTUAL_TEST_HOST_HTTP_HTTPS":"ACTUAL_HTTP","host",base,"method",method,"path",route.split("\\?")[0],"status",response.statusCode()));return response;
    }
    static void enableCrossHostTransport()throws Exception{
        var trust=java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());trust.load(null,null);
        try(var input=Files.newInputStream(run.resolve("private/tls/ca.pem"))){trust.setCertificateEntry("owned-stage1-test-ca",java.security.cert.CertificateFactory.getInstance("X.509").generateCertificate(input));}
        var tm=javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());tm.init(trust);var tls=javax.net.ssl.SSLContext.getInstance("TLS");tls.init(null,tm.getTrustManagers(),null);
        crossHostClient=java.net.http.HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(10)).followRedirects(java.net.http.HttpClient.Redirect.NEVER).build();crossHostHttp=true;deployedHttp=true;
    }
    static JsonObject checked(MvcResult response,int expected){
        String text=new String(response.getResponse().getContentAsByteArray(),StandardCharsets.UTF_8);JsonObject json=text.isBlank()?new JsonObject():object(text);
        observations.add(map("path",response.getRequest().getRequestURI(),"method",response.getRequest().getMethod(),"status",response.getResponse().getStatus(),"code",json.get("code")));
        var error=response.getResolvedException();String diagnostic=error==null?"":error.getClass().getSimpleName()+" "+Arrays.stream(error.getStackTrace()).filter(s->s.getClassName().startsWith("cn.lili.modules.community.p0.")).limit(4).map(StackTraceElement::toString).toList();
        assertEquals(expected,response.getResponse().getStatus(),"Unexpected HTTP status; code="+json.get("code")+" "+diagnostic);
        if(expected>=400)return json;assertTrue(json.get("success").getAsBoolean());
        return json.has("result")&&json.get("result").isJsonObject()?json.getAsJsonObject("result"):json;
    }
    static java.sql.Connection observedConnection(java.sql.Connection target){return (java.sql.Connection)java.lang.reflect.Proxy.newProxyInstance(Stage1PersistenceTest.class.getClassLoader(),new Class[]{java.sql.Connection.class},(proxy,method,args)->{try{Object value=method.invoke(target,args);if(value instanceof java.sql.Statement statement){String prepared=args!=null&&args.length>0&&args[0] instanceof String s?s:null;Class<?> type=value instanceof java.sql.CallableStatement?java.sql.CallableStatement.class:value instanceof java.sql.PreparedStatement?java.sql.PreparedStatement.class:java.sql.Statement.class;return java.lang.reflect.Proxy.newProxyInstance(Stage1PersistenceTest.class.getClassLoader(),new Class[]{type},(p,m,a)->{if(m.getName().startsWith("execute")||m.getName().equals("addBatch")){String sql=prepared!=null?prepared:a!=null&&a.length>0&&a[0] instanceof String s?s:"";if(forbidSqlWrites.get()){String verb=sql.stripLeading().split("\\s+",2)[0].toUpperCase(Locale.ROOT);assertEquals("SELECT",verb,"Consumer query attempted a non-SELECT JDBC statement");observedSqlReads.incrementAndGet();}if(sqlObserver.get()!=null)sqlObserver.get().before(sql);}try{return m.invoke(statement,a);}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});}return value;}catch(java.lang.reflect.InvocationTargetException e){throw e.getCause();}});}
    static String req(){return "s1_"+UUID.randomUUID().toString().replace("-","");}
    static Object payload(String file){
        String code="91"+db.id().substring(4),alphabet="0123456789ABCDEFGHJKLMNPQRTUWXY";code=code.substring(0,17);int[] weights={1,3,9,27,19,26,16,17,20,29,25,13,8,24,10,30,28};int sum=0;for(int i=0;i<17;i++)sum+=alphabet.indexOf(code.charAt(i))*weights[i];code+=alphabet.charAt((31-sum%31)%31);
        String today=LocalDate.now(Stage1Rules.ZONE).toString(),end=LocalDate.now(Stage1Rules.ZONE).plusYears(1).toString();
        return map("subjectType","ENTERPRISE","legalName","S1_TEST合成主体","legalCode",code,"legalPerson","合成人员","storeName","S1_TEST合成店铺","contactName","合成联系人","phone","010-12345678","provinceCode","990000","cityCode","990100","districtCode","990101","address","S1_TEST合成地址一号","description","合成测试资料","categoryIds",List.of(category),"qualifications",List.of(map("type","BUSINESS_LICENSE","number",code,"holder","S1_TEST合成主体","issuer","S1_TEST签发方","validFrom",today,"validThrough",end,"longTerm",false,"fileIds",List.of(file),"categoryIds",List.of())));
    }
}
