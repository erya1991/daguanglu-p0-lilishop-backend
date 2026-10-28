package cn.lili.common.security.session;

import cn.lili.security.BuyerSecurityConfig;
import cn.lili.security.BuyerAuthenticationFilter;
import cn.lili.controller.passport.MemberBuyerController;
import cn.lili.controller.passport.ResidentProfileController;
import cn.lili.controller.passport.connect.ResidentWechatLoginController;
import cn.lili.common.properties.IgnoredUrlsProperties;
import cn.lili.common.security.CustomAccessDeniedHandler;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.modules.sms.SmsUtil;
import cn.lili.modules.verification.service.VerificationService;
import org.springframework.context.annotation.*;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.cors.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.bind.annotation.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.Filter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.common.properties.JWTTokenProperties;
import cn.lili.common.security.session.http.*;
import cn.lili.common.security.token.SecretKeyUtil;
import cn.lili.common.utils.SnowFlake;
import cn.lili.modules.connect.entity.*;
import cn.lili.modules.connect.mapper.*;
import cn.lili.modules.connect.wechat.*;
import cn.lili.modules.file.entity.File;
import cn.lili.modules.file.mapper.FileMapper;
import cn.lili.modules.file.plugin.FilePluginFactory;
import cn.lili.modules.file.service.FileService;
import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.member.mapper.*;
import cn.lili.modules.member.profile.*;
import cn.lili.modules.member.service.*;
import cn.lili.modules.member.token.StoreTokenGenerate;
import cn.lili.modules.permission.service.*;
import cn.lili.modules.store.service.StoreService;
import cn.lili.modules.system.token.ManagerTokenGenerate;
import cn.lili.mybatis.mybatisplus.MyMetaObjectHandler;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.google.gson.Gson;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.type.JdbcType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.extension.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Explicitly authorized P03 API integration only. Explicit MVC beans, no component scanning, migration, official gateway,
 * file storage, native registration/bootstrap, scheduler, message consumer or listening business server is started here. The real
 * MyBatis mappers, transaction templates, P03 services and RedisSessionStore are exercised.
 * Every generated row id and Redis key is durably registered BEFORE the corresponding write.
 * Credentials are read only into this process from existing PREP protected files, never argv/log.
 */
@EnabledIfSystemProperty(named = "p03.api.integration", matches = "approved-prep")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(P03MvcPersistenceIntegrationTest.FailClosed.class)
@SuppressWarnings("unchecked")
class P03MvcPersistenceIntegrationTest {
    private static final Gson JSON = new Gson();
    private static final String RUN = UUID.randomUUID().toString().replace("-", "");
    private static final String APP = "p03api_" + RUN;
    private static final Map<String, Set<String>> ROWS = new LinkedHashMap<>();
    private static final Map<String, Set<String>> CONFIRMED_INSERTS = new LinkedHashMap<>();
    private static final Map<String, Integer> PRESENT_BEFORE_CLEANUP = new LinkedHashMap<>();
    private static final Map<String, Integer> DELETED_BY_TABLE = new LinkedHashMap<>();
    private static final Set<String> BASELINE_MEMBERS = new HashSet<>();
    private static final Map<String, Boolean> KEYS = new LinkedHashMap<>();
    private static final List<Map<String, Object>> RESULTS = new ArrayList<>();
    private static final List<Map<String, Object>> SQL_ERRORS = new ArrayList<>();
    private static final Map<String, String> PHONE_FIXTURES = new ConcurrentHashMap<>();
    private static final Map<String, WechatCodeGateway.Identity> CODE_FIXTURES = new ConcurrentHashMap<>();
    private static final Map<String, ResidentLoginException.Code> CODE_FAILURE_FIXTURES = new ConcurrentHashMap<>();
    private static final Set<String> REGISTERED_APPS = ConcurrentHashMap.newKeySet();
    private static final Set<String> REQUESTS = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean FAILED = new AtomicBoolean();
    private static final AtomicInteger INSERT_SUCCEEDED = new AtomicInteger();
    private static final ThreadLocal<Boolean> INVALID_MAPPING = ThreadLocal.withInitial(() -> false);
    private static final Set<String> USED_LOGIN_CODES = ConcurrentHashMap.newKeySet();
    private static final Set<String> USED_PHONE_CODES = ConcurrentHashMap.newKeySet();
    private static Path evidence;
    private static JdbcTemplate jdbc;
    private static DataSourceTransactionManager transactions;
    private static MemberMapper members;
    private static WechatIdentityMapper mappings;
    private static ResidentProfileMapper profileMapper;
    private static MemberIdentityLinkMapper links;
    private static FileMapper files;
    private static WechatIdentityService identities;
    private static ResidentWechatLoginService login;
    private static ResidentProfileService profile;
    private static ResidentAvatarService avatars;
    private static ResidentTransactions residentTransactions;
    private static ResidentProfileProperties profileProperties;
    private static SessionApplications apps;
    private static SessionService sessions;
    private static SecretKeyUtil signing;
    private static LettuceConnectionFactory redisFactory;
    private static StringRedisTemplate redis;
    private static RedisSessionStore actualStore;
    private static Object previousSnowflake;
    private static boolean setupComplete;
    private static int deletedRows, deletedKeys;
    private static AnnotationConfigWebApplicationContext context;
    private static MockMvc mvc;
    private static MemberService memberService;
    private static CurrentSessionAuthorization authorization;
    private static MemberLinkService linkService;
    private static ObjectProvider<MemberOwnershipProof> proofProvider;
    private static FilePluginFactory filePlugins;
    private static Cache cache;
    private static final Set<String> LEGACY_KEYS = ConcurrentHashMap.newKeySet();
    private static final Set<String> CREDENTIALS = ConcurrentHashMap.newKeySet();
    private static final List<Map<String,Object>> HTTP_RESULTS = Collections.synchronizedList(new ArrayList<>());
    private static final AtomicInteger FOREIGN_HANDLERS = new AtomicInteger();
    private static ListAppender<ILoggingEvent> logCapture;
    private static final Path BUYER_YAML = Path.of("D:/git/daguanglu-p0/lilishop-backend/buyer-api/src/main/resources/application.yml");
    private static List<String> ignoredUrls;

