package cn.lili.common.security.session;

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
 * Explicitly authorized B04 integration only. No application context, migration, official gateway,
 * file storage, native registration/bootstrap, or business service is started here. The real
 * MyBatis mappers, transaction templates, P03 services and RedisSessionStore are exercised.
 * Every generated row id and Redis key is durably registered BEFORE the corresponding write.
 * Credentials are read only into this process from existing PREP protected files, never argv/log.
 */
@EnabledIfSystemProperty(named = "b04.integration", matches = "approved-prep")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@ExtendWith(B04SchemaIntegrationTest.FailClosed.class)
@SuppressWarnings("unchecked")
class B04SchemaIntegrationTest {
    private static final Gson JSON = new Gson();
    private static final String RUN = UUID.randomUUID().toString().replace("-", "");
    private static final String APP = "b04_" + RUN;
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
    private static final Set<String> REGISTERED_APPS = ConcurrentHashMap.newKeySet();
    private static final Set<String> REQUESTS = ConcurrentHashMap.newKeySet();
    private static final AtomicBoolean FAILED = new AtomicBoolean();
    private static final AtomicInteger INSERT_SUCCEEDED = new AtomicInteger();
    private static final ThreadLocal<Boolean> INVALID_MAPPING = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> FIRST_PARALLEL_READ = ThreadLocal.withInitial(() -> false);
    private static volatile CyclicBarrier firstLoginBarrier;
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

    static final class FailClosed implements TestWatcher {
        @Override public void testSuccessful(ExtensionContext context) { result(context, "PASS", null); }
        @Override public void testFailed(ExtensionContext context, Throwable cause) {
            FAILED.set(true); result(context, "FAIL", cause.getClass().getSimpleName());
        }
        @Override public void testAborted(ExtensionContext context, Throwable cause) { result(context, "NOT_RUN", null); }
        private static void result(ExtensionContext context, String outcome, String error) {
            var entry = new LinkedHashMap<String, Object>();
            entry.put("case", context.getRequiredTestMethod().getName().substring(0, 4));
            entry.put("result", outcome);
            if (error != null) entry.put("failureClass", error);
            synchronized (ROWS) { RESULTS.add(entry); journal(); }
        }
    }

    @BeforeAll static void initializeOnlyAfterVerifiedSchema() throws Exception {
        evidence = Path.of(Objects.requireNonNull(System.getProperty("b04.evidence"))).toAbsolutePath();
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

        apps = new SessionApplications(); apps.setResidentApplication(APP); apps.setMerchantApplication("b04_merchant_" + RUN);
        apps.setManagerApplication("b04_manager_" + RUN);
        var wechatProperties = new ResidentWechatProperties(); wechatProperties.setSchemaReady(true);
        wechatProperties.setEnabled(true); wechatProperties.setAppSecret(UUID.randomUUID().toString().replace("-", ""));
        identities = new WechatIdentityService(mappings, members, wechatProperties, transactions);
        profileProperties = new ResidentProfileProperties(); profileProperties.setSchemaReady(true);
        // No history-ready or upload-ready switch is enabled, even in this isolated test.
        var verifier = new WechatSessionIdentityVerifier(identities, apps, links, profileProperties);
        ObjectProvider<SessionIdentityVerifier> proof = mock(ObjectProvider.class); when(proof.getIfUnique()).thenReturn(verifier);
        MemberService memberService = mock(MemberService.class);
        when(memberService.getById(anyString())).thenAnswer(i -> members.selectById((String) i.getArgument(0)));
        var authorization = new CurrentSessionAuthorization(apps, proof, memberService, mock(ClerkService.class), mock(StoreService.class),
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
            var identity = CODE_FIXTURES.get(code);
            if (identity == null) throw new ResidentLoginException(ResidentLoginException.Code.WECHAT_REJECTED);
            return identity;
        };
        login = new ResidentWechatLoginService(gateway, identities, sessions, apps, wechatProperties);
        FileService fileService = mock(FileService.class);
        when(fileService.getById(anyString())).thenAnswer(i -> files.selectById((String) i.getArgument(0)));
        avatars = new ResidentAvatarService(fileService, mock(FilePluginFactory.class), profileProperties, identities);
        Cache<List<String>> cache = mock(Cache.class); when(cache.get(CachePrefix.SENSITIVE.getPrefix())).thenReturn(List.of("synthetic_forbidden_word"));
        WechatPhoneGateway phoneGateway = code -> {
            String phone = PHONE_FIXTURES.get(code);
            if (phone == null) throw new ResidentProfileException(ResidentProfileException.Code.PHONE_REJECTED);
            return phone;
        };
        profile = new ResidentProfileService(profileMapper, identities, profileProperties, residentTransactions, avatars, phoneGateway, cache);
        setupComplete = true; journal();
    }

