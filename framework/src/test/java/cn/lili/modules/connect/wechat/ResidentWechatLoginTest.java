package cn.lili.modules.connect.wechat;

import cn.lili.common.properties.JWTTokenProperties;
import cn.lili.common.security.session.*;
import cn.lili.common.security.session.http.*;
import cn.lili.common.security.token.SecretKeyUtil;
import cn.lili.common.utils.SnowFlake;
import cn.lili.modules.connect.entity.WechatIdentity;
import cn.lili.modules.connect.mapper.WechatIdentityMapper;
import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.member.mapper.MemberMapper;
import cn.lili.modules.member.service.*;
import cn.lili.modules.member.token.StoreTokenGenerate;
import cn.lili.modules.permission.service.*;
import cn.lili.modules.store.service.StoreService;
import cn.lili.modules.system.token.ManagerTokenGenerate;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real login, mapping service, Spring TransactionTemplate, verifier, authorization and Session core.
 * Repository/commit/unique-index/Redis substitutes only: NOT a MySQL/Redis/WeChat integration PASS.
 * REQ-S0-R01-001..007/010; S02-001..004/006..008; existing formal TC IDs are unchanged.
 */
@SuppressWarnings("unchecked")
class ResidentWechatLoginTest {
    static Object previousIdGenerator;
    @BeforeAll static void initializeOfflineIds() {
        previousIdGenerator = org.springframework.test.util.ReflectionTestUtils.getField(SnowFlake.class, "snowflake");
        SnowFlake.initialize(31, 31);
    }
    @AfterAll static void restoreIdGenerator() {
        org.springframework.test.util.ReflectionTestUtils.setField(SnowFlake.class, "snowflake", previousIdGenerator);
    }
    final Model db = new Model();
    final SessionApplications apps = new SessionApplications();
    final ResidentWechatProperties properties = new ResidentWechatProperties();
    final WechatCodeGateway gateway = mock(WechatCodeGateway.class);
    final RedisSessionStore redis = mock(RedisSessionStore.class);
    final Map<String, SessionRecord> sessionsBySid = new ConcurrentHashMap<>();
    final MemberService memberService = mock(MemberService.class);
    final WechatIdentityService identities;
    final WechatSessionIdentityVerifier verifier;
    final SessionService sessions;
    final ResidentWechatLoginService login;
    final SecretKeyUtil keys;

    ResidentWechatLoginTest() {
        apps.setResidentApplication("wx0000000000000001"); apps.setMerchantApplication("wx0000000000000002");
        apps.setManagerApplication("synthetic-manager");
        properties.setSchemaReady(true); properties.setEnabled(true);
        properties.setAppSecret(UUID.randomUUID().toString().replace("-", ""));
        identities = new WechatIdentityService(db.mappings, db.members, properties, db);
        verifier = new WechatSessionIdentityVerifier(identities, apps);
        ObjectProvider<SessionIdentityVerifier> proof = mock(ObjectProvider.class);
        when(proof.getIfUnique()).thenReturn(verifier);
        when(memberService.getById(anyString())).thenAnswer(i -> db.committedMembers.get(i.getArgument(0)));
        var authorization = new CurrentSessionAuthorization(apps, proof, memberService, mock(ClerkService.class),
                mock(StoreService.class), mock(AdminUserService.class), mock(MenuService.class), mock(StoreMenuService.class),
                new ManagerTokenGenerate(), new StoreTokenGenerate());
        ObjectProvider<SessionAuthorization> providers = mock(ObjectProvider.class);
        when(providers.getIfUnique()).thenReturn(authorization);
        byte[] raw = new byte[32]; new SecureRandom().nextBytes(raw);
        var jwt = new JWTTokenProperties(); jwt.setSecretKey(Base64.getEncoder().encodeToString(raw));
        keys = new SecretKeyUtil(jwt);
        when(redis.create(any())).thenAnswer(i -> {
            assertNull(db.pending.get(), "Session must be created AFTER commit");
            SessionRecord record = i.getArgument(0);
            assertTrue(db.committedMembers.containsKey(record.identity().subject()));
            return sessionsBySid.putIfAbsent(record.sid(), record) == null;
        });
        when(redis.load(any(), anyString(), anyString())).thenAnswer(i -> sessionsBySid.get(i.getArgument(2)));
        when(redis.rotate(any(), any())).thenAnswer(i -> {
            SessionRecord old = i.getArgument(0), next = i.getArgument(1);
            return sessionsBySid.replace(old.sid(), old, next);
        });
        sessions = new SessionService(redis, keys, providers);
        login = new ResidentWechatLoginService(gateway, identities, sessions, apps, properties);
        when(gateway.authenticate(anyString())).thenAnswer(i -> new WechatCodeGateway.Identity(apps.getResidentApplication(), "synthetic-open", null));
    }
    @AfterEach void clear() { keys.destroy(); TransactionSynchronizationManager.clear(); }