    static final class FailClosed implements TestWatcher {
        @Override public void testSuccessful(ExtensionContext context) { result(context, "PASS", null); }
        @Override public void testFailed(ExtensionContext context, Throwable cause) {
            FAILED.set(true); result(context, "FAIL", cause.getClass().getSimpleName());
        }
        @Override public void testAborted(ExtensionContext context, Throwable cause) { result(context, "NOT_RUN", null); }
        private static void result(ExtensionContext context, String outcome, String error) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("case", context.getRequiredTestMethod().getName().substring(0, 5));
            entry.put("result", outcome);
            if (error != null) entry.put("failureClass", error);
            synchronized (ROWS) { RESULTS.add(entry); journal(); }
        }
    }

    @BeforeAll static void initializeOnlyAfterVerifiedSchema() throws Exception {
        evidence = Path.of(Objects.requireNonNull(System.getProperty("p03.api.evidence"))).toAbsolutePath();
        require(Files.isDirectory(evidence), "EVIDENCE_DIRECTORY_REQUIRED");
        var gate = JSON.fromJson(Files.readString(evidence.resolve("access.json")), Map.class);
        require(Boolean.TRUE.equals(gate.get("passed")) && Boolean.TRUE.equals(gate.get("ddlVerified")), "ENVIRONMENT_GATE_REQUIRED");
        require("8.4.11".equals(gate.get("mysqlVersion")) && "daguanglu_f0103_dev".equals(gate.get("database"))
                && "127.0.0.1".equals(gate.get("mysqlHost")) && number(gate, "mysqlPort") == 13306
                && "7.2.16".equals(gate.get("redisVersion")) && "127.0.0.1".equals(gate.get("redisHost"))
                && number(gate, "redisPort") == 16379, "WRONG_TARGET");
        require(Duration.between(Instant.parse((String) gate.get("checkedAt")), Instant.now()).abs()
                .compareTo(Duration.ofHours(1)) < 0, "STALE_GATE");
        for (String table : List.of("li_member", "li_wechat_identity", "li_member_identity_link", "li_file")) {
            ROWS.put(table, new LinkedHashSet<>());
            CONFIRMED_INSERTS.put(table, new LinkedHashSet<>());
            PRESENT_BEFORE_CLEANUP.put(table, 0); DELETED_BY_TABLE.put(table, 0);
        }
        REGISTERED_APPS.add(APP); REGISTERED_APPS.add(APP + "_other"); journal();

        Properties mysql = controlledMysqlCredentials();
        var data = new DriverManagerDataSource();
        data.setDriverClassName("com.mysql.cj.jdbc.Driver");
        data.setUrl("jdbc:mysql://127.0.0.1:13306/daguanglu_f0103_dev?useSSL=false&allowPublicKeyRetrieval=true&characterEncoding=UTF-8&connectionTimeZone=UTC&connectTimeout=4000&socketTimeout=20000");
        data.setUsername(mysql.getProperty("user")); data.setPassword(mysql.getProperty("password")); mysql.clear();
        jdbc = new JdbcTemplate(data);
        assertEquals("8.4.11", jdbc.queryForObject("SELECT VERSION()", String.class));
        assertEquals("daguanglu_f0103_dev", jdbc.queryForObject("SELECT DATABASE()", String.class));
        assertEquals(2, count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('li_wechat_identity','li_member_identity_link')"));
        assertEquals(3, count("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='li_member' AND column_name IN ('profile_version','phone_request_id','phone_request_hash')"));
        assertEquals(1, count("SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='li_member' AND index_name='uk_member_mobile' AND non_unique=0"));
        BASELINE_MEMBERS.addAll(jdbc.queryForList("SELECT CAST(id AS CHAR) FROM li_member", String.class));
        assertEquals(1, BASELINE_MEMBERS.size(), "approved baseline has one protected synthetic seed");
        assertEquals(0, count("SELECT COUNT(*) FROM li_wechat_identity"));
        assertEquals(0, count("SELECT COUNT(*) FROM li_member_identity_link"));
        previousSnowflake = ReflectionTestUtils.getField(SnowFlake.class, "snowflake"); SnowFlake.initialize(30, 31);

        var config = new MybatisConfiguration();
        config.setMapUnderscoreToCamelCase(true); config.setJdbcTypeForNull(JdbcType.NULL); config.setLogImpl(NoLoggingImpl.class);
        var global = new GlobalConfig(); global.setMetaObjectHandler(new MyMetaObjectHandler());
        var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(data);
        factory.setConfiguration(config); factory.setGlobalConfig(global);
        var sqlFactory = factory.getObject();
        for (Class<?> mapper : List.of(MemberMapper.class, WechatIdentityMapper.class, ResidentProfileMapper.class,
                MemberIdentityLinkMapper.class, FileMapper.class)) sqlFactory.getConfiguration().addMapper(mapper);
        var template = new SqlSessionTemplate(sqlFactory);
        members = guarded(MemberMapper.class, template.getMapper(MemberMapper.class));
        mappings = guarded(WechatIdentityMapper.class, template.getMapper(WechatIdentityMapper.class));
        profileMapper = guarded(ResidentProfileMapper.class, template.getMapper(ResidentProfileMapper.class));
        links = guarded(MemberIdentityLinkMapper.class, template.getMapper(MemberIdentityLinkMapper.class));
        files = guarded(FileMapper.class, template.getMapper(FileMapper.class));
        transactions = new DataSourceTransactionManager(data); residentTransactions = new ResidentTransactions(transactions);

        apps = new SessionApplications(); apps.setResidentApplication(APP); apps.setMerchantApplication("p03api_merchant_" + RUN);
        apps.setManagerApplication("p03api_manager_" + RUN);
        var wechatProperties = new ResidentWechatProperties(); wechatProperties.setSchemaReady(true);
        wechatProperties.setEnabled(true); wechatProperties.setAppSecret(UUID.randomUUID().toString().replace("-", ""));
        identities = new WechatIdentityService(mappings, members, wechatProperties, transactions);
        profileProperties = new ResidentProfileProperties(); profileProperties.setSchemaReady(true);
        // No history-ready or upload-ready switch is enabled, even in this isolated test.
        var verifier = new WechatSessionIdentityVerifier(identities, apps, links, profileProperties);
        ObjectProvider<SessionIdentityVerifier> proof = mock(ObjectProvider.class); when(proof.getIfUnique()).thenReturn(verifier);
        memberService = mock(MemberService.class);
        when(memberService.getById(anyString())).thenAnswer(i -> members.selectById((String) i.getArgument(0)));
        authorization = new CurrentSessionAuthorization(apps, proof, memberService, mock(ClerkService.class), mock(StoreService.class),
                mock(AdminUserService.class), mock(MenuService.class), mock(StoreMenuService.class), new ManagerTokenGenerate(), new StoreTokenGenerate());
        var redisConfig = new RedisStandaloneConfiguration("127.0.0.1", 16379); redisConfig.setDatabase(0); redisConfig.setUsername("default");
        redisConfig.setPassword(Files.readString(Path.of("D:/git/daguanglu-p0-build/F01-03C2D/private/redis-password"), StandardCharsets.UTF_8));
        redisFactory = new LettuceConnectionFactory(redisConfig, LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(4)).shutdownTimeout(Duration.ofSeconds(1)).build());
        redisFactory.afterPropertiesSet(); redisFactory.start(); redis = new StringRedisTemplate(redisFactory);
        try (var connection = redisFactory.getConnection()) {
            assertEquals("PONG", connection.ping()); Properties info = connection.serverCommands().info("server");
            assertNotNull(info); assertEquals("7.2.16", info.getProperty("redis_version"));
            assertEquals(gate.get("redisRunId"), info.getProperty("run_id"));
        }
        actualStore = new RedisSessionStore(redis);
        byte[] secret = new byte[48]; new SecureRandom().nextBytes(secret);
        var jwt = new JWTTokenProperties(); jwt.setSecretKey(Base64.getEncoder().encodeToString(secret)); Arrays.fill(secret, (byte) 0);
        signing = new SecretKeyUtil(jwt); jwt.setSecretKey(null);
        sessions = new SessionService(guardedStore(), signing, authorization, Clock.systemUTC());
        WechatCodeGateway gateway = code -> {
            var failure = CODE_FAILURE_FIXTURES.get(code);
            if (failure != null) {
                if (!USED_LOGIN_CODES.add(code)) throw new ResidentLoginException(ResidentLoginException.Code.WECHAT_REJECTED);
                throw new ResidentLoginException(failure);
            }
            var identity = CODE_FIXTURES.get(code);
            if (identity == null || !USED_LOGIN_CODES.add(code)) throw new ResidentLoginException(ResidentLoginException.Code.WECHAT_REJECTED);
            return identity;
        };
        login = new ResidentWechatLoginService(gateway, identities, sessions, apps, wechatProperties);
        FileService fileService = mock(FileService.class);
        when(fileService.getById(anyString())).thenAnswer(i -> files.selectById((String) i.getArgument(0)));
        filePlugins = mock(FilePluginFactory.class); avatars = new ResidentAvatarService(fileService, filePlugins, profileProperties, identities);
        cache = mock(Cache.class); when(cache.get(CachePrefix.SENSITIVE.getPrefix())).thenReturn(List.of("synthetic_forbidden_word")); when(cache.hasKey(anyString())).thenAnswer(i -> LEGACY_KEYS.contains(i.getArgument(0)));
        WechatPhoneGateway phoneGateway = code -> {
            String phone = PHONE_FIXTURES.get(code);
            if (phone == null || !USED_PHONE_CODES.add(code)) throw new ResidentProfileException(ResidentProfileException.Code.PHONE_REJECTED);
            return phone;
        };
        profile = new ResidentProfileService(profileMapper, identities, profileProperties, residentTransactions, avatars, phoneGateway, cache);
        proofProvider = mock(ObjectProvider.class);
        linkService = new MemberLinkService(links, mappings, identities, profileMapper, profileProperties, residentTransactions, proofProvider, sessions);
        ObjectProvider<WechatLinkRecovery> recoveries = mock(ObjectProvider.class);
        when(recoveries.getIfUnique()).thenReturn(linkService);
        login = new ResidentWechatLoginService(gateway, identities, sessions, apps, wechatProperties, recoveries);
        startMvc();
        setupComplete = true; journal();
    }

    @BeforeEach void stopAfterUnexpectedFailure() { Assumptions.assumeFalse(FAILED.get(), "prior integration failure: dependent tests stopped"); }

    private static void startMvc() {
        // Parse ONLY ignored.urls from the actual Buyer source. Do not register its datasource,
        // MQ, scheduling, external client or other application configuration in this context.
        var yaml = new YamlPropertiesFactoryBean(); yaml.setResources(new FileSystemResource(BUYER_YAML));
        Properties values = Objects.requireNonNull(yaml.getObject());
        ignoredUrls = values.stringPropertyNames().stream().filter(k -> k.matches("ignored\\.urls\\[[0-9]+]"))
                .sorted(Comparator.comparingInt(k -> Integer.parseInt(k.substring(k.indexOf('[')+1,k.length()-1))))
                .map(values::getProperty).toList();
        require(!ignoredUrls.isEmpty() && ignoredUrls.contains("/buyer/passport/connect/**"),"ACTUAL_IGNORED_URLS_REQUIRED");
        values.clear();
        logCapture = new ListAppender<>(); logCapture.list = Collections.synchronizedList(new ArrayList<>()); logCapture.start();
        ((Logger)LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logCapture);
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        // Intentionally no component scan, Boot auto-configuration, scheduler, MQ or servlet port.
        context.register(MvcBeans.class, BuyerSecurityConfig.class); context.refresh();
        var chain = context.getBean("springSecurityFilterChain", FilterChainProxy.class);
        var filters = chain.getFilters("/buyer/passport/member");
        assertTrue(filters.stream().anyMatch(SessionHttpFilter.class::isInstance));
        assertTrue(filters.stream().anyMatch(BuyerAuthenticationFilter.class::isInstance));
        require(context.getBeanDefinitionNames().length < 150, "UNEXPECTED_CONTEXT_EXPANSION");
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(chain).build();
        try {
            Files.writeString(evidence.resolve("mvc-isolation.json"), JSON.toJson(Map.of(
                    "mode", "explicit Spring MVC + real BuyerSecurityConfig, no listening server",
                    "buyerFilters", filters.stream().map(f -> f.getClass().getName()).toList(),
                    "beans", Arrays.asList(context.getBeanDefinitionNames()),
                    "foreignDomains", "test-only chains with real SessionHttpFilter; not Manager/Store business API integration",
                    "ignoredUrlsSource", BUYER_YAML.toString(), "ignoredUrls", ignoredUrls)), StandardCharsets.UTF_8);
        } catch(Exception e) { throw new IllegalStateException("MVC_EVIDENCE_FAILED"); }
    }

    @Configuration(proxyBeanMethods = false) @EnableWebMvc
    static class MvcBeans {
        @Bean Cache cache() { return P03MvcPersistenceIntegrationTest.cache; }
        @Bean SecretKeyUtil keys() { return signing; }
        @Bean SessionApplications apps() { return P03MvcPersistenceIntegrationTest.apps; }
        @Bean SessionService sessions() { return P03MvcPersistenceIntegrationTest.sessions; }
        @Bean CurrentSessionAuthorization authorization() { return P03MvcPersistenceIntegrationTest.authorization; }
        @Bean MemberService members() { return memberService; }
        @Bean SmsUtil sms() { return mock(SmsUtil.class); }
        @Bean VerificationService verification() { return mock(VerificationService.class); }
        @Bean MemberShareLogService shareLog() { return mock(MemberShareLogService.class); }
        @Bean MemberShareRegisterService shareRegister() { return mock(MemberShareRegisterService.class); }
        @Bean MemberBuyerController memberController() { return new MemberBuyerController(); }
        @Bean ResidentWechatLoginController loginController() { return new ResidentWechatLoginController(login); }
        @Bean ResidentProfileController profileController() { return new ResidentProfileController(profile, avatars, linkService); }
        @Bean ForeignSentinel foreignSentinel() { return new ForeignSentinel(); }
        @Bean IgnoredUrlsProperties ignored() {
            var p = new IgnoredUrlsProperties();
            p.setUrls(new ArrayList<>(ignoredUrls)); return p;
        }
        @Bean CustomAccessDeniedHandler denied() { return new CustomAccessDeniedHandler(); }
        @Bean CorsConfigurationSource corsConfigurationSource() { return new UrlBasedCorsConfigurationSource(); }
        @Bean AuthenticationProvider provider() { return new AuthenticationProvider() {
            public Authentication authenticate(Authentication authentication) { throw new AssertionError("PASSWORD_PROVIDER_NOT_AUTHORIZED"); }
            public boolean supports(Class<?> type) { return true; }
        }; }
        // These are isolated shared-filter rejection probes, not production Manager API configuration.
        @Bean @org.springframework.core.annotation.Order(1)
        SecurityFilterChain applicantProbe(HttpSecurity http) throws Exception { return foreign(http, "/store/passport/applicant/**", SessionDomain.APPLICANT); }
        @Bean @org.springframework.core.annotation.Order(2)
        SecurityFilterChain storeProbe(HttpSecurity http) throws Exception { return foreign(http, "/store/**", SessionDomain.STORE); }
        @Bean @org.springframework.core.annotation.Order(3)
        SecurityFilterChain managerProbe(HttpSecurity http) throws Exception { return foreign(http, "/manager/**", SessionDomain.MANAGER); }
        private SecurityFilterChain foreign(HttpSecurity http,String path,SessionDomain domain) throws Exception {
            http.securityMatcher(path).authorizeHttpRequests(a -> a.anyRequest().authenticated())
                    .csrf(c -> c.disable()).sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .exceptionHandling(e -> e.authenticationEntryPoint((req,res,ex) -> res.setStatus(403)))
                    .addFilterBefore(new SessionHttpFilter(sessions, authorization, apps, domain), BasicAuthenticationFilter.class);
            return http.build();
        }
    }
    @RestController static class ForeignSentinel {
        @GetMapping({"/store/member/user", "/manager/passport/user/info", "/store/passport/applicant/identity"})
        String protectedEndpoint() { FOREIGN_HANDLERS.incrementAndGet(); throw new AssertionError("CROSS_DOMAIN_HANDLER_REACHED"); }
    }

    @Test @Order(1) void API01_firstAndRepeatLoginUseRealDatabaseAndSession() throws Exception {
        String open = "repeat_" + RUN;
        var one = apiLogin(open); var two = apiLogin(open);
        assertEquals(one.memberId(),two.memberId()); assertNotEquals(one.session().sid(),two.session().sid());
        assertNull(members.selectById(one.memberId()).getMobile());
        assertEquals(1,count("SELECT COUNT(*) FROM li_wechat_identity WHERE app_id=? AND open_id=?",APP,open));
        assertEquals(one.memberId(),verified(one).identity().subject());
        assertNotNull(actualStore.load(SessionDomain.RESIDENT,APP,one.session().sid()));
    }

    @Test @Order(2) void API01_invalidCodeSelectorsAndUnsupportedPhoneDoNotWrite() throws Exception {
        int rows = registered("li_member").size(), keys = KEYS.size();
        reject(post(SessionRoutes.RESIDENT_LOGIN).contentType("application/json").content(JSON.toJson(Map.of("loginCode",invalidCode()))),403,"WECHAT_REJECTED");
        for(String field:List.of("appId","openId","memberId","storeId","role")) {
            reject(post(SessionRoutes.RESIDENT_LOGIN).contentType("application/json").content(JSON.toJson(Map.of("loginCode",invalidCode(),field,"forged"))),400,"INVALID_REQUEST");
        }
        reject(post(SessionRoutes.RESIDENT_LOGIN).contentType("application/json").content(JSON.toJson(Map.of("loginCode",invalidCode(),"phoneCode",invalidCode()))),400,"PHONE_NOT_SUPPORTED");
        assertEquals(rows,registered("li_member").size()); assertEquals(keys,KEYS.size());
    }

    @Test @Order(3) void API01_realDatabaseFailureCannotIssueSession() throws Exception {
        int keys = KEYS.size(); Set<String> before = registered("li_member");
        INVALID_MAPPING.set(true);
        try { reject(post(SessionRoutes.RESIDENT_LOGIN).contentType("application/json").content(JSON.toJson(Map.of("loginCode",loginFixture("invalid_"+RUN)))),503,"LOGIN_UNAVAILABLE"); }
        finally { INVALID_MAPPING.remove(); }
        Set<String> attempted = registered("li_member"); attempted.removeAll(before);
        assertEquals(1,attempted.size()); assertEquals(0,existing("li_member",attempted)); assertEquals(keys,KEYS.size());
    }

    @Test @Order(4) void API02_profileProjectionAndSelectorsArePrivate() throws Exception {
        var one=apiFresh("profile"); var body=own(one);
        assertEquals(one.memberId(),body.get("memberId").getAsString()); assertFalse(body.get("phoneBound").getAsBoolean());
        assertTrue(!body.has("maskedMobile") || body.get("maskedMobile").isJsonNull());
        assertTrue(body.keySet().stream().allMatch(Set.of("memberId","nickname","avatar","maskedMobile","phoneBound","version","updatedAt")::contains));
        reject(get("/buyer/passport/member").header("accessToken",one.session().accessToken()).queryParam("memberId","123"),403,"SESSION_REJECTED");
        reject(get("/buyer/passport/member").header("accessToken",one.session().accessToken()).header("memberId","123"),400,"INVALID_REQUEST");
        status(get("/buyer/passport/member"),403);
    }

    @Test @Order(5) void API03_nicknameAvatarCasAndForbiddenFields() throws Exception {
        var one=apiFresh("edit"); var other=apiFresh("other"); var file=metadata(one.memberId());
        var result=success(json(put(SessionRoutes.RESIDENT_EDIT),one,Map.of("version",0,"nickname","接口合成居民","avatarFileId",file.getId())));
        assertEquals(1,result.get("version").getAsLong()); assertEquals("接口合成居民",own(one).get("nickname").getAsString());
        assertEquals(file.getUrl(),own(one).get("avatar").getAsString());
        reject(json(put(SessionRoutes.RESIDENT_EDIT),one,Map.of("version",0,"nickname","迟到更新")),409,"PROFILE_CONFLICT");
        reject(json(put(SessionRoutes.RESIDENT_EDIT),other,Map.of("version",0,"avatarFileId",file.getId())),400,"AVATAR_REJECTED");
        for(String field:List.of("memberId","username","mobile","password","gradeId","role","status"))
            reject(json(put(SessionRoutes.RESIDENT_EDIT),one,Map.of("version",1,"nickname","越权更新",field,"forged")),400,"INVALID_REQUEST");
        assertEquals(1,own(one).get("version").getAsLong()); assertEquals(0,own(other).get("version").getAsLong());
    }

    @Test @Order(6) void API03_avatarHttpValidatesWithoutClaimingStorageSuccess() throws Exception {
        var one=apiFresh("upload");
        var bytes=new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(1,1,BufferedImage.TYPE_INT_RGB),"png",bytes);
        var valid=new MockMultipartFile("file","synthetic.png","image/png",bytes.toByteArray());
        reject(multipart(SessionRoutes.RESIDENT_AVATAR).file(valid).header("accessToken",one.session().accessToken()),503,"STORAGE_PENDING");
        reject(multipart(SessionRoutes.RESIDENT_AVATAR).file(new MockMultipartFile("file","bad.png","image/png",new byte[]{1,2,3})).header("accessToken",one.session().accessToken()),400,"AVATAR_REJECTED");
        reject(multipart(SessionRoutes.RESIDENT_AVATAR).file(new MockMultipartFile("wrong","synthetic.png","image/png",bytes.toByteArray())).header("accessToken",one.session().accessToken()),400,"INVALID_REQUEST");
        reject(multipart(SessionRoutes.RESIDENT_AVATAR).file(valid).param("ownerId","123").header("accessToken",one.session().accessToken()),400,"INVALID_REQUEST");
        verifyNoInteractions(filePlugins);
    }

    @Test @Order(7) void API04_phonePersistenceReplayUpdateAndCancellation() throws Exception {
        var one=apiFresh("phone"); String first=phone(), next=phone(), firstCode=phoneFixture(first), id=request();
        var request=Map.<String,Object>of("version",0,"phoneCode",firstCode,"requestId",id);
        var bound=success(json(post(SessionRoutes.RESIDENT_PHONE),one,request));
        assertTrue(bound.get("phoneBound").getAsBoolean()); assertTrue(first.equals(profileMapper.read(one.memberId()).getMobile()),"first synthetic number persisted");
        assertEquals(1,success(json(post(SessionRoutes.RESIDENT_PHONE),one,request)).get("version").getAsLong());
        reject(json(post(SessionRoutes.RESIDENT_PHONE),one,Map.of("version",0,"phoneCode",phoneFixture(next),"requestId",id)),409,"REQUEST_CONFLICT");
        reject(json(post(SessionRoutes.RESIDENT_PHONE),one,Map.of("version",1,"phoneCode",invalidCode(),"requestId",request())),400,"PHONE_REJECTED");
        reject(json(post(SessionRoutes.RESIDENT_PHONE),one,Map.of("version",1,"phoneCode","","requestId",request())),400,"PHONE_REJECTED");
        reject(json(post(SessionRoutes.RESIDENT_PHONE),one,Map.of("version",1,"phoneCode",phoneFixture(next),"requestId",request(),"mobile",next)),400,"INVALID_REQUEST");
        assertTrue(first.equals(profileMapper.read(one.memberId()).getMobile()),"failed authorizations preserved old phone");
        assertEquals(2,success(json(post(SessionRoutes.RESIDENT_PHONE),one,Map.of("version",1,"phoneCode",phoneFixture(next),"requestId",request()))).get("version").getAsLong());
        assertTrue(next.equals(profileMapper.read(one.memberId()).getMobile()),"replacement synthetic number persisted");
        assertFalse(JSON.toJson(own(one)).contains(next));
    }

    @Test @Order(8) void API04_twoRequestsCannotOverwriteOneProfileVersion() throws Exception {
        var one=apiFresh("phoneRace"); var barrier=new CyclicBarrier(2); String number=phone();
        try(var executor=Executors.newFixedThreadPool(2)) {
            var futures=new ArrayList<Future<Integer>>();
            for(int n=0;n<2;n++) futures.add(executor.submit(() -> {
                var req=json(post(SessionRoutes.RESIDENT_PHONE),one,Map.of("version",0,"phoneCode",phoneFixture(number),"requestId",request()));
                barrier.await(10,TimeUnit.SECONDS); return perform(req).getResponse().getStatus();
            }));
            var statuses=new ArrayList<Integer>(); for(var f:futures) statuses.add(f.get(30,TimeUnit.SECONDS));
            assertEquals(1,Collections.frequency(statuses,200)); assertEquals(1,Collections.frequency(statuses,409));
        }
        assertEquals(1,own(one).get("version").getAsLong());
        assertTrue(number.equals(profileMapper.read(one.memberId()).getMobile()),"one authoritative phone persisted");
    }

    @Test @Order(9) void API04_phoneConflictNeverTakesOverMemberB() throws Exception {
        var a=apiFresh("phoneA"); var b=apiFresh("phoneB"); String number=phone();
        success(json(post(SessionRoutes.RESIDENT_PHONE),b,Map.of("version",0,"phoneCode",phoneFixture(number),"requestId",request())));
        reject(json(post(SessionRoutes.RESIDENT_PHONE),a,Map.of("version",0,"phoneCode",phoneFixture(number),"requestId",request())),409,"LINK_REQUIRED");
        assertNull(profileMapper.read(a.memberId()).getMobile()); assertEquals(0,own(a).get("version").getAsLong());
        assertTrue(number.equals(profileMapper.read(b.memberId()).getMobile()),"B ownership preserved");
        assertEquals(a.memberId(),verified(a).identity().subject()); assertEquals(b.memberId(),verified(b).identity().subject());
    }

    @Test @Order(10) void API05_historyAndMissingProofDenyWithoutChangingEitherIdentity() throws Exception {
        var a=apiFresh("linkA"); var b=apiFresh("linkB"); var identity=verified(a).identity();
        String aBefore=linkSnapshot(a), bBefore=linkSnapshot(b);
        var payload=Map.<String,Object>of("requestId",request(),"proofReference",request(),"consentVersion",MemberLinkService.CONSENT_VERSION,"confirmed",true);
        reject(json(post(SessionRoutes.RESIDENT_LINK),a,payload),503,"HISTORY_PENDING");
        // Test-only negative probe reaches the provider guard without enabling history-ready.
        var gateProbe=new ResidentProfileProperties(){ @Override public void requireHistory(){ requireSchema(); } };
        gateProbe.setSchemaReady(true);
        var probe=new MemberLinkService(links,mappings,identities,profileMapper,gateProbe,residentTransactions,proofProvider,sessions);
        var controller=context.getBean(ResidentProfileController.class);
        ReflectionTestUtils.setField(controller,"links",probe);
        try { reject(json(post(SessionRoutes.RESIDENT_LINK),a,payload),503,"PROOF_SOURCE_PENDING"); }
        finally { ReflectionTestUtils.setField(controller,"links",linkService); }
        assertThrows(DataAccessException.class,() -> links.history(a.memberId()),"actual missing relation tables never mean no history");
        assertEquals(0,count("SELECT COUNT(*) FROM li_member_identity_link WHERE mapping_id=?",identity.bindingId()));
        assertEquals(a.memberId(),mappings.selectById(identity.bindingId()).getMemberId());
        assertEquals(a.memberId(),own(a).get("memberId").getAsString()); assertEquals(b.memberId(),own(b).get("memberId").getAsString());
        assertTrue(aBefore.equals(linkSnapshot(a)),"A profile, mapping and Session unchanged after denied linking");
        assertTrue(bBefore.equals(linkSnapshot(b)),"B profile, mapping and Session unchanged after denied linking");
    }

    @Test @Order(11) void API06_refreshLogoutAndOtherSidRemainIndependent() throws Exception {
        String open="sessions_"+RUN; var one=apiLogin(open); var two=apiLogin(open);
        var refreshed=JSON.fromJson(success(post(SessionRoutes.refresh(SessionDomain.RESIDENT)).contentType("application/json")
                .content(JSON.toJson(Map.of("refreshToken",one.session().refreshToken())))),SessionTokens.class);
        credentials(refreshed);
        status(get("/buyer/passport/member").header("accessToken",one.session().accessToken()),403);
        status(post(SessionRoutes.refresh(SessionDomain.RESIDENT)).contentType("application/json").content(JSON.toJson(Map.of("refreshToken",one.session().refreshToken()))),403);
        status(get("/buyer/passport/member").header("accessToken",refreshed.refreshToken()),403);
        status(post(SessionRoutes.refresh(SessionDomain.RESIDENT)).contentType("application/json").content(JSON.toJson(Map.of("refreshToken",refreshed.accessToken()))),403);
        status(get("/buyer/passport/member").header("accessToken",refreshed.accessToken()),200);
        status(post(SessionRoutes.logout(SessionDomain.RESIDENT)).header("accessToken",refreshed.accessToken()),200);
        status(get("/buyer/passport/member").header("accessToken",refreshed.accessToken()),403);
        status(post(SessionRoutes.refresh(SessionDomain.RESIDENT)).contentType("application/json").content(JSON.toJson(Map.of("refreshToken",refreshed.refreshToken()))),403);
        assertEquals(two.memberId(),own(two).get("memberId").getAsString());
        credentials(JSON.fromJson(success(post(SessionRoutes.refresh(SessionDomain.RESIDENT)).contentType("application/json")
                .content(JSON.toJson(Map.of("refreshToken",two.session().refreshToken())))),SessionTokens.class));
    }

    @Test @Order(12) void API06_crossDomainsAnonymousAndLegacyWritesAreDeniedBeforeHandlers() throws Exception {
        var one=apiFresh("domain");
        for(String route:List.of("/store/member/user","/manager/passport/user/info","/store/passport/applicant/identity")) {
            status(get(route).header("accessToken",one.session().accessToken()),403); status(get(route),403);
        }
        assertEquals(0,FOREIGN_HANDLERS.get());
        for(String path:List.of(SessionRoutes.RESIDENT_PHONE,SessionRoutes.RESIDENT_LINK,SessionRoutes.RESIDENT_AVATAR)) status(post(path),403);
        status(put(SessionRoutes.RESIDENT_EDIT),403);
        String legacy=legacy(one.memberId());
        reject(put(SessionRoutes.RESIDENT_EDIT).header("accessToken",legacy).contentType("application/json")
                .content(JSON.toJson(Map.of("version",0,"nickname","legacy denied"))),403,"SESSION_REJECTED");
        assertEquals(0,own(one).get("version").getAsLong());
        var disabled=members.selectById(one.memberId()); disabled.setDisabled(false); members.updateById(disabled);
        status(get("/buyer/passport/member").header("accessToken",one.session().accessToken()),403);
    }

    /** TC-S0-S02-002-01: one uninterrupted HTTP/DB sequence, not a composition of separate tests. */
    @Test @Order(13) void API01_profileAndPhoneChangesThenSameWechatLoginKeepOriginalMapping() throws Exception {
        String open="continuity_"+RUN;
        var first=apiLogin(open); var original=verified(first).identity();
        var before=mappings.selectById(original.bindingId());
        long mappingVersion=before.getBindingVersion();
        int memberCount=count("SELECT COUNT(*) FROM li_member"), mappingCount=count("SELECT COUNT(*) FROM li_wechat_identity");
        assertFalse(own(first).get("phoneBound").getAsBoolean());
        assertEquals(1,success(json(put(SessionRoutes.RESIDENT_EDIT),first,
                Map.of("version",0,"nickname","稳定映射居民"))).get("version").getAsLong());
        String firstPhone=phone(), replacement=phone();
        assertEquals(2,success(json(post(SessionRoutes.RESIDENT_PHONE),first,
                Map.of("version",1,"phoneCode",phoneFixture(firstPhone),"requestId",request()))).get("version").getAsLong());
        assertEquals(3,success(json(post(SessionRoutes.RESIDENT_PHONE),first,
                Map.of("version",2,"phoneCode",phoneFixture(replacement),"requestId",request()))).get("version").getAsLong());

        var again=apiLogin(open); var current=verified(again).identity();
        assertEquals(first.memberId(),again.memberId()); assertEquals(original,current);
        assertNotEquals(first.session().sid(),again.session().sid());
        var after=mappings.selectById(current.bindingId());
        assertEquals(before.getId(),after.getId()); assertEquals(mappingVersion,after.getBindingVersion());
        assertEquals("ACTIVE",after.getBindingState());
        assertEquals(memberCount,count("SELECT COUNT(*) FROM li_member"));
        assertEquals(mappingCount,count("SELECT COUNT(*) FROM li_wechat_identity"));
        assertEquals(1,count("SELECT COUNT(*) FROM li_wechat_identity WHERE app_id=? AND open_id=?",APP,open));
        var latest=own(again);
        assertEquals("稳定映射居民",latest.get("nickname").getAsString());
        assertEquals(3,latest.get("version").getAsLong()); assertTrue(latest.get("phoneBound").getAsBoolean());
        assertTrue(replacement.equals(profileMapper.read(first.memberId()).getMobile()),"latest synthetic phone survives relogin");
        assertEquals(first.memberId(),own(first).get("memberId").getAsString());
        assertEquals(0,count("SELECT COUNT(*) FROM li_member_identity_link WHERE mapping_id=?",current.bindingId()));
    }

    /** R01-004-01: controlled gateway results, real Filter/Controller/Service, no official outbound. */
    @ParameterizedTest @ValueSource(strings={"expired","consumed","wrong-app","unavailable"}) @Order(14)
    void API01_gatewayFailureVariantsNeverWriteOrIssueSession(String fault) throws Exception {
        int memberCount=count("SELECT COUNT(*) FROM li_member"), mappingCount=count("SELECT COUNT(*) FROM li_wechat_identity"), keys=KEYS.size();
        String code=invalidCode();
        if(fault.equals("wrong-app")) CODE_FIXTURES.put(code,new WechatCodeGateway.Identity(apps.getMerchantApplication(),"wrong_app_"+RUN,null));
        else CODE_FAILURE_FIXTURES.put(code,fault.equals("unavailable")?ResidentLoginException.Code.WECHAT_UNAVAILABLE:ResidentLoginException.Code.WECHAT_REJECTED);
        reject(post(SessionRoutes.RESIDENT_LOGIN).contentType("application/json").content(JSON.toJson(Map.of("loginCode",code))),
                fault.equals("unavailable")?503:403,fault.equals("unavailable")?"WECHAT_UNAVAILABLE":"WECHAT_REJECTED");
        assertEquals(memberCount,count("SELECT COUNT(*) FROM li_member"));
        assertEquals(mappingCount,count("SELECT COUNT(*) FROM li_wechat_identity")); assertEquals(keys,KEYS.size());
    }

    /** R01-004-02/S02-004-01: mutate only registered synthetic rows; never interpret missing Member as empty history. */
    @ParameterizedTest @ValueSource(strings={"disabled","deleted","missing-member"}) @Order(15)
    void API01_inactiveOrOrphanMappingCannotReloginReadOrRefresh(String fault) throws Exception {
        String open="revoked_"+fault.replace('-','_')+"_"+RUN; var one=apiLogin(open); var identity=verified(one).identity();
        if(fault.equals("missing-member")) {
            String missing=SnowFlake.getIdStr(); register("li_member",missing);
            assertEquals(0,existing("li_member",Set.of(missing)));
            var mapping=mappings.selectById(identity.bindingId()); mapping.setMemberId(missing);
            assertEquals(1,mappings.updateById(mapping));
        } else {
            var member=members.selectById(one.memberId());
            if(fault.equals("disabled")) member.setDisabled(false); else member.setDeleteFlag(true);
            assertEquals(1,members.updateById(member));
        }
        int memberCount=count("SELECT COUNT(*) FROM li_member"), mappingCount=count("SELECT COUNT(*) FROM li_wechat_identity"), keys=KEYS.size();
        reject(post(SessionRoutes.RESIDENT_LOGIN).contentType("application/json").content(JSON.toJson(Map.of("loginCode",loginFixture(open)))),403,"IDENTITY_CONFLICT");
        status(get("/buyer/passport/member").header("accessToken",one.session().accessToken()),403);
        status(post(SessionRoutes.refresh(SessionDomain.RESIDENT)).contentType("application/json")
                .content(JSON.toJson(Map.of("refreshToken",one.session().refreshToken()))),403);
        assertEquals(memberCount,count("SELECT COUNT(*) FROM li_member"));
        assertEquals(mappingCount,count("SELECT COUNT(*) FROM li_wechat_identity")); assertEquals(keys,KEYS.size());
    }

    /** R02-001-01: use the actual second registered Member ID, rather than a selector placeholder. */
    @Test @Order(16) void API02_actualOtherMemberSelectorsCannotChooseProfile() throws Exception {
        var a=apiFresh("selectorA"); var b=apiFresh("selectorB");
        var beforeA=linkSnapshot(a); var beforeB=linkSnapshot(b);
        reject(get("/buyer/passport/member").header("accessToken",a.session().accessToken()).queryParam("memberId",b.memberId()),403,"SESSION_REJECTED");
        reject(get("/buyer/passport/member").header("accessToken",a.session().accessToken()).header("memberId",b.memberId()),400,"INVALID_REQUEST");
        assertEquals(a.memberId(),own(a).get("memberId").getAsString());
        assertEquals(b.memberId(),own(b).get("memberId").getAsString());
        assertEquals(beforeA,linkSnapshot(a)); assertEquals(beforeB,linkSnapshot(b));
    }

    @AfterEach void noSensitiveLogOutput() {
        if(logCapture==null) return;
        var secrets=new ArrayList<>(CREDENTIALS); secrets.addAll(CODE_FIXTURES.keySet()); secrets.addAll(PHONE_FIXTURES.keySet()); secrets.addAll(PHONE_FIXTURES.values());
        for(var event:new ArrayList<>(logCapture.list)) {
            String text=event.getFormattedMessage()+ (event.getThrowableProxy()==null?"":ThrowableProxyUtil.asString(event.getThrowableProxy()));
            assertTrue(secrets.stream().noneMatch(value -> value!=null && !value.isEmpty() && text.contains(value)),"sensitive fixture material must never enter logs");
        }
    }
    private static MvcResult perform(RequestBuilder request) throws Exception {
        var result=mvc.perform(request).andReturn();
        var row=new LinkedHashMap<String,Object>(); row.put("method",result.getRequest().getMethod()); row.put("uri",result.getRequest().getRequestURI()); row.put("status",result.getResponse().getStatus());
        if(result.getHandler() instanceof HandlerMethod handler) row.put("handler",handler.getBeanType().getSimpleName()+"."+handler.getMethod().getName());
        synchronized(ROWS) { HTTP_RESULTS.add(row); journal(); } return result;
    }
    private static MvcResult status(RequestBuilder request,int expected) throws Exception {
        var result=perform(request); assertEquals(expected,result.getResponse().getStatus(),"HTTP status; bodies intentionally excluded from failure output"); return result;
    }
    private static JsonObject success(RequestBuilder request) throws Exception {
        var result=status(request,200); assertEquals("no-store",result.getResponse().getHeader("Cache-Control"));
        var body=JsonParser.parseString(result.getResponse().getContentAsString()).getAsJsonObject(); assertTrue(body.get("success").getAsBoolean());
        return body.getAsJsonObject("result");
    }
    private static void reject(RequestBuilder request,int expected,String message) throws Exception {
        var result=status(request,expected); var body=JsonParser.parseString(result.getResponse().getContentAsString()).getAsJsonObject();
        assertFalse(body.get("success").getAsBoolean()); assertEquals(message,body.get("message").getAsString());
    }
    private static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request,ResidentWechatLoginService.LoginResult owner,Object body) {
        return request.header("accessToken",owner.session().accessToken()).contentType("application/json").content(JSON.toJson(body));
    }
    private static String loginFixture(String open) {
        String code="code_"+UUID.randomUUID().toString().replace("-",""); CODE_FIXTURES.put(code,new WechatCodeGateway.Identity(APP,open,null)); journal(); return code;
    }
    private static String invalidCode() { String code="rejected_"+UUID.randomUUID().toString().replace("-",""); CREDENTIALS.add(code); return code; }
    private static ResidentWechatLoginService.LoginResult apiLogin(String open) throws Exception {
        var result=JSON.fromJson(success(post(SessionRoutes.RESIDENT_LOGIN).contentType("application/json").content(JSON.toJson(Map.of("loginCode",loginFixture(open))))),ResidentWechatLoginService.LoginResult.class);
        assertNotNull(result.session()); credentials(result.session()); return result;
    }
    private static ResidentWechatLoginService.LoginResult apiFresh(String label) throws Exception { return apiLogin(label+"_"+UUID.randomUUID().toString().replace("-","")); }
    private static void credentials(SessionTokens tokens) { CREDENTIALS.add(tokens.accessToken()); CREDENTIALS.add(tokens.refreshToken()); }
    private static JsonObject own(ResidentWechatLoginService.LoginResult user) throws Exception { return success(get("/buyer/passport/member").header("accessToken",user.session().accessToken())); }
    private static String linkSnapshot(ResidentWechatLoginService.LoginResult user) {
        var identity=verified(user).identity();
        // Digest only: no profile values or serialized SessionRecord leave this process.
        return ResidentProfileService.hash(JSON.toJson(List.of(profileMapper.read(user.memberId()),
                mappings.selectById(identity.bindingId()), actualStore.load(SessionDomain.RESIDENT,APP,user.session().sid()))));
    }
    private static File metadata(String owner) {
        File file=new File(); file.setId(SnowFlake.getIdStr()); file.setOwnerId(owner); file.setUserEnums("MEMBER"); file.setFileCategory("IMAGE");
        file.setFileType("image/png"); file.setFileSize(128L); file.setDeleteFlag(false); file.setName("synthetic-avatar.png");
        file.setFileKey("MEMBER/"+owner+"/stage0-avatar/"+RUN+".png"); file.setUrl("https://p03-api.invalid/"+RUN+".png");
        assertEquals(1,files.insert(file)); return file;
    }
    private static String legacy(String id) {
        var user=AuthUser.builder().id(id).username("synthetic-legacy").role(UserEnums.MEMBER).build();
        String value=Jwts.builder().claim("userContext",JSON.toJson(user)).subject(user.getUsername()).expiration(new Date(System.currentTimeMillis()+60000))
                .signWith(signing.generalKey(),Jwts.SIG.HS256).compact();
        CREDENTIALS.add(value); LEGACY_KEYS.add(CachePrefix.ACCESS_TOKEN.getPrefix(UserEnums.MEMBER,id)+value); return value;
    }

    private static <T> T guarded(Class<T> type, T actual) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            String name = method.getName(); Object row = args == null || args.length == 0 ? null : args[0];
            if ("insert".equals(name)) {
                if (row instanceof Member member) register("li_member", member.getId());
                else if (row instanceof WechatIdentity mapping) {
                    require(REGISTERED_APPS.contains(mapping.getAppId()) && registered("li_member").contains(mapping.getMemberId()), "FOREIGN_MAPPING");
                    register("li_wechat_identity", mapping.getId());
                    if (INVALID_MAPPING.get()) mapping.setBindingState("BROKEN"); // DB07 actual CHECK failure, test only.
                } else if (row instanceof File file) {
                    require(registered("li_member").contains(file.getOwnerId()), "FOREIGN_FILE_OWNER"); register("li_file", file.getId());
                } else if (row instanceof MemberIdentityLink link) register("li_member_identity_link", link.getId());
                else throw new IllegalStateException("UNREGISTERED_INSERT_TYPE");
            }
            if ("updateById".equals(name)) {
                String table, id;
                if (row instanceof Member m) { table = "li_member"; id = m.getId(); }
                else if (row instanceof WechatIdentity m) { table = "li_wechat_identity"; id = m.getId(); }
                else throw new IllegalStateException("UNAPPROVED_UPDATE");
                require(registered(table).contains(id), "UNREGISTERED_UPDATE");
            }
            if (type == ResidentProfileMapper.class && Set.of("read", "lock", "edit", "phone").contains(name)) {
                require(row instanceof String && registered("li_member").contains(row), "FOREIGN_MEMBER_QUERY");
            }
            if (name.startsWith("delete")) throw new IllegalStateException("TEST_MAPPER_DELETE_NOT_ALLOWED");
            try {
                Object result = method.invoke(actual, args);
                if ("insert".equals(name) && Integer.valueOf(1).equals(result)) {
                    INSERT_SUCCEEDED.incrementAndGet(); confirmInsert(row);
                }
                return result;
            } catch (InvocationTargetException e) {
                for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {
                    if (cause instanceof java.sql.SQLException sql) {
                        var detail = new LinkedHashMap<String, Object>();
                        detail.put("mapper", type.getSimpleName()); detail.put("method", name);
                        detail.put("class", sql.getClass().getSimpleName()); detail.put("sqlState", sql.getSQLState());
                        detail.put("vendorCode", sql.getErrorCode());
                        // Never persist SQL parameters, passwords, numbers, gateway codes or tokens.
                        detail.put("message", Objects.toString(sql.getMessage(), "").replaceAll("'[^']*'", "'[VALUE]'")
                                .replaceAll("[A-Za-z0-9_+/=-]{40,}", "[REDACTED]"));
                        synchronized (ROWS) { SQL_ERRORS.add(detail); journal(); }
                        break;
                    }
                }
                throw e.getCause();
            }
        });
    }

    private static SessionService.Store guardedStore() {
        return new SessionService.Store() {
            @Override public boolean create(SessionRecord record) {
                require(registered("li_member").contains(record.identity().subject()) && REGISTERED_APPS.contains(record.identity().application()), "FOREIGN_SESSION");
                String key = RedisSessionStore.key(record.identity().domain(), record.identity().application(), record.sid());
                synchronized (ROWS) { require(!KEYS.containsKey(key), "SESSION_KEY_COLLISION"); KEYS.put(key, false); journal(); }
                boolean created = actualStore.create(record);
                synchronized (ROWS) { KEYS.put(key, created); journal(); } return created;
            }
            @Override public SessionRecord load(SessionDomain domain, String app, String sid) { keyGuard(domain, app, sid); return actualStore.load(domain, app, sid); }
            @Override public boolean rotate(SessionRecord before, SessionRecord after) {
                keyGuard(before.identity().domain(), before.identity().application(), before.sid()); return actualStore.rotate(before, after);
            }
            @Override public boolean revoke(SessionRecord record) {
                keyGuard(record.identity().domain(), record.identity().application(), record.sid()); return actualStore.revoke(record);
            }
        };
    }
    private static void keyGuard(SessionDomain domain, String app, String sid) {
        synchronized (ROWS) { require(KEYS.containsKey(RedisSessionStore.key(domain, app, sid)), "UNREGISTERED_REDIS_KEY"); }
    }
    private static void register(String table, String id) {
        synchronized (ROWS) {
            require(id != null && id.matches("[0-9]{1,19}") && !BASELINE_MEMBERS.contains(id), "PROTECTED_OR_INVALID_ID");
            require(ROWS.containsKey(table), "UNAPPROVED_INSERT_TABLE");
            if (!ROWS.get(table).contains(id)) {
                // Never claim ownership of a pre-existing row, including an existing li_file id.
                // A rejected collision is deliberately NOT added to the cleanup registry.
                require(count("SELECT COUNT(*) FROM " + table + " WHERE id=?", id) == 0, "PREEXISTING_INSERT_ID");
                ROWS.get(table).add(id); journal();
            }
        }
    }
    private static Set<String> registered(String table) { synchronized (ROWS) { return new LinkedHashSet<>(ROWS.get(table)); } }
    private static void confirmInsert(Object row) {
        String table, id;
        if (row instanceof Member m) { table = "li_member"; id = m.getId(); }
        else if (row instanceof WechatIdentity m) { table = "li_wechat_identity"; id = m.getId(); }
        else if (row instanceof MemberIdentityLink m) { table = "li_member_identity_link"; id = m.getId(); }
        else if (row instanceof File m) { table = "li_file"; id = m.getId(); }
        else throw new IllegalStateException("UNRECOGNIZED_CONFIRMED_INSERT");
        synchronized (ROWS) {
            require(ROWS.get(table).contains(id), "UNREGISTERED_CONFIRMED_INSERT");
            CONFIRMED_INSERTS.get(table).add(id); journal();
        }
    }
    private static SessionService.VerifiedSession verified(ResidentWechatLoginService.LoginResult login) { return sessions.verifyAccess(login.session().accessToken(), SessionDomain.RESIDENT, APP); }
    private static String phone() { return "+999" + String.format(Locale.ROOT, "%09d", new SecureRandom().nextInt(1_000_000_000)); }
    private static String phoneFixture(String number) {
        String code = "phone_" + UUID.randomUUID().toString().replace("-", "");
        PHONE_FIXTURES.put(code, number); synchronized (ROWS) { journal(); } return code;
    }
    private static String request() {
        String id = "req_" + UUID.randomUUID().toString().replace("-", "");
        REQUESTS.add(id); synchronized (ROWS) { journal(); } return id;
    }
    private static int count(String sql, Object... arguments) { return Objects.requireNonNull(jdbc.queryForObject(sql, Integer.class, arguments)); }
    private static int existing(String table, Set<String> ids) { int count = 0; for (String id : ids) count += count("SELECT COUNT(*) FROM " + table + " WHERE id=?", id); return count; }
    private static int number(Map<?, ?> value, String key) { return ((Number) value.get(key)).intValue(); }
    private static void require(boolean valid, String message) { if (!valid) throw new IllegalStateException(message); }
    private static Properties controlledMysqlCredentials() throws Exception {
        var result = new Properties();
        for (String line : Files.readAllLines(Path.of("D:/git/daguanglu-p0-build/F01-03C2D/private/mysql-app.cnf"), StandardCharsets.UTF_8)) {
            int separator = line.indexOf('='); if (separator <= 0) continue;
            String key = line.substring(0, separator).trim(), value = line.substring(separator + 1).trim();
            if ((value.startsWith("\"") && value.endsWith("\"")) || (value.startsWith("'") && value.endsWith("'"))) value = value.substring(1, value.length() - 1);
            if (key.equals("user") || key.equals("password")) result.setProperty(key, value);
        }
        require("daguanglu_app".equals(result.getProperty("user")) && result.getProperty("password") != null, "CONTROLLED_CREDENTIAL_REQUIRED");
        return result;
    }

    private static void journal() {
        if (evidence == null) return;
        synchronized (ROWS) {
            var state = new LinkedHashMap<String, Object>(); state.put("run", RUN); state.put("registeredApplications", REGISTERED_APPS);
            state.put("rows", ROWS); state.put("redisKeys", KEYS); state.put("results", RESULTS);
            state.put("confirmedInsertRows", CONFIRMED_INSERTS);
            state.put("registeredRequestIds", REQUESTS);
            state.put("sqlDiagnostics", SQL_ERRORS); state.put("httpRequests", HTTP_RESULTS);
            state.put("successfulInsertStatements", INSERT_SUCCEEDED.get()); state.put("deletedRows", deletedRows); state.put("deletedRedisKeys", deletedKeys);
            state.put("loginFixtures", CODE_FIXTURES.values().stream().map(i -> Map.of("app", i.appId(), "open", i.openId())).toList());
            // Phone values/codes and Session credentials never enter artifacts; only fixture digests.
            state.put("phoneFixtureDigests", PHONE_FIXTURES.values().stream().map(ResidentProfileService::hash).toList());
            try {
                Path temp = evidence.resolve("p03-api-data-journal.tmp"), target = evidence.resolve("p03-api-data-journal.json");
                Files.writeString(temp, JSON.toJson(state), StandardCharsets.UTF_8);
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception e) { throw new IllegalStateException("JOURNAL_WRITE_FAILED"); }
        }
    }

    @AfterAll static void cleanOnlyRegisteredObjects() throws Exception {
        List<String> cleanupErrors = new ArrayList<>();
        try {
            if (jdbc != null && !ROWS.isEmpty()) {
                boolean unconfirmedPresent = false;
                var uncertain = new ArrayList<Map<String, String>>();
                for (String table : ROWS.keySet()) {
                    int present = 0;
                    for (String id : registered(table)) {
                        int found = count("SELECT COUNT(*) FROM " + table + " WHERE id=?", id);
                        present += found;
                        if (found != 0 && !CONFIRMED_INSERTS.get(table).contains(id)) {
                            uncertain.add(Map.of("table", table, "id", id)); unconfirmedPresent = true;
                        }
                    }
                    PRESENT_BEFORE_CLEANUP.put(table, present);
                }
                if (unconfirmedPresent) {
                    Files.writeString(evidence.resolve("p03-api-unconfirmed-rows.json"), JSON.toJson(uncertain), StandardCharsets.UTF_8);
                    throw new IllegalStateException("UNCONFIRMED_DATABASE_INSERT_REQUIRES_REVIEW");
                }
                // Check actual referencing columns, without reading unrelated business records.
                var references = jdbc.queryForList("SELECT table_name,column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND column_name IN ('member_id','owner_id','source_member_id','target_member_id')");
                for (var ref : references) {
                    String table = Objects.toString(ref.get("TABLE_NAME"), Objects.toString(ref.get("table_name"), ""));
                    String column = Objects.toString(ref.get("COLUMN_NAME"), Objects.toString(ref.get("column_name"), ""));
                    require(table.matches("[a-zA-Z0-9_]+") && column.matches("[a-zA-Z0-9_]+"), "UNSAFE_METADATA_NAME");
                    for (String member : registered("li_member")) {
                        int all = count("SELECT COUNT(*) FROM `" + table + "` WHERE `" + column + "`=?", member);
                        int ours = 0;
                        if (ROWS.containsKey(table)) for (String id : registered(table)) ours += count("SELECT COUNT(*) FROM `" + table + "` WHERE id=? AND `" + column + "`=?", id, member);
                        require(all == ours, "UNKNOWN_TEST_REFERENCE_" + table);
                    }
                }
                for (String table : List.of("li_member_identity_link", "li_wechat_identity", "li_file", "li_member")) {
                    for (String id : registered(table)) {
                        require(!BASELINE_MEMBERS.contains(id), "PROTECTED_SEED");
                        // A registered attempt alone never confers cleanup ownership. A confirmed
                        // INSERT may already have rolled back; its exact DELETE then correctly returns 0.
                        if (!CONFIRMED_INSERTS.get(table).contains(id)) continue;
                        int deleted = jdbc.update("DELETE FROM " + table + " WHERE id=?", id);
                        deletedRows += deleted; DELETED_BY_TABLE.merge(table, deleted, Integer::sum); journal();
                    }
                    assertEquals(0, existing(table, registered(table)), "registered table rows cleaned");
                }
                assertEquals(BASELINE_MEMBERS, new HashSet<>(jdbc.queryForList("SELECT CAST(id AS CHAR) FROM li_member", String.class)), "protected baseline retained");
            }
        } catch (Throwable failure) {
            String message = failure.getMessage();
            cleanupErrors.add(failure.getClass().getSimpleName() + ":" + (message != null
                    && (message.startsWith("UNKNOWN_TEST_REFERENCE_") || message.equals("UNCONFIRMED_DATABASE_INSERT_REQUIRES_REVIEW"))
                    ? message : "DATABASE_CLEANUP_REQUIRES_REVIEW"));
        }
        try {
            if (redis != null) for (String key : new ArrayList<>(KEYS.keySet())) {
                // Registration is not ownership. NX=false or an uncertain CREATE response must
                // never authorize deletion of an existing/unknown Session key.
                if (!Boolean.TRUE.equals(KEYS.get(key))) {
                    cleanupErrors.add("REDIS_CREATE_NOT_CONFIRMED_REQUIRES_REVIEW");
                    continue;
                }
                if (Boolean.TRUE.equals(redis.delete(key))) deletedKeys++; journal();
                require(!Boolean.TRUE.equals(redis.hasKey(key)), "REGISTERED_SESSION_REMAINS");
            }
        } catch (Throwable failure) { cleanupErrors.add("REDIS_CLEANUP_REQUIRES_REVIEW"); }
        try {
            var summary = new LinkedHashMap<String, Object>(); summary.put("setupComplete", setupComplete); summary.put("run", RUN);
            summary.put("registeredRows", ROWS); summary.put("deletedRows", deletedRows); summary.put("registeredRedisKeys", KEYS.size());
            summary.put("registeredRowAttempts", rowCounts(ROWS)); summary.put("confirmedInsertCounts", rowCounts(CONFIRMED_INSERTS));
            summary.put("confirmedInsertRows", CONFIRMED_INSERTS); summary.put("committedPresentBeforeCleanup", PRESENT_BEFORE_CLEANUP);
            summary.put("deletedByTable", DELETED_BY_TABLE);
            summary.put("deletedRedisKeys", deletedKeys); summary.put("cleanupErrors", cleanupErrors);
            summary.put("remainingRegisteredRows", jdbc == null ? null : remainingRows());
            summary.put("remainingConfirmedCreatedKeys", redis == null ? null : KEYS.entrySet().stream()
                    .filter(e -> Boolean.TRUE.equals(e.getValue())).filter(e -> Boolean.TRUE.equals(redis.hasKey(e.getKey()))).count());
            summary.put("unconfirmedCreateKeysRequiringReview", KEYS.values().stream().filter(v -> !Boolean.TRUE.equals(v)).count());
            Files.writeString(evidence.resolve("p03-api-cleanup.json"), JSON.toJson(summary), StandardCharsets.UTF_8);
        } finally {
            if (context != null) context.close(); SecurityContextHolder.clearContext(); if (logCapture != null) ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logCapture); if (signing != null) signing.destroy(); if (redisFactory != null) redisFactory.destroy();
            if (setupComplete || previousSnowflake != null) ReflectionTestUtils.setField(SnowFlake.class, "snowflake", previousSnowflake);
        }
        require(cleanupErrors.isEmpty(), "EXACT_CLEANUP_INCOMPLETE_SEE_PRIVATE_EVIDENCE");
    }
    private static Map<String, Integer> remainingRows() { var result = new LinkedHashMap<String, Integer>(); for (String table : ROWS.keySet()) result.put(table, existing(table, registered(table))); return result; }
    private static Map<String, Integer> rowCounts(Map<String, Set<String>> source) {
        var result = new LinkedHashMap<String, Integer>(); source.forEach((table, ids) -> result.put(table, ids.size())); return result;
    }
}