    @BeforeEach void stopAfterUnexpectedFailure() { Assumptions.assumeFalse(FAILED.get(), "prior integration failure: dependent tests stopped"); }

    @Test @Order(1) void DB01_nullableMobileUsesRealMemberMapper() {
        var result = fresh("nullable"); Member row = members.selectById(result.memberId());
        assertNull(row.getMobile()); assertNull(row.getGradeId()); assertNotNull(row.getCreateTime());
        assertTrue(row.getDisabled()); assertFalse(row.getDeleteFlag()); assertEquals(0, row.getSex());
        assertEquals(result.memberId(), verified(result).identity().subject());
        assertEquals(0L, profile.read(verified(result).identity()).version());
    }

    @Test @Order(2) void DB02_mappingRoundTripsWithTypesAndChecks() {
        var result = fresh("mapping"); var identity = verified(result).identity();
        var row = mappings.selectById(identity.bindingId());
        assertEquals(APP, row.getAppId()); assertEquals(result.memberId(), row.getMemberId());
        assertEquals(1L, row.getBindingVersion()); assertEquals("ACTIVE", row.getBindingState());
        assertNull(row.getUnionId()); assertNotNull(row.getCreateTime()); assertNotNull(row.getUpdateTime());
        var renewed = sessions.refresh(result.session().refreshToken(), SessionDomain.RESIDENT, APP);
        assertThrows(SessionException.class, () -> sessions.verifyAccess(result.session().accessToken(), SessionDomain.RESIDENT, APP));
        assertEquals(identity, sessions.verifyAccess(renewed.accessToken(), SessionDomain.RESIDENT, APP).identity());
    }

    @Test @Order(3) void DB03_repeatLoginReusesCommittedMemberAndMapping() {
        String open = "repeat_" + RUN; var one = loginOpen(APP, open); var two = loginOpen(APP, open);
        assertEquals(one.memberId(), two.memberId()); assertEquals(verified(one).identity(), verified(two).identity());
        assertNotEquals(one.session().sid(), two.session().sid());
        assertEquals(1, count("SELECT COUNT(*) FROM li_wechat_identity WHERE app_id=? AND open_id=?", APP, open));
    }

    @Test @Order(4) void DB04_concurrentFirstLoginRollsBackLosingMembers() throws Exception {
        Set<String> before = registered("li_member"); firstLoginBarrier = new CyclicBarrier(4);
        try (var executor = Executors.newFixedThreadPool(4)) {
            var work = new ArrayList<Future<ResidentWechatLoginService.LoginResult>>();
            for (int n = 0; n < 4; n++) work.add(executor.submit(() -> {
                FIRST_PARALLEL_READ.set(true);
                try { return loginOpen(APP, "concurrent_" + RUN); }
                finally { FIRST_PARALLEL_READ.remove(); }
            }));
            var winners = new HashSet<String>();
            for (var future : work) winners.add(future.get(30, TimeUnit.SECONDS).memberId());
            assertEquals(1, winners.size());
        } finally { firstLoginBarrier = null; }
        Set<String> attempted = registered("li_member"); attempted.removeAll(before);
        assertEquals(4, attempted.size(), "barrier forces all callers through first insertion");
        assertEquals(1, existing("li_member", attempted), "three losing Member rows must roll back");
        assertEquals(1, count("SELECT COUNT(*) FROM li_wechat_identity WHERE app_id=? AND open_id=?", APP, "concurrent_" + RUN));
    }

