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


import cn.lili.modules.community.p0.stage2.*;
import cn.lili.modules.order.order.mapper.*;
import cn.lili.modules.order.trade.mapper.OrderLogMapper;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
/** Owned S2 real MySQL/MyBatis/Redis + production Session filter.
 * Supply setup reuses Stage1 fixture helpers only inside the S2 schema. No real WeChat or S1 data. */
@EnabledIfSystemProperty(named="s2.integration",matches="owned-run")
@Timeout(value=5,unit=java.util.concurrent.TimeUnit.MINUTES)
class Stage2PersistenceTest {
    static boolean stage3DependencyMode,stage45DependencyMode;
    static void initializeStage45Dependencies()throws Exception{stage45DependencyMode=true;initialize();}
    static void initializeStage3Dependencies()throws Exception{stage3DependencyMode=true;initialize();}
    static final Clock oldClock=clock;static final MutableClock simulation=new MutableClock();static Stage2RecoveryWorker worker;
    static class MutableClock extends Clock{volatile long offset;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return new Clock(){public ZoneId getZone(){return zone;}public Clock withZone(ZoneId other){return MutableClock.this.withZone(other);}public Instant instant(){return MutableClock.this.instant();}};}public Instant instant(){return Instant.now().plusSeconds(offset);}void advance(long seconds){offset+=seconds;}}
    @BeforeEach void resetClock(){simulation.offset=0;mvc=transactionMvc;}
    static Stage2Settings s2Settings;static Stage2Transactions s2tx;static Stage2Inventory s2inventory;static Stage2TradeService s2trades;static PersistentPaymentMock paymentMock;static Stage2PaymentService s2payments;static MockMvc supplyMvc,transactionMvc;
    static final Set<String> recoveryMembers=new TreeSet<>(),recoveryStores=new TreeSet<>();
    static Instant recoveryAdmittedAt;
    static void writeRecoveryScope(){try{
        var skus=new TreeSet<String>();for(String store:recoveryStores)for(var row:db.rows("SELECT id FROM li_goods_sku WHERE store_id=?",store))skus.add(id(row,"id"));
        Path file=run.resolve("tmp/recovery-scope-"+testId+".json");
        byte[] bytes=write(map("runId",settings.getRunId(),"admittedAt",recoveryAdmittedAt,"evidence","Stage2PersistenceTest explicit returned fixture IDs: "+testId,"members",recoveryMembers,"stores",recoveryStores,"skus",skus)).getBytes(StandardCharsets.UTF_8);
        Files.write(file,bytes);s2Settings.setRecoveryScopeFile(file.toString());s2Settings.setRecoveryScopeSha256(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
    }catch(Exception failure){throw new IllegalStateException("TEST_RECOVERY_ENROLLMENT_FAILED",failure);}}
    @BeforeAll static void initialize() throws Exception {
        clock=simulation;run=Path.of(Objects.requireNonNull(System.getenv(stage45DependencyMode?"S45_RUN":stage3DependencyMode?"S3_RUN":"S2_RUN"))).toRealPath();
        assertTrue(run.getFileName().toString().matches(stage45DependencyMode?"S45_[0-9]{8}_[0-9]{6}":stage3DependencyMode?"S3_[0-9]{8}_[0-9]{6}":"S2_[0-9]{8}_[0-9]{6}"));
        assertEquals(Path.of(stage45DependencyMode?"D:/git/daguanglu-p0-stage45/.stage45-runtime":stage3DependencyMode?"D:/git/daguanglu-p0-stage3/.stage3-runtime":"D:/git/daguanglu-p0/.stage2-runtime").toRealPath(),run.getParent());
        assertEquals(run.getFileName().toString(),object(Files.readString(run.resolve("owner.json"))).get("runId").getAsString());
        var resource=object(Files.readString(run.resolve("runtime/resources.json")));
        var secret=object(Files.readString(run.resolve("private/db.json")));
        int mysqlPort=stage45DependencyMode?19606:stage3DependencyMode?19506:19406,redisPort=stage45DependencyMode?19679:stage3DependencyMode?19579:19479;String database=stage45DependencyMode?"dgl_p0_s45":stage3DependencyMode?"dgl_p0_s3":"dgl_p0_s2",appUser=stage45DependencyMode?"s45_app":stage3DependencyMode?"s3_app":"s2_app",mockUser=stage45DependencyMode?"s45_mock":stage3DependencyMode?"s3_mock":"s2_mock";
        pool=new com.zaxxer.hikari.HikariDataSource();pool.setJdbcUrl("jdbc:mysql://127.0.0.1:"+mysqlPort+"/"+database+"?useUnicode=true&characterEncoding=UTF-8&serverTimezone=UTC&connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true&useSSL=false&allowPublicKeyRetrieval=true");pool.setUsername(appUser);pool.setPassword(secret.get("app").getAsString());pool.setMaximumPoolSize(24);pool.setMinimumIdle(2);pool.setConnectionTimeout(30000);pool.setPoolName("Stage2OwnedIntegration");var source=new DelegatingDataSource(pool){@Override public java.sql.Connection getConnection()throws java.sql.SQLException{return observedConnection(super.getConnection());}};
        db=new Stage1Db(new JdbcTemplate(source));assertEquals(resource.get("mysqlUuid").getAsString(),db.required("SELECT @@server_uuid AS uuid").get("uuid"));
        assertEquals(database,db.required("SELECT DATABASE() AS name").get("name"));
        assertTrue(db.count("SELECT COUNT(*) FROM li_p0_schema_history WHERE status='DONE'")>=9);
        settings=new Stage1Settings();settings.setEnabled(false);settings.setStage2(true);settings.setDatabase(database);settings.setRunId(run.getFileName().toString());settings.setMysqlUuid(resource.get("mysqlUuid").getAsString());settings.setFileRoot(run.resolve("files").toString());settings.setManagerRoleIds(Set.of("710000000000000001","710000000000000002","710000000000000003"));if(stage3DependencyMode)settings.setManagerRoleIds(Set.of("710000000000000001","710000000000000002","710000000000000003","730000000000000010"));
        s2Settings=new Stage2Settings();s2Settings.setEnabled(true);s2Settings.setDatabase(database);s2Settings.setStage3ResourceMode(stage3DependencyMode);s2Settings.setStage45ResourceMode(stage45DependencyMode);s2Settings.setRunId(settings.getRunId());s2Settings.setMysqlUuid(settings.getMysqlUuid());s2Settings.setFileRoot(settings.getFileRoot());s2Settings.setMockJdbcUrl("jdbc:mysql://127.0.0.1:"+mysqlPort+"/"+database+"_mock?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");s2Settings.setMockUsername(mockUser);s2Settings.setMockPassword(secret.get("mock").getAsString());s2Settings.setMockControlKey(secret.get("mockControl").getAsString());
        assertEquals(stage45DependencyMode?45:stage3DependencyMode?3:2,object(Files.readString(run.resolve("owner.json"))).get("stage").getAsInt());
        new Stage2ResourceGuard(db,s2Settings,new MockEnvironment().withProperty("p0.stage3.enabled",String.valueOf(stage3DependencyMode||stage45DependencyMode)).withProperty("p0.stage45.enabled",String.valueOf(stage45DependencyMode)).withProperty("spring.data.redis.host","127.0.0.1").withProperty("spring.data.redis.port",String.valueOf(redisPort)).withProperty("lili.message.consumer.enabled","false").withProperty("lili.promotion.delay-listener.enabled","false").withProperty("lili.mqtt.enabled","false").withProperty("lili.schedule.every-minute-cron","-").withProperty("lili.schedule.every-hour-cron","-").withProperty("lili.schedule.every-day-cron","-")).afterSingletonsInstantiated();
        testId=UUID.randomUUID().toString().replace("-","");recoveryMembers.clear();recoveryStores.clear();recoveryAdmittedAt=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);writeRecoveryScope();previousSnowflake=ReflectionTestUtils.getField(SnowFlake.class,"snowflake");SnowFlake.initialize(29,30);
        var configuration=new MybatisConfiguration();configuration.setMapUnderscoreToCamelCase(true);configuration.setJdbcTypeForNull(JdbcType.NULL);configuration.setLogImpl(NoLoggingImpl.class);
        var global=new GlobalConfig();global.setMetaObjectHandler(new MyMetaObjectHandler());
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(configuration);factory.setGlobalConfig(global);
        var sf=factory.getObject();for(Class<?> type:List.of(MemberMapper.class,ClerkMapper.class,StoreMapper.class,AdminUserMapper.class,MerchantGrantMapper.class,WechatIdentityMapper.class,MemberIdentityLinkMapper.class,GoodsMapper.class,GoodsSkuMapper.class,TradeMapper.class,OrderMapper.class,OrderItemMapper.class,OrderLogMapper.class))sf.getConfiguration().addMapper(type);
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
        if(stage45DependencyMode){
            settings.setManagerRoleIds(Set.of("710000000000000001","710000000000000002","710000000000000003","730000000000000010","744500000000004001","744500000000004002","744500000000004003"));
            var stage3Auth=new cn.lili.modules.community.p0.stage3.Stage3Authorization(db,access,sql.getMapper(MerchantGrantMapper.class));
            var stage45Auth=new cn.lili.modules.community.p0.stage45.Stage45Authorization(db,access,sql.getMapper(MerchantGrantMapper.class));
            var extensions=new StaticListableBeanFactory(Map.of("stage3",stage3Auth,"stage45",stage45Auth));
            ReflectionTestUtils.setField(access,"stage3",extensions.getBeanProvider(cn.lili.modules.community.p0.stage3.Stage3Authorization.class));
            ReflectionTestUtils.setField(access,"stage45",extensions.getBeanProvider(cn.lili.modules.community.p0.stage45.Stage45Authorization.class));
        }
        var proofBeans=new StaticListableBeanFactory(Map.of("proof",access));
        var merchant=new MerchantAuthorizationService(apps,memberService,clerkService,storeService,sql.getMapper(MerchantGrantMapper.class),proofBeans.getBeanProvider(MerchantOwnershipProof.class));merchant.setLifecycle(access);
        var verifierBeans=new StaticListableBeanFactory(Map.of("verifier",verifier));
        authorization=new CurrentSessionAuthorization(apps,verifierBeans.getBeanProvider(SessionIdentityVerifier.class),memberService,clerkService,storeService,adminService,mock(MenuService.class),mock(StoreMenuService.class),new ManagerTokenGenerate(),new StoreTokenGenerate());
        authorization.setMerchantAuthorization(merchant);authorization.setSessionAccessExtension(access);
        var redisConfiguration=new RedisStandaloneConfiguration("127.0.0.1",redisPort);redisConfiguration.setPassword(secret.get("redis").getAsString());redisFactory=new LettuceConnectionFactory(redisConfiguration);redisFactory.afterPropertiesSet();redisFactory.start();redis=new StringRedisTemplate(redisFactory);
        try(var connection=redisFactory.getConnection()){assertEquals("PONG",connection.ping());assertEquals(resource.get("redisRunId").getAsString(),Objects.requireNonNull(connection.serverCommands().info("server")).getProperty("run_id"));}
        Path keyFile=run.resolve("private/session-signing-key");if(!Files.exists(keyFile)){byte[] bytes=new byte[48];new SecureRandom().nextBytes(bytes);Files.writeString(keyFile,Base64.getEncoder().encodeToString(bytes),StandardOpenOption.CREATE_NEW);Arrays.fill(bytes,(byte)0);}
        var jwt=new JWTTokenProperties();jwt.setSecretKey(Files.readString(keyFile));keys=new SecretKeyUtil(jwt);jwt.setSecretKey(null);
        sessions=new SessionService(new RedisSessionStore(redis),keys,authorization,clock);
        var gateway=new WechatCodeGateway(){public Identity authenticate(String code){String open=codes.remove(code);if(open==null)throw new ResidentLoginException(ResidentLoginException.Code.WECHAT_REJECTED);return new Identity(apps.application(SessionDomain.RESIDENT),open,null);}public Identity authenticate(String code,SessionDomain domain){assertEquals(SessionDomain.APPLICANT,domain);String open=codes.remove(code);if(open==null)throw new ResidentLoginException(ResidentLoginException.Code.WECHAT_REJECTED);return new Identity(apps.application(domain),open,null);}};
        login=new MerchantWechatLoginService(gateway,identities,sessions,apps,merchantProperties,merchant);
        residentLogin=new ResidentWechatLoginService(gateway,identities,sessions,apps,residentProperties);
        var context=new Stage1Context(authorization,access);var mutations=new Stage1Mutations(db,context,manager,clock);var files=new Stage1Files(db,context,access,mutations,settings,clock);var regions=new Stage1Regions(db);
        var rules=new AdmissionRules(db,regions,files,clock);var templates=new StaffTemplates(db,clock);if(stage3DependencyMode||stage45DependencyMode)ReflectionTestUtils.setField(templates,"stage3Enabled",true);if(stage45DependencyMode)ReflectionTestUtils.setField(templates,"stage45Enabled",true);admission=new AdmissionService(db,context,mutations,rules,regions,templates,clock);staff=new StaffService(db,context,mutations,templates,admission,keys,clock);
        var agreements=new AgreementService(db,context,mutations,files,clock);commissions=new CommissionRuleService(db,context,mutations,agreements,clock);eligibility=new EligibilityService(db,agreements,commissions,settings,manager,clock,apps);
        var vault=new ChannelVault(settings);var channelConfigs=new ChannelConfigService(db,context,mutations,settings,vault,manager,clock,apps);var channelMaterials=new ChannelMaterials(db,files,clock);channels=new ChannelService(db,context,access,mutations,channelConfigs,channelMaterials,vault,files,settings,manager,clock);receivers=new ReceiverService(db,context,access,mutations,channelConfigs,vault,manager,clock);
        var catalog=new CatalogService(db,context,mutations,clock);var fulfillment=new FulfillmentService(db,context,mutations,regions,clock);var goodsRules=new GoodsContentRules(db,files,catalog);
        var nativeGoods=new GoodsServiceImpl();ReflectionTestUtils.setField(nativeGoods,"baseMapper",sql.getMapper(GoodsMapper.class));var nativeSkus=new GoodsSkuServiceImpl();ReflectionTestUtils.setField(nativeSkus,"baseMapper",sql.getMapper(GoodsSkuMapper.class));var projector=new NativeGoodsProjector(db,nativeGoods,nativeSkus,goodsRules,catalog,files,settings,clock);var goods=new GoodsManagementService(db,context,access,mutations,goodsRules,projector,eligibility,fulfillment,clock);var inventory=new InventoryService(db,context,mutations,projector,eligibility,settings,redis,clock);
                s2tx=new Stage2Transactions(db,context,s2Settings,manager,clock);
        s2inventory=new Stage2Inventory(db,s2Settings,projector,clock);ReflectionTestUtils.setField(inventory,"stage2Inventory",s2inventory);
        var filters=new EnumMap<SessionDomain,SessionHttpFilter>(SessionDomain.class);for(var domain:SessionDomain.values())filters.put(domain,new SessionHttpFilter(sessions,authorization,apps,domain));
        Filter dispatch=(request,response,chain)->{
            var req=(jakarta.servlet.http.HttpServletRequest)request;String path=req.getRequestURI();SessionDomain domain=path.startsWith(Stage1Routes.APPLICANT)?SessionDomain.APPLICANT:path.startsWith("/store/")?SessionDomain.STORE:path.startsWith("/manager/")?SessionDomain.MANAGER:SessionDomain.RESIDENT;
            try{filters.get(domain).doFilter(request,response,chain);}finally{SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}
        };
        var consumer=new ConsumerGoodsService(db,context,eligibility,fulfillment,settings,manager,clock);
        mvc=MockMvcBuilders.standaloneSetup(new ApplicantController(admission),new ManagerAdmissionController(admission,context),new MerchantProfileController(admission,context,access),new StaffController(staff),new InvitationController(staff),new Stage1CommonController(mutations,files,regions,context),new ManagerQualificationController(admission),new ManagerCooperationController(agreements,commissions),new EligibilityController(context,eligibility,agreements,commissions),new ManagerChannelController(channelConfigs,channels,receivers),new CatalogController(catalog),new FulfillmentController(fulfillment,context),new GoodsManagementController(goods,inventory),new ConsumerGoodsController(consumer),new MerchantWorkbench(context,access,eligibility,goods,clock)).setControllerAdvice(new Stage1Responses()).addFilters(new Stage1EntranceGuard(),dispatch).build();
        supplyMvc=mvc;
        var s2cart=new Stage2Cart(db,s2tx,consumer,clock);var s2addresses=new Stage2Addresses(db,s2tx,clock);
        var s2quotes=new Stage2QuoteService(db,s2tx,s2cart,s2addresses,consumer,fulfillment,commissions,clock);if(stage45DependencyMode)ReflectionTestUtils.setField(s2quotes,"stage45Enabled",true);
        s2trades=new Stage2TradeService(db,s2tx,s2quotes,s2cart,s2inventory,sql.getMapper(TradeMapper.class),sql.getMapper(OrderMapper.class),sql.getMapper(OrderItemMapper.class),sql.getMapper(OrderLogMapper.class),clock);
        paymentMock=new PersistentPaymentMock(s2Settings,clock);s2payments=new Stage2PaymentService(db,s2tx,s2trades,s2quotes,s2inventory,paymentMock,s2Settings,clock);
        worker=new Stage2RecoveryWorker(s2tx,db,s2Settings,s2trades,s2payments,paymentMock,redis,manager,clock);
        transactionMvc=MockMvcBuilders.standaloneSetup(new Stage2BuyerController(new Stage2Catalog(db,consumer,eligibility,s2tx,fulfillment),s2cart,s2addresses,s2tx,files),new Stage2CheckoutController(s2quotes,s2trades,s2payments),new Stage2PaymentControlController(paymentMock,s2payments),new Stage2StockController(inventory),new GoodsManagementController(goods,inventory)).setControllerAdvice(new Stage1Responses()).addFilters(new Stage2EntranceGuard(),dispatch).build();
        reviewer=manager("710000000000000001");operator=manager("710000000000000002");channel=manager("710000000000000003");
        category=db.id();db.nativeInsert("li_category",map("id",category,"delete_flag",false,"create_by","S1_TEST","name","S1_TEST_"+testId,"parent_id",0,"level",1));
        db.update("INSERT INTO li_p0_catalog_control(object_type,object_id,enabled,normalized_name) VALUES('CATEGORY',?,TRUE,?)",category,"S1_TEST_"+testId);
        observations.add(map("fixture",testId,"mode","real MySQL/MyBatis/Redis and Session filter; synthetic Code gateway only","databaseUuid",settings.getMysqlUuid(),"category",category));
    }
    @AfterAll static void closeS2() throws Exception { if(run!=null)Files.writeString(run.resolve("evidence/stage2-persistence-"+(testId==null?"setup-failed":testId)+".json"),write(observations));if(redisFactory!=null)redisFactory.destroy();if(keys!=null)keys.destroy();if(pool!=null)pool.close();if(previousSnowflake!=null)ReflectionTestUtils.setField(SnowFlake.class,"snowflake",previousSnowflake);clock=oldClock;SecurityContextHolder.clearContext();RequestContextHolder.resetRequestAttributes();}

    static OwnerFixture readyStore(String config)throws Exception {
        var owner=ownerFixture();deliveryFixture(owner);String agreement=agreementFixture(owner,0,LocalDate.now(ZoneOffset.UTC),null);ruleFixture(owner,agreement,0);
        String relation=channelRelationFixture(owner,config);var r=relationCommand(relation,1,"submit");for(int i=0;i<3;i++)r=relationCommand(relation,r.get("version").getAsLong(),"query");relationCommand(relation,r.get("version").getAsLong(),"enable");
        var payload=object(goodsPayload(unitFixture(),upload(owner.login().session(),Stage1Routes.STORE,"GOODS"),10));payload.getAsJsonArray("skus").get(0).getAsJsonObject().addProperty("priceCent",1001);
        var created=call(post(Stage1Routes.STORE+"/goods"),owner.login().session(),map("requestId",req(),"payload",payload),200);String goods=created.getAsJsonObject("goods").get("id").getAsString();call(post(Stage1Routes.STORE+"/goods/"+goods+"/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",0),200);call(post(Stage1Routes.MANAGER+"/goods/"+goods+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"contentVersion",0),200);call(post(Stage1Routes.STORE+"/goods/"+goods+"/list"),owner.login().session(),map("requestId",req(),"expectedVersion",2),200);
        assertTrue(Boolean.TRUE.equals(eligibility.evaluate(owner.store(),category,null).get("MOCK_READY")));recoveryStores.add(owner.store());writeRecoveryScope();return owner;
    }
    static ResidentWechatLoginService.LoginResult residentFixture(){String code=req();codes.put(code,"S2_TEST_resident_"+UUID.randomUUID());var login=residentLogin.login(code);db.update("INSERT INTO li_p0_s2_member_scope VALUES(?,?,'TRANSACT','S2_TEST owned fixture')",login.memberId(),settings.getRunId());recoveryMembers.add(login.memberId());writeRecoveryScope();return login;}
    @Test void persistentCartAddressQuoteNativeTradeAndIndependentPaymentCloseLoop()throws Exception {
        mvc=supplyMvc;String config=channelConfigFixture(10000);var a=readyStore(config);var b=readyStore(config);var resident=residentFixture();var other=residentFixture();String skuA=id(db.required("SELECT id FROM li_goods_sku WHERE store_id=?",a.store()),"id"),skuB=id(db.required("SELECT id FROM li_goods_sku WHERE store_id=?",b.store()),"id");mvc=transactionMvc;
        var body=map("operationId",req(),"skuId",skuA,"quantity",2);var added=call(post(Stage2Routes.BASE+"/cart/items"),resident.session(),body,200);call(post(Stage2Routes.BASE+"/cart/items"),resident.session(),body,200);assertEquals(2,db.count("SELECT quantity FROM li_p0_cart_item WHERE member_id=? AND sku_id=?",resident.memberId(),skuA));
        String cartId=added.getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString();long cartVersion=added.getAsJsonArray("items").get(0).getAsJsonObject().get("version").getAsLong();
        call(put(Stage2Routes.BASE+"/cart/items/"+cartId),other.session(),map("operationId",req(),"quantity",1,"expectedVersion",cartVersion),404);
        var address=call(post(Stage2Routes.BASE+"/addresses"),resident.session(),map("operationId",req(),"addressBookVersion",0,"name","S2_TEST联系人","mobile","13800000000","provinceCode","990000","cityCode","990100","districtCode","990101","detail","S2_TEST测试地址一号"),200);String addressId=address.get("defaultAddressId").getAsString();
        var selection=map("items",List.of(map("skuId",skuA,"quantity",2,"cartId",cartId,"expectedVersion",cartVersion),map("skuId",skuB,"quantity",1)),"stores",List.of(map("storeId",a.store(),"method","MERCHANT_DELIVERY","addressId",addressId,"addressVersion",0,"contact",map("name","S2_TEST联系人","mobile","13800000000")),map("storeId",b.store(),"method","MERCHANT_DELIVERY","addressId",addressId,"addressVersion",0,"contact",map("name","S2_TEST联系人","mobile","13800000000"))));
        var quote=call(post(Stage2Routes.BASE+"/checkout/quotes"),resident.session(),selection,200);assertEquals(3003,quote.get("amountCent").getAsLong());String intent="intent_"+req();var create=map("intentId",intent,"quoteId",quote.get("quoteId").getAsString(),"confirmationHash",quote.get("confirmationHash").getAsString());
        var made=call(post(Stage2Routes.BASE+"/checkout/intents"),resident.session(),create,200);assertEquals("SUCCEEDED",made.get("status").getAsString());String trade=made.get("tradeId").getAsString();assertEquals(trade,call(post(Stage2Routes.BASE+"/checkout/intents"),resident.session(),create,200).get("tradeId").getAsString());assertEquals(2,db.count("SELECT COUNT(*) FROM li_p0_order_snapshot WHERE trade_id=?",trade));assertEquals(3,db.count("SELECT SUM(quantity) FROM li_p0_inventory_hold WHERE trade_id=? AND status='HELD'",trade));assertEquals(8,db.count("SELECT quantity FROM li_goods_sku WHERE id=?",skuA));
        var prepare=call(post(Stage2Routes.BASE+"/trades/"+trade+"/payment/prepare"),resident.session(),map("operationId",req()),200);assertTrue(prepare.get("launchReady").getAsBoolean());
        var paid=call(post(Stage2Routes.BASE+"/trades/"+trade+"/payment/launch"),resident.session(),map("operationId",req()),200);assertEquals("PAID",paid.getAsJsonObject("trade").get("status").getAsString());assertEquals(0,s2inventory.held(skuA));assertEquals(8,db.count("SELECT quantity FROM li_goods_sku WHERE id=?",skuA));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_cart_consumption WHERE trade_id=?",trade));
        call(get(Stage2Routes.BASE+"/trades/"+trade+"/summary"),other.session(),null,404);
        assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_trade_state WHERE trade_id=? AND status<>'PAID'",trade));
        observations.add(map("case","S2-native-multistore-cart-payment","tradeId",trade,"orders",2,"backend","real MySQL/MyBatis/Redis + production Session filter","channel","independent persistent Mock","identity","synthetic gateway only"));
    }

    @Test void catalogSearchIncludesPublicStoreNameAndRejectsUnknownCategory()throws Exception{
        var p=purchase(1);String name="S2_FIND_"+req();db.update("UPDATE li_store SET store_name=? WHERE id=?",name,p.owner.store());
        var result=call(get(Stage2Routes.BASE+"/goods").param("keyword",name).param("pageSize","1"),p.resident.session(),null,200);assertEquals(1,result.get("total").getAsInt());assertEquals(p.owner.store(),result.getAsJsonArray("records").get(0).getAsJsonObject().get("storeId").getAsString());
        assertEquals(0,call(get(Stage2Routes.BASE+"/goods").param("keyword",name).param("pageSize","1").param("page","2"),p.resident.session(),null,200).getAsJsonArray("records").size());call(get(Stage2Routes.BASE+"/goods").param("categoryId",db.id()),p.resident.session(),null,404);
    }
    @Test void cartRetainsPartialStockAndRejectsDisabledSelectedSku()throws Exception{
        var p=purchase(2);var added=call(post(Stage2Routes.BASE+"/cart/items"),p.resident.session(),map("operationId",req(),"skuId",p.sku,"quantity",2),200);String cart=added.getAsJsonArray("items").get(0).getAsJsonObject().get("id").getAsString();
        db.update("UPDATE li_goods_sku SET quantity=1 WHERE id=?",p.sku);var row=call(get(Stage2Routes.BASE+"/cart"),p.resident.session(),null,200).getAsJsonArray("items").get(0).getAsJsonObject();assertFalse(row.get("available").getAsBoolean());assertEquals(2,row.get("quantity").getAsInt());assertTrue(row.getAsJsonArray("reasonCodes").asList().stream().anyMatch(e->"OUT_OF_STOCK".equals(e.getAsString())));
        call(put(Stage2Routes.BASE+"/cart/items/"+cart),p.resident.session(),map("operationId",req(),"expectedVersion",0,"quantity",1),200);assertEquals(1,db.count("SELECT version FROM li_p0_cart_item WHERE id=?",cart));
        db.update("UPDATE li_p0_sku_control SET enabled=0 WHERE sku_id=?",p.sku);row=call(get(Stage2Routes.BASE+"/cart"),p.resident.session(),null,200).getAsJsonArray("items").get(0).getAsJsonObject();assertFalse(row.get("available").getAsBoolean());assertTrue(row.getAsJsonArray("reasonCodes").asList().stream().anyMatch(e->"SKU_DISABLED".equals(e.getAsString())));call(put(Stage2Routes.BASE+"/cart/items/"+cart),p.resident.session(),map("operationId",req(),"expectedVersion",1,"quantity",1),409);
    }
    record Purchase(OwnerFixture owner,ResidentWechatLoginService.LoginResult resident,String sku,String address,JsonObject quote,Map<String,Object> create){}
    static Purchase purchase(int quantity)throws Exception{mvc=supplyMvc;var owner=readyStore(channelConfigFixture(10000));String sku=id(db.required("SELECT id FROM li_goods_sku WHERE store_id=?",owner.store()),"id");var resident=residentFixture();mvc=transactionMvc;var address=call(post(Stage2Routes.BASE+"/addresses"),resident.session(),map("operationId",req(),"addressBookVersion",0,"name","S2_TEST居民","mobile","13800000000","provinceCode","990000","cityCode","990100","districtCode","990101","detail","S2_TEST一号楼一单元"),200);String aid=address.get("defaultAddressId").getAsString();var quote=quote(resident,owner.store(),sku,quantity,aid);return new Purchase(owner,resident,sku,aid,quote,map("intentId","intent_"+req(),"quoteId",quote.get("quoteId").getAsString(),"confirmationHash",quote.get("confirmationHash").getAsString()));}
    static JsonObject quote(ResidentWechatLoginService.LoginResult r,String store,String sku,int quantity,String address)throws Exception{return call(post(Stage2Routes.BASE+"/checkout/quotes"),r.session(),map("items",List.of(map("skuId",sku,"quantity",quantity)),"stores",List.of(map("storeId",store,"method","MERCHANT_DELIVERY","addressId",address,"addressVersion",0,"contact",map("name","S2_TEST居民","mobile","13800000000")))),200);}
    static String create(Purchase p)throws Exception{return call(post(Stage2Routes.BASE+"/checkout/intents"),p.resident.session(),p.create,200).get("tradeId").getAsString();}
    static JsonObject prepare(Purchase p,String trade)throws Exception{return call(post(Stage2Routes.BASE+"/trades/"+trade+"/payment/prepare"),p.resident.session(),map("operationId",req()),200);}
    static JsonObject cancel(Purchase p,String trade)throws Exception{return call(post(Stage2Routes.BASE+"/trades/"+trade+"/cancel"),p.resident.session(),map("operationId",req()),200);}
    static void scenario(String trade,String value,boolean release){String combine=id(db.required("SELECT combine_no FROM li_p0_payment WHERE trade_id=?",trade),"combine_no");paymentMock.scenario(map("combineNo",combine,"scenario",value,"releaseDelayed",release),s2Settings.getMockControlKey());}
    static void deliver(String trade){var request=object(db.required("SELECT request_json FROM li_p0_payment WHERE trade_id=?",trade).get("request_json"));s2payments.notification(paymentMock.query(request));}
    @Test void catalogOptionsAndFiniteSessionBoundaries()throws Exception{var p=purchase(1);var listed=call(get(Stage2Routes.BASE+"/goods").param("sort","PRICE_ASC").param("pageSize","20"),p.resident.session(),null,200);assertTrue(listed.getAsJsonArray("records").size()>0);call(get(Stage2Routes.BASE+"/catalog"),p.resident.session(),null,200);call(get(Stage2Routes.BASE+"/regions"),p.resident.session(),null,200);var options=call(post(Stage2Routes.BASE+"/checkout/options"),p.resident.session(),map("items",List.of(map("skuId",p.sku,"quantity",1))),200);assertEquals(p.owner.store(),options.getAsJsonArray("stores").get(0).getAsJsonObject().get("storeId").getAsString());call(post(Stage2Routes.BASE+"/checkout/intents"),p.resident.session(),map("intentId","invalid_"+req(),"quoteId",db.id(),"confirmationHash","a".repeat(64)),200);assertEquals("FAILED",id(db.required("SELECT status FROM li_p0_checkout_intent WHERE member_id=? ORDER BY created_at DESC LIMIT 1",p.resident.memberId()),"status"));call(post("/buyer/trade/cart/cart"),p.resident.session(),map(),403);}
    @Test void cancelBeforeDispatchReleasesOnceAndDoesNotRewriteSnapshot()throws Exception{var p=purchase(2);String trade=create(p),snapshot=id(db.required("SELECT content_hash FROM li_p0_order_snapshot WHERE trade_id=?",trade),"content_hash");assertEquals("CLOSED",cancel(p,trade).getAsJsonObject("trade").get("status").getAsString());cancel(p,trade);assertEquals(10,db.count("SELECT quantity FROM li_goods_sku WHERE id=?",p.sku));assertEquals(0,s2inventory.held(p.sku));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='RELEASE'",trade));assertEquals(snapshot,id(db.required("SELECT content_hash FROM li_p0_order_snapshot WHERE trade_id=?",trade),"content_hash"));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_payment WHERE trade_id=?",trade));}
    @Test void closeUnknownUsesBackoffAndPaidFactWinsPendingClose()throws Exception{var p=purchase(2);String trade=create(p);prepare(p,trade);scenario(trade,"CLOSE_UNKNOWN",false);var result=cancel(p,trade);assertEquals("CLOSE_CONFIRMING",result.getAsJsonObject("trade").get("status").getAsString());assertEquals(2,s2inventory.held(p.sku));for(int gap:new int[]{5,15,30,60}){var before=db.required("SELECT query_step,next_query_at FROM li_p0_trade_state WHERE trade_id=?",trade);long step=number(before,"query_step");s2payments.recover(trade);assertEquals(step,db.count("SELECT query_step FROM li_p0_trade_state WHERE trade_id=?",trade));simulation.advance(gap+1);s2payments.recover(trade);}simulation.advance(12);worker.attention();assertNotNull(db.required("SELECT attention_at FROM li_p0_trade_state WHERE trade_id=?",trade).get("attention_at"));assertEquals(2,s2inventory.held(p.sku));scenario(trade,"DELAYED_SUCCESS",true);deliver(trade);deliver(trade);assertEquals("PAID",id(db.required("SELECT status FROM li_p0_trade_state WHERE trade_id=?",trade),"status"));assertEquals(8,db.count("SELECT quantity FROM li_goods_sku WHERE id=?",p.sku));assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='CAPTURE'",trade));}
    @Test void lateSuccessAfterReleasedStockStaysProtectedAndNeverDeductsAgain()throws Exception{var p=purchase(1);String trade=create(p);prepare(p,trade);cancel(p,trade);assertEquals(10,db.count("SELECT quantity FROM li_goods_sku WHERE id=?",p.sku));scenario(trade,"LATE_SUCCESS",true);deliver(trade);deliver(trade);var state=db.required("SELECT * FROM li_p0_trade_state WHERE trade_id=?",trade);assertEquals("LATE_PAYMENT_AFTER_RELEASE",id(state,"protection"));assertEquals("SUCCEEDED",id(state,"payment_status"));assertEquals(10,db.count("SELECT quantity FROM li_goods_sku WHERE id=?",p.sku));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_inventory_event WHERE trade_id=? AND event_type='CAPTURE'",trade));}
    @Test void t3ChangedPriceClosesOriginalAndManualTargetRespectsHeldSpace()throws Exception{var p=purchase(2);String trade=create(p);long version=db.count("SELECT inventory_version FROM li_p0_sku_control WHERE sku_id=?",p.sku);redis.opsForValue().set(cn.lili.modules.goods.service.GoodsSkuService.getStockCacheKey(p.sku),"9999999");call(put(Stage1Routes.STORE+"/skus/"+p.sku+"/stock"),p.owner.login().session(),map("requestId",req(),"expectedVersion",version,"targetQuantity",9999999,"reason","S2_TEST有预占上界"),409);call(put(Stage1Routes.STORE+"/skus/"+p.sku+"/stock"),p.owner.login().session(),map("requestId",req(),"expectedVersion",version,"targetQuantity",5,"reason","S2_TEST人工设置可售"),200);assertEquals(5,db.count("SELECT quantity FROM li_goods_sku WHERE id=?",p.sku));db.update("UPDATE li_goods_sku SET price=11.01 WHERE id=?",p.sku);var result=prepare(p,trade);assertFalse(result.get("launchReady").getAsBoolean());assertEquals("CLOSED",result.getAsJsonObject("trade").get("status").getAsString());assertEquals("RECONFIRM_REQUIRED",result.getAsJsonObject("trade").get("closeReason").getAsString());assertEquals(7,db.count("SELECT quantity FROM li_goods_sku WHERE id=?",p.sku));worker.project(p.sku);assertEquals("7",redis.opsForValue().get(cn.lili.modules.goods.service.GoodsSkuService.getStockCacheKey(p.sku)));}
    @Test void twoResidentsCompeteForLastUnitWithoutOversell()throws Exception{var p=purchase(1);var other=residentFixture();db.update("UPDATE li_goods_sku SET quantity=1 WHERE id=?",p.sku);var address=call(post(Stage2Routes.BASE+"/addresses"),other.session(),map("operationId",req(),"addressBookVersion",0,"name","S2_TEST二号","mobile","13800000000","provinceCode","990000","cityCode","990100","districtCode","990101","detail","S2_TEST二号楼一单元"),200);var q=quote(other,p.owner.store(),p.sku,1,address.get("defaultAddressId").getAsString());var otherCommand=map("intentId","intent_"+req(),"quoteId",q.get("quoteId").getAsString(),"confirmationHash",q.get("confirmationHash").getAsString());var executor=java.util.concurrent.Executors.newFixedThreadPool(2);try{var a=executor.submit(()->call(post(Stage2Routes.BASE+"/checkout/intents"),p.resident.session(),p.create,200));var b=executor.submit(()->call(post(Stage2Routes.BASE+"/checkout/intents"),other.session(),otherCommand,200));var results=List.of(a.get(),b.get());assertEquals(1,results.stream().filter(r->"SUCCEEDED".equals(r.get("status").getAsString())).count());assertEquals(1,results.stream().filter(r->"FAILED".equals(r.get("status").getAsString())).count());assertEquals(0,db.count("SELECT quantity FROM li_goods_sku WHERE id=?",p.sku));assertEquals(1,s2inventory.held(p.sku));}finally{executor.shutdownNow();}}
    @Test void inconsistentChannelEvidenceIsProtectedUntilCorrectedTrustedFact()throws Exception{var p=purchase(1);String trade=create(p);prepare(p,trade);scenario(trade,"WRONG_AMOUNT",false);call(post(Stage2Routes.BASE+"/trades/"+trade+"/payment/launch"),p.resident.session(),map("operationId",req()),200);assertEquals("PAYMENT_EVIDENCE_MISMATCH",id(db.required("SELECT protection FROM li_p0_trade_state WHERE trade_id=?",trade),"protection"));assertEquals(1,s2inventory.held(p.sku));var combine=id(db.required("SELECT combine_no FROM li_p0_payment WHERE trade_id=?",trade),"combine_no");paymentMock.scenario(map("combineNo",combine,"scenario","SUCCESS","correctEvidence",true),s2Settings.getMockControlKey());deliver(trade);assertEquals("PAID",id(db.required("SELECT status FROM li_p0_trade_state WHERE trade_id=?",trade),"status"));}

    @Test void partialNativeWriteRollsBackAllFactsAndOriginalIntentRecovers()throws Exception{var p=purchase(2);var real=(OrderItemMapper)ReflectionTestUtils.getField(s2trades,"items");var interrupted=org.mockito.Mockito.spy(real);org.mockito.Mockito.doAnswer(invocation->{invocation.callRealMethod();throw new org.springframework.dao.DataAccessResourceFailureException("S2_TEST failure after native item");}).when(interrupted).insert(org.mockito.ArgumentMatchers.any(cn.lili.modules.order.order.entity.dos.OrderItem.class));ReflectionTestUtils.setField(s2trades,"items",interrupted);try{var result=call(post(Stage2Routes.BASE+"/checkout/intents"),p.resident.session(),p.create,200);assertEquals("VERIFYING",result.get("status").getAsString());assertEquals(0,db.count("SELECT COUNT(*) FROM li_trade WHERE member_id=?",p.resident.memberId()));assertEquals(0,db.count("SELECT COUNT(*) FROM li_p0_order_snapshot WHERE member_id=?",p.resident.memberId()));assertEquals(0,s2inventory.held(p.sku));assertEquals(10,db.count("SELECT quantity FROM li_goods_sku WHERE id=?",p.sku));}finally{ReflectionTestUtils.setField(s2trades,"items",real);}var recovered=call(post(Stage2Routes.BASE+"/checkout/intents"),p.resident.session(),p.create,200);assertEquals("SUCCEEDED",recovered.get("status").getAsString());assertEquals(1,db.count("SELECT COUNT(*) FROM li_trade WHERE member_id=?",p.resident.memberId()));assertEquals(2,s2inventory.held(p.sku));}
    @Test void cartConsumedVersionCannotBuildAgainButNewlyAddedQuantityCan()throws Exception{var p=purchase(1);var added=call(post(Stage2Routes.BASE+"/cart/items"),p.resident.session(),map("operationId",req(),"skuId",p.sku,"quantity",2),200);var line=added.getAsJsonArray("items").get(0).getAsJsonObject();String cid=line.get("id").getAsString();long cv=line.get("version").getAsLong();var input=map("items",List.of(map("skuId",p.sku,"quantity",1,"cartId",cid,"expectedVersion",cv)),"stores",List.of(map("storeId",p.owner.store(),"method","MERCHANT_DELIVERY","addressId",p.address,"addressVersion",0,"contact",map("name","S2_TEST居民","mobile","13800000000"))));var q=call(post(Stage2Routes.BASE+"/checkout/quotes"),p.resident.session(),input,200);var command=map("intentId","intent_"+req(),"quoteId",q.get("quoteId").getAsString(),"confirmationHash",q.get("confirmationHash").getAsString());String first=call(post(Stage2Routes.BASE+"/checkout/intents"),p.resident.session(),command,200).get("tradeId").getAsString();var repeated=new LinkedHashMap<>(command);repeated.put("intentId","intent_"+req());assertEquals("FAILED",call(post(Stage2Routes.BASE+"/checkout/intents"),p.resident.session(),repeated,200).get("status").getAsString());assertEquals(1,db.count("SELECT quantity FROM li_p0_cart_item WHERE id=?",cid));call(post(Stage2Routes.BASE+"/cart/items"),p.resident.session(),map("operationId",req(),"skuId",p.sku,"quantity",2),200);assertEquals(3,db.count("SELECT quantity FROM li_p0_cart_item WHERE id=?",cid));cancel(p,first);assertEquals(3,db.count("SELECT quantity FROM li_p0_cart_item WHERE id=?",cid));}
}