    @Test void firstAndRepeatLoginReuseOneMemberWithoutMobileOrNativeRegistration() {
        var first = login.login("code-one"); var second = login.login("code-two");
        assertEquals(first.memberId(), second.memberId()); assertEquals("RESIDENT", first.identity());
        assertEquals(1, db.committedMembers.size()); assertEquals(1, db.committedMappings.size());
        assertNotEquals(first.session().sid(), second.session().sid());
        Member member = db.committedMembers.get(first.memberId());
        assertNull(member.getMobile()); assertNull(member.getGradeId()); assertFalse(member.getHaveStore());
        assertTrue(member.getDisabled()); assertFalse(member.getDeleteFlag()); assertEquals(0L, member.getExperience());
        assertTrue(member.getPassword().startsWith("$2"));
        assertFalse(new BCryptPasswordEncoder().matches("123456", member.getPassword()));
        assertFalse(new BCryptPasswordEncoder().matches("e10adc3949ba59abbe56e057f20f883e", member.getPassword()));
        assertEquals(first.memberId(), sessions.verifyAccess(first.session().accessToken(), SessionDomain.RESIDENT, apps.getResidentApplication()).identity().subject());
        sessions.refresh(first.session().refreshToken(), SessionDomain.RESIDENT, apps.getResidentApplication());
        verify(memberService, never()).autoRegister(any()); verify(memberService, never()).findByMobile(anyString());
        assertEquals(1, db.commitsWithWrites.get());
    }
    @Test void concurrentFirstLoginRollsBackEveryLosingMemberThenReadsWinner() throws Exception {
        db.firstReadBarrier = new CyclicBarrier(6);
        try (var pool = Executors.newFixedThreadPool(6)) {
            List<Future<ResidentWechatLoginService.LoginResult>> calls = new ArrayList<>();
            for (int n = 0; n < 6; n++) {
                String code = "parallel-fresh-code-" + n;
                calls.add(pool.submit(() -> login.login(code)));
            }
            Set<String> subjects = new HashSet<>();
            for (var call : calls) subjects.add(call.get(15, TimeUnit.SECONDS).memberId());
            assertEquals(1, subjects.size()); assertEquals(1, db.committedMembers.size());
            assertEquals(1, db.committedMappings.size()); assertEquals(5, db.rollbacks.get());
            assertEquals(6, sessionsBySid.size()); // Independent devices, same stable Member.
        }
    }
    @Test void exactApplicationAndOpenIdAreIndependentAndUnionIdDoesNotMerge() {
        when(gateway.authenticate(anyString())).thenAnswer(i -> new WechatCodeGateway.Identity(apps.getResidentApplication(), i.getArgument(0), "synthetic-shared-union"));
        var one = login.login("same-open");
        var two = login.login("Same-open");
        assertNotEquals(one.memberId(), two.memberId());
        apps.setResidentApplication("wx0000000000000003");
        var three = login.login("same-open");
        assertNotEquals(one.memberId(), three.memberId());
        assertEquals(3, db.committedMembers.size());
        assertThrows(SessionException.class, () -> sessions.verifyAccess(one.session().accessToken(), SessionDomain.RESIDENT, apps.getResidentApplication()));
    }
    @Test void officialWrongAppAndGatewayFailureNeverReachRepositories() {
        when(gateway.authenticate(anyString())).thenReturn(new WechatCodeGateway.Identity(apps.getMerchantApplication(), "synthetic-open", null));
        assertThrows(ResidentLoginException.class, () -> login.login("wrong-app-code"));
        when(gateway.authenticate(anyString())).thenThrow(new IllegalStateException("synthetic-private-code"));
        var failure = assertThrows(ResidentLoginException.class, () -> login.login("synthetic-private-code"));
        assertNull(failure.getCause()); assertFalse(failure.toString().contains("synthetic-private-code"));
        verifyNoInteractions(db.members, db.mappings, redis);
    }
    /** R01-004-01: exercise the real official-response parser AND login service, with no HTTP. */
    @ParameterizedTest @ValueSource(strings = {"expired", "consumed", "wrong-app", "throttled", "malformed", "transport"})
    void officialFailureVariantsNeverCreateIdentityOrSession(String fault) {
        var official = new OfficialWechatCodeGateway(apps, properties, url -> {
            if (fault.equals("transport")) throw new IllegalStateException("synthetic-private-transport");
            if (fault.equals("malformed")) return "{not-json";
            int error = switch (fault) {
                case "expired" -> 40029;
                case "consumed" -> 40163;
                case "wrong-app" -> 40013;
                default -> 45011;
            };
            return "{\"errcode\":" + error + ",\"errmsg\":\"synthetic-private-diagnostic\"}";
        });
        var actualLogin = new ResidentWechatLoginService(official, identities, sessions, apps, properties);
        var rejected = assertThrows(ResidentLoginException.class, () -> actualLogin.login("synthetic-fresh-code"));
        assertEquals(fault.equals("malformed") || fault.equals("transport")
                ? ResidentLoginException.Code.WECHAT_UNAVAILABLE : ResidentLoginException.Code.WECHAT_REJECTED,
                rejected.getCode());
        assertNull(rejected.getCause());
        assertFalse(rejected.toString().contains("synthetic-private"));
        assertTrue(db.committedMembers.isEmpty()); assertTrue(db.committedMappings.isEmpty());
        assertTrue(sessionsBySid.isEmpty());
        verifyNoInteractions(db.members, db.mappings, redis);
    }
    @ParameterizedTest @ValueSource(strings = {"disabled", "deleted", "missing", "pending", "unknown-state", "version", "duplicate", "wrong-case", "wrong-subject"})
    void dirtyOrRevokedMappingCannotLoginAccessOrRefresh(String fault) {
        var pair = login.login("code-one");
        var member = db.committedMembers.get(pair.memberId());
        var mapping = db.committedMappings.values().iterator().next();
        switch (fault) {
            case "disabled" -> member.setDisabled(false);
            case "deleted" -> member.setDeleteFlag(true);
            case "missing" -> db.committedMembers.remove(member.getId());
            case "pending" -> mapping.setBindingState("PENDING");
            case "unknown-state" -> mapping.setBindingState(null);
            case "version" -> mapping.setBindingVersion(2L);
            case "duplicate" -> db.duplicate = true;
            case "wrong-case" -> mapping.setOpenId("Synthetic-open");
            case "wrong-subject" -> mapping.setMemberId("synthetic-foreign");
        }
        assertThrows(SessionException.class, () -> sessions.verifyAccess(pair.session().accessToken(), SessionDomain.RESIDENT, apps.getResidentApplication()));
        assertThrows(SessionException.class, () -> sessions.refresh(pair.session().refreshToken(), SessionDomain.RESIDENT, apps.getResidentApplication()));
        if (!fault.equals("version")) assertThrows(ResidentLoginException.class, () -> login.login("new-code"));
        else assertEquals(pair.memberId(), login.login("new-code").memberId());
        assertEquals(1, db.commitsWithWrites.get());
    }
    @ParameterizedTest @ValueSource(strings = {"member-write", "mapping-write", "commit", "collision-no-winner"})
    void databaseFailuresCannotLeaveMemberOrIssueSession(String fault) {
        db.fault = fault;
        assertThrows(ResidentLoginException.class, () -> login.login("synthetic-code"));
        assertTrue(db.committedMembers.isEmpty()); assertTrue(db.committedMappings.isEmpty());
        assertTrue(sessionsBySid.isEmpty()); verifyNoInteractions(redis);
    }
    @Test void redisFailureKeepsCommittedIdentityAndNextLoginReusesIt() {
        doThrow(new SessionException(SessionException.Code.STORE_UNAVAILABLE)).when(redis).create(any());
        assertThrows(SessionException.class, () -> login.login("synthetic-code"));
        assertEquals(1, db.committedMembers.size()); assertEquals(1, db.committedMappings.size());
        assertTrue(sessionsBySid.isEmpty());
        doReturn(true).when(redis).create(any());
        assertEquals(db.committedMembers.keySet().iterator().next(), login.login("fresh-code").memberId());
        assertEquals(1, db.commitsWithWrites.get());
    }
    @Test void activeOuterTransactionIsRejectedBeforeAuthentication() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThrows(ResidentLoginException.class, () -> login.login("synthetic-code"));
        verifyNoInteractions(gateway, db.members, db.mappings, redis);
    }
    @Test void rollbackDebugLoggingCannotExposeRawRepositoryCause() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(org.springframework.transaction.support.TransactionTemplate.class);
        var previous = logger.getLevel();
        var events = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        events.start(); logger.addAppender(events); logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        try {
            doThrow(new IllegalStateException("synthetic-private-db-identifier")).when(db.members).insert(any(Member.class));
            var failure = assertThrows(ResidentLoginException.class, () -> login.login("synthetic-private-code"));
            assertNull(failure.getCause());
            assertFalse(events.list.isEmpty(), "actual Spring rollback logger observed");
            for (var event : events.list) {
                String diagnostic = event.getFormattedMessage() + ch.qos.logback.classic.spi.ThrowableProxyUtil.asString(event.getThrowableProxy());
                assertFalse(diagnostic.contains("synthetic-private-db-identifier"));
                assertFalse(diagnostic.contains("synthetic-private-code"));
            }
            verifyNoInteractions(redis);
        } finally { logger.detachAppender(events); logger.setLevel(previous); events.stop(); }
    }
    @Test void absentSchemaApprovalRejectsLoginAndExistingSessions() {
        var pair = login.login("synthetic-code"); properties.setSchemaReady(false);
        assertEquals(ResidentLoginException.Code.B04_PENDING, assertThrows(ResidentLoginException.class, () -> login.login("new-code")).getCode());
        assertThrows(SessionException.class, () -> sessions.verifyAccess(pair.session().accessToken(), SessionDomain.RESIDENT, apps.getResidentApplication()));
    }
    @ParameterizedTest @ValueSource(strings = {"APPLICANT", "STORE"})
    void pendingMerchantDomainsNeverAcquireAuthority(String domain) {
        var pair = login.login("synthetic-code");
        var identity = db.committedMappings.values().iterator().next();
        var claimed = new SessionIdentity(pair.memberId(), SessionDomain.valueOf(domain), apps.getMerchantApplication(), identity.getId(), 1L);
        assertEquals(SessionException.Code.AUTHORIZATION_REQUIRED, assertThrows(SessionException.class, () -> verifier.check(claimed)).getCode());
        assertThrows(SessionException.class, () -> sessions.create(claimed));
        assertEquals(1, sessionsBySid.size());
    }

    /** Models per-transaction staged writes + UNIQUE lock. It proves service ordering/rollback, not B04. */
    static class Model implements PlatformTransactionManager {
        final MemberMapper members = mock(MemberMapper.class);
        final WechatIdentityMapper mappings = mock(WechatIdentityMapper.class);
        final Map<String, Member> committedMembers = new ConcurrentHashMap<>();
        final Map<String, WechatIdentity> committedMappings = new ConcurrentHashMap<>();
        final ThreadLocal<Tx> pending = new ThreadLocal<>();
        final ReentrantLock unique = new ReentrantLock();
        final AtomicInteger rollbacks = new AtomicInteger(), commitsWithWrites = new AtomicInteger(), reads = new AtomicInteger();
        volatile CyclicBarrier firstReadBarrier;
        volatile String fault;
        volatile boolean duplicate;
        Model() {
            when(members.insert(any(Member.class))).thenAnswer(i -> {
                if ("member-write".equals(fault)) throw new IllegalStateException("synthetic-db-failure");
                if ("collision-no-winner".equals(fault)) throw new DuplicateKeyException("synthetic-id-collision");
                pending.get().member = i.getArgument(0); return 1;
            });
            when(members.selectById(anyString())).thenAnswer(i -> {
                String id = i.getArgument(0); Tx tx = pending.get();
                return tx != null && tx.member != null && tx.member.getId().equals(id) ? tx.member : committedMembers.get(id);
            });
            when(mappings.selectList(any(QueryWrapper.class))).thenAnswer(i -> {
                QueryWrapper<WechatIdentity> q = i.getArgument(0); q.getSqlSegment();
                var values = new ArrayList<>(q.getParamNameValuePairs().values());
                // Parameter names define equality operands, not map iteration order.
                String app = (String) q.getParamNameValuePairs().get("MPGENVAL1");
                String open = (String) q.getParamNameValuePairs().get("MPGENVAL2");
                assertEquals(2, values.size()); assertTrue(q.getSqlSegment().contains("LIMIT 2"));
                var found = committedMappings.get(app + ":" + open);
                var barrier = firstReadBarrier;
                if (barrier != null && reads.incrementAndGet() <= 6) barrier.await(10, TimeUnit.SECONDS);
                return found == null ? List.of() : duplicate ? List.of(found, found) : List.of(found);
            });
            when(mappings.selectById(anyString())).thenAnswer(i -> committedMappings.values().stream()
                    .filter(m -> m.getId().equals(i.getArgument(0))).findFirst().orElse(null));
            when(mappings.insert(any(WechatIdentity.class))).thenAnswer(i -> {
                if ("mapping-write".equals(fault)) throw new IllegalStateException("synthetic-db-failure");
                Tx tx = pending.get(); unique.lock(); tx.locked = true;
                WechatIdentity value = i.getArgument(0);
                if (committedMappings.containsKey(value.getAppId() + ":" + value.getOpenId())) throw new DuplicateKeyException("synthetic-unique-race");
                tx.mapping = value; return 1;
            });
        }
        public TransactionStatus getTransaction(TransactionDefinition definition) {
            assertNull(pending.get()); Tx tx = new Tx(); pending.set(tx); return tx;
        }
        public void commit(TransactionStatus status) {
            Tx tx = (Tx) status;
            if ("commit".equals(fault)) { rollback(status); throw new TransactionSystemException("synthetic-commit-failure"); }
            try {
                if (tx.member != null) {
                    assertNotNull(tx.mapping); committedMembers.put(tx.member.getId(), tx.member);
                    committedMappings.put(tx.mapping.getAppId() + ":" + tx.mapping.getOpenId(), tx.mapping);
                    commitsWithWrites.incrementAndGet();
                }
            } finally { finish(tx); }
        }
        public void rollback(TransactionStatus status) { rollbacks.incrementAndGet(); finish((Tx) status); }
        void finish(Tx tx) { pending.remove(); if (tx.locked) unique.unlock(); }
        static class Tx extends SimpleTransactionStatus { Member member; WechatIdentity mapping; boolean locked; }
    }
}