    @Test @Order(5) void DB05_applicationAndCaseRemainSeparate() {
        var a = identities.resolve(registerIdentity(APP, "case_" + RUN));
        var b = identities.resolve(registerIdentity(APP, "Case_" + RUN));
        var c = identities.resolve(registerIdentity(APP + "_other", "case_" + RUN));
        assertEquals(3, Set.of(a.subject(), b.subject(), c.subject()).size());
        identities.verify(a); identities.verify(b); identities.verify(c);
    }

    @Test @Order(6) void DB06_revokedMemberAndMappingFenceDenyOldSessions() {
        var disabled = fresh("disabled"); var account = members.selectById(disabled.memberId()); account.setDisabled(false); members.updateById(account);
        assertThrows(SessionException.class, () -> verified(disabled));
        assertThrows(SessionException.class, () -> sessions.refresh(disabled.session().refreshToken(), SessionDomain.RESIDENT, APP));
        var pending = fresh("pending"); var identity = verified(pending).identity(); var mapping = mappings.selectById(identity.bindingId());
        mapping.setBindingState("PENDING"); mappings.updateById(mapping);
        assertThrows(SessionException.class, () -> verified(pending));
        var fenced = fresh("version"); var fence = mappings.selectById(verified(fenced).identity().bindingId());
        fence.setBindingVersion(2L); mappings.updateById(fence);
        assertThrows(SessionException.class, () -> verified(fenced));
    }

    @Test @Order(7) void DB07_actualConstraintFailureRollsBackAndNeverIssuesSession() {
        Set<String> before = registered("li_member"); int beforeKeys = KEYS.size(); INVALID_MAPPING.set(true);
        try { assertThrows(ResidentLoginException.class, () -> fresh("failed_mapping")); }
        finally { INVALID_MAPPING.remove(); }
        Set<String> attempted = registered("li_member"); attempted.removeAll(before);
        assertEquals(1, attempted.size()); assertEquals(0, existing("li_member", attempted));
        assertEquals(beforeKeys, KEYS.size(), "no Session create may occur after failed mapping transaction");
    }

    @Test @Order(8) void DB08_profileCasAndAuthoritativeRead() {
        var identity = verified(fresh("profile")).identity(); var old = profile.read(identity);
        var changed = profile.edit(identity, old.version(), "B04验证用户", null);
        assertEquals(1, changed.version()); assertEquals("B04验证用户", changed.nickname());
        assertNotNull(changed.updatedAt()); assertEquals(changed, profile.read(identity));
        assertFalse(changed.phoneBound()); assertNull(changed.maskedMobile());
    }

    @Test @Order(9) void DB09_staleProfileAndRequestReplayCannotOverwrite() {
        var identity = verified(fresh("stale")).identity(); profile.edit(identity, 0, "Latest", null);
        profileError(ResidentProfileException.Code.PROFILE_CONFLICT, () -> profile.edit(identity, 0, "Late", null));
        assertEquals("Latest", profile.read(identity).nickname());
        String request = request(), code = phoneFixture(phone()); var first = profile.phone(identity, 1, request, code);
        assertEquals(first, profile.phone(identity, 1, request, code));
        String differentCode = phoneFixture(phone());
        profileError(ResidentProfileException.Code.REQUEST_CONFLICT, () -> profile.phone(identity, 1, request, differentCode));
        assertEquals(first, profile.read(identity));
    }

    @Test @Order(10) void DB10_phoneFirstBindAndVerifiedUpdate() {
        var identity = verified(fresh("phone")).identity(); String firstPhone = phone(), secondPhone = phone();
        assertTrue(profile.phone(identity, 0, request(), phoneFixture(firstPhone)).phoneBound());
        assertTrue(firstPhone.equals(profileMapper.read(identity.subject()).getMobile()), "synthetic first number stored");
        var updated = profile.phone(identity, 1, request(), phoneFixture(secondPhone));
        assertEquals(2, updated.version()); assertTrue(secondPhone.equals(profileMapper.read(identity.subject()).getMobile()), "synthetic replacement stored");
        assertFalse(updated.toString().contains(secondPhone)); assertFalse(updated.maskedMobile().equals(secondPhone));
    }

    @Test @Order(11) void DB11_twoMembersCompeteForOneNumber() throws Exception {
        var one = verified(fresh("compete1")).identity(); var two = verified(fresh("compete2")).identity();
        String phone = phone(); var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var jobs = new ArrayList<Future<Boolean>>();
            for (var identity : List.of(one, two)) jobs.add(executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                try { profile.phone(identity, 0, request(), phoneFixture(phone)); return true; }
                catch (ResidentProfileException expectedConflict) {
                    assertTrue(Set.of(ResidentProfileException.Code.LINK_REQUIRED, ResidentProfileException.Code.PROFILE_CONFLICT,
                            ResidentProfileException.Code.OPERATION_UNAVAILABLE).contains(expectedConflict.getCode()));
                    return false;
                }
            }));
            int successes = 0; for (var job : jobs) if (job.get(30, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        }
        assertEquals(1, count("SELECT COUNT(*) FROM li_member WHERE mobile=? AND id IN (?,?)", phone, one.subject(), two.subject()));
        assertEquals(1, count("SELECT COUNT(*) FROM li_member WHERE mobile IS NULL AND id IN (?,?)", one.subject(), two.subject()));
    }

    @Test @Order(12) void DB12_phoneConflictCannotTakeOverAnotherMember() {
        var a = verified(fresh("conflictA")).identity(); var b = verified(fresh("conflictB")).identity();
        String number = phone(); profile.phone(b, 0, request(), phoneFixture(number));
        profileError(ResidentProfileException.Code.LINK_REQUIRED, () -> profile.phone(a, 0, request(), phoneFixture(number)));
        assertNull(profileMapper.read(a.subject()).getMobile()); assertEquals(0, profile.read(a).version());
        assertEquals(a.subject(), mappings.selectById(a.bindingId()).getMemberId());
        assertEquals(b.subject(), mappings.selectById(b.bindingId()).getMemberId());
    }

    @Test @Order(13) void DB13_realFileMetadataOwnerIsCheckedWithoutStorage() {
        var owner = verified(fresh("fileowner")).identity(); var other = verified(fresh("otherowner")).identity();
        File file = new File(); file.setId(SnowFlake.getIdStr()); file.setOwnerId(owner.subject()); file.setUserEnums("MEMBER");
        file.setFileCategory("IMAGE"); file.setFileType("image/png"); file.setFileSize(128L); file.setDeleteFlag(false);
        file.setFileKey("MEMBER/" + owner.subject() + "/stage0-avatar/" + RUN + ".png");
        file.setName("synthetic-avatar.png"); file.setUrl("https://b04.invalid/" + RUN + ".png");
        assertEquals(1, files.insert(file)); assertEquals(file.getUrl(), avatars.ownedUrl(owner.subject(), file.getId()));
        profileError(ResidentProfileException.Code.AVATAR_REJECTED, () -> avatars.ownedUrl(other.subject(), file.getId()));
        var changed = profile.edit(owner, 0, null, file.getId()); assertEquals(file.getUrl(), changed.avatar());
        profileError(ResidentProfileException.Code.AVATAR_REJECTED, () -> profile.edit(other, 0, null, file.getId()));
        assertNull(profile.read(other).avatar());
    }

    @Test @Order(14) void DB14_missingHistoryAndProofKeepLinkFailClosed() {
        var current = verified(fresh("linkreject"));
        ObjectProvider<MemberOwnershipProof> unavailable = mock(ObjectProvider.class);
        var link = new MemberLinkService(links, mappings, identities, profileMapper, profileProperties, residentTransactions, unavailable, sessions);
        profileError(ResidentProfileException.Code.HISTORY_PENDING, () -> link.link(current, request(), request(), MemberLinkService.CONSENT_VERSION, true));
        assertThrows(DataAccessException.class, () -> links.history(current.identity().subject()), "actual missing native history tables must not mean zero history");
        // A test-only gate subclass bypasses ONLY the gate method to reach the missing-provider
        // branch. It never sets historyReady, never supplies proof, and cannot reach a write.
        var gateProbe = new ResidentProfileProperties() { @Override public void requireHistory() { requireSchema(); } };
        gateProbe.setSchemaReady(true);
        var proofProbe = new MemberLinkService(links, mappings, identities, profileMapper, gateProbe, residentTransactions, unavailable, sessions);
        profileError(ResidentProfileException.Code.PROOF_SOURCE_PENDING, () -> proofProbe.link(current, request(), request(), MemberLinkService.CONSENT_VERSION, true));
        assertEquals(0, count("SELECT COUNT(*) FROM li_member_identity_link WHERE mapping_id=?", current.identity().bindingId()));
        identities.verify(current.identity());
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
                if (type == WechatIdentityMapper.class && "selectList".equals(name) && FIRST_PARALLEL_READ.get()
                        && result instanceof List<?> rows && rows.isEmpty()) {
                    FIRST_PARALLEL_READ.set(false); firstLoginBarrier.await(15, TimeUnit.SECONDS);
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
    private static ResidentWechatLoginService.LoginResult fresh(String label) { return loginOpen(APP, label + "_" + UUID.randomUUID().toString().replace("-", "")); }
    private static ResidentWechatLoginService.LoginResult loginOpen(String app, String open) {
        require(REGISTERED_APPS.contains(app), "UNREGISTERED_APP"); String code = "code_" + UUID.randomUUID().toString().replace("-", "");
        CODE_FIXTURES.put(code, new WechatCodeGateway.Identity(app, open, null));
        synchronized (ROWS) { journal(); } // fixture context is registered before production service use.
        return login.login(code);
    }
    private static WechatCodeGateway.Identity registerIdentity(String app, String open) {
        require(REGISTERED_APPS.contains(app), "UNREGISTERED_APP");
        var identity = new WechatCodeGateway.Identity(app, open, null);
        CODE_FIXTURES.put("fixture_" + UUID.randomUUID().toString().replace("-", ""), identity);
        synchronized (ROWS) { journal(); } return identity;
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
    private static void profileError(ResidentProfileException.Code code, org.junit.jupiter.api.function.Executable operation) {
        assertEquals(code, assertThrows(ResidentProfileException.class, operation).getCode());
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
            state.put("sqlDiagnostics", SQL_ERRORS);
            state.put("successfulInsertStatements", INSERT_SUCCEEDED.get()); state.put("deletedRows", deletedRows); state.put("deletedRedisKeys", deletedKeys);
            state.put("loginFixtures", CODE_FIXTURES.values().stream().map(i -> Map.of("app", i.appId(), "open", i.openId())).toList());
            // Phone values/codes and Session credentials never enter artifacts; only fixture digests.
            state.put("phoneFixtureDigests", PHONE_FIXTURES.values().stream().map(ResidentProfileService::hash).toList());
            try {
                Path temp = evidence.resolve("b04-data-journal.tmp"), target = evidence.resolve("b04-data-journal.json");
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
                    Files.writeString(evidence.resolve("b04-unconfirmed-rows.json"), JSON.toJson(uncertain), StandardCharsets.UTF_8);
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
            Files.writeString(evidence.resolve("b04-cleanup.json"), JSON.toJson(summary), StandardCharsets.UTF_8);
        } finally {
            if (signing != null) signing.destroy(); if (redisFactory != null) redisFactory.destroy();
            if (setupComplete || previousSnowflake != null) ReflectionTestUtils.setField(SnowFlake.class, "snowflake", previousSnowflake);
        }
        require(cleanupErrors.isEmpty(), "EXACT_CLEANUP_INCOMPLETE_SEE_PRIVATE_EVIDENCE");
    }
    private static Map<String, Integer> remainingRows() { var result = new LinkedHashMap<String, Integer>(); for (String table : ROWS.keySet()) result.put(table, existing(table, registered(table))); return result; }
    private static Map<String, Integer> rowCounts(Map<String, Set<String>> source) {
        var result = new LinkedHashMap<String, Integer>(); source.forEach((table, ids) -> result.put(table, ids.size())); return result;
    }
}
