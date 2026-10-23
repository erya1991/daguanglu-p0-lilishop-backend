package cn.lili.common.security.session;

import cn.lili.common.properties.JWTTokenProperties;
import cn.lili.common.security.token.SecretKeyUtil;
import com.google.gson.Gson;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.IntegerOutput;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;

import static cn.lili.common.security.session.SessionException.Code.INVALID_CREDENTIAL;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in integration against the independently verified PREP instance only. No Spring application,
 * database, business service, real identity or old Redis key is used. A guarded delegate journals
 * every random key before the real RedisSessionStore can touch it; all mutations use that store,
 * except one explicitly recorded short-TTL fixture and exact-key cleanup.
 */
@EnabledIfSystemProperty(named = "p02.redis.integration", matches = "approved-prep")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisSessionStoreIntegrationTest {
    private static final Gson JSON = new Gson();
    private static final String RUN = UUID.randomUUID().toString();
    private static final Set<SessionIdentity> IDENTITIES = ConcurrentHashMap.newKeySet();
    private static final Map<String, KeyEvidence> KEYS = new LinkedHashMap<>();
    private static final List<Map<String, Object>> OBSERVATIONS = new ArrayList<>();
    private static Path evidence;
    private static LettuceConnectionFactory factory;
    private static StringRedisTemplate redis;
    private static RedisSessionStore actual;
    private static SessionService service;
    private static SecretKeyUtil signing;
    private static String testCase;
    private static long successfulScripts, conflictScripts;
    private static final SessionService.Store GUARDED = new SessionService.Store() {
        @Override public boolean create(SessionRecord r) {
            String key = RedisSessionStore.key(r.identity().domain(), r.identity().application(), r.sid());
            synchronized (KEYS) {
                if (!KEYS.containsKey(key)) {
                    require(IDENTITIES.contains(r.identity()), "UNREGISTERED_SYNTHETIC_IDENTITY");
                    KEYS.put(key, new KeyEvidence(testCase, key));
                    journal(); // Durable registration BEFORE any Redis operation, including an uncertain CREATE.
                }
            }
            boolean created = actual.create(r);
            synchronized (KEYS) { if (created) KEYS.get(key).created = true; journal(); }
            return created;
        }
        @Override public SessionRecord load(SessionDomain d, String app, String sid) {
            guard(d, app, sid); return actual.load(d, app, sid);
        }
        @Override public boolean rotate(SessionRecord before, SessionRecord next) {
            guard(before.identity().domain(), before.identity().application(), before.sid());
            return actual.rotate(before, next);
        }
        @Override public boolean revoke(SessionRecord r) {
            guard(r.identity().domain(), r.identity().application(), r.sid()); return actual.revoke(r);
        }
    };

    @BeforeAll static void connectOnlyAfterEnvironmentGate() throws Exception {
        evidence = Path.of(System.getProperty("p02.redis.evidence")).toAbsolutePath();
        var gate = JSON.fromJson(Files.readString(evidence.resolve("access.json")), Map.class);
        require(Boolean.TRUE.equals(gate.get("passed")) && "7.2.16".equals(gate.get("redisVersion"))
                && "127.0.0.1".equals(gate.get("host")) && ((Number) gate.get("port")).intValue() == 16379,
                "ENVIRONMENT_GATE_NOT_MET");
        require(Duration.between(Instant.parse((String) gate.get("checkedAt")), Instant.now()).abs()
                .compareTo(Duration.ofHours(1)) < 0, "STALE_ENVIRONMENT_GATE");
        var config = new RedisStandaloneConfiguration("127.0.0.1", 16379);
        config.setDatabase(0);
        config.setUsername("default");
        // Existing PREP protected-file injection, consumed in memory only; no password property/argv/log.
        config.setPassword(Files.readString(Path.of(
                "D:/git/daguanglu-p0-build/F01-03C2D/private/redis-password"), StandardCharsets.UTF_8));
        factory = new LettuceConnectionFactory(config, LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofSeconds(4)).shutdownTimeout(Duration.ofSeconds(1)).build());
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory) {
            @Override public <T> T execute(RedisScript<T> script, List<String> keys, Object... args) {
                synchronized (KEYS) { for (String key : keys) require(KEYS.containsKey(key), "UNREGISTERED_SCRIPT_KEY"); }
                try {
                    T reply = super.execute(script, keys, args); // Actual driver, serializer and Lua execution.
                    synchronized (KEYS) {
                        if (Long.valueOf(1).equals(reply)) successfulScripts++;
                        else if (Long.valueOf(0).equals(reply)) conflictScripts++;
                        else throw new IllegalStateException("UNEXPECTED_SCRIPT_RESULT_TYPE");
                    }
                    return reply;
                } catch (RuntimeException failure) {
                    Throwable root = failure;
                    while (root.getCause() != null) root = root.getCause();
                    String detail = root.getClass().getSimpleName();
                    // Preserve Lua diagnostics, never connection/credential payloads.
                    if (root.getClass().getSimpleName().equals("RedisCommandExecutionException")) {
                        detail += ": " + Objects.toString(root.getMessage(), "")
                                .replaceAll("[A-Za-z0-9_+/=-]{40,}", "[REDACTED]");
                    }
                    observe(Map.of("case", testCase, "scriptFailure", detail));
                    throw failure;
                }
            }
        };
        try (var connection = factory.getConnection()) {
            assertEquals("PONG", connection.ping());
            Properties info = connection.serverCommands().info("server");
            assertNotNull(info);
            assertEquals("7.2.16", info.getProperty("redis_version"));
            assertEquals(gate.get("runId"), info.getProperty("run_id"), "same approved running instance");
        }
        var props = new JWTTokenProperties();
        byte[] random = new byte[48];
        new SecureRandom().nextBytes(random);
        props.setSecretKey(Base64.getEncoder().encodeToString(random));
        Arrays.fill(random, (byte) 0);
        signing = new SecretKeyUtil(props);
        props.setSecretKey(null);
        actual = new RedisSessionStore(redis);
        service = serviceAt(Clock.systemUTC());
        journal();
    }

    @BeforeEach void label(TestInfo info) { testCase = info.getTestMethod().orElseThrow().getName().substring(0, 3); }

    @Test @Order(1) void R01_createAndReadActualSessions() {
        for (SessionDomain domain : SessionDomain.values()) {
            var identity = identity(domain, "create");
            var tokens = service.create(identity);
            var record = load(tokens, identity);
            assertEquals(identity, record.identity());
            assertEquals(0, record.generation());
            assertEquals(SessionRecord.Status.ACTIVE, record.status());
            assertEquals(identity, service.verifyAccess(tokens.accessToken(), domain, identity.application()).identity());
            assertTrue(SessionCredentials.matches(tokens.accessToken(), record.accessDigest()));
            assertTrue(SessionCredentials.matches(tokens.refreshToken(), record.refreshDigest()));
            String raw = redis.opsForValue().get(key(tokens, identity));
            assertNotNull(raw);
            assertFalse(raw.contains(tokens.accessToken()) || raw.contains(tokens.refreshToken()), "only digests persisted");
        }
        observe(Map.of("case", "R01", "domains", 4));
    }

    @Test @Order(2) void R02_refreshConsumesOldAccessAndRefresh() {
        for (SessionDomain domain : SessionDomain.values()) {
            var identity = identity(domain, "refresh");
            var old = service.create(identity);
            invalid(() -> service.verifyAccess(old.refreshToken(), domain, identity.application()));
            invalid(() -> service.refresh(old.accessToken(), domain, identity.application()));
            var next = service.refresh(old.refreshToken(), domain, identity.application());
            assertFalse(old.accessToken().equals(next.accessToken()));
            assertFalse(old.refreshToken().equals(next.refreshToken()));
            invalid(() -> service.verifyAccess(old.accessToken(), domain, identity.application()));
            invalid(() -> service.refresh(old.refreshToken(), domain, identity.application()));
            assertEquals(1, service.verifyAccess(next.accessToken(), domain, identity.application()).generation());
            assertEquals(old.absoluteExpiresAt(), next.absoluteExpiresAt());
            var second = service.refresh(next.refreshToken(), domain, identity.application());
            assertEquals(2, service.verifyAccess(second.accessToken(), domain, identity.application()).generation());
        }
        observe(Map.of("case", "R02", "domains", 4, "refreshesPerDomain", 2));
    }

    @Test @Order(3) void R03_concurrentRefreshHasExactlyOneWinner() throws Exception {
        for (SessionDomain domain : SessionDomain.values()) {
            var identity = identity(domain, "race-refresh");
            var old = service.create(identity);
            var ready = new CountDownLatch(8);
            var go = new CountDownLatch(1);
            try (var workers = Executors.newFixedThreadPool(8)) {
                List<Future<SessionTokens>> attempts = new ArrayList<>();
                for (int i = 0; i < 8; i++) attempts.add(workers.submit(() -> {
                    ready.countDown(); require(go.await(5, TimeUnit.SECONDS), "RACE_START_TIMEOUT");
                    return refreshOrRejected(old, identity);
                }));
                assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown();
                List<SessionTokens> winners = new ArrayList<>();
                for (var attempt : attempts) { var next = attempt.get(10, TimeUnit.SECONDS); if (next != null) winners.add(next); }
                assertEquals(1, winners.size(), "healthy Redis must yield one winner and seven rejections");
                assertEquals(1, service.verifyAccess(winners.getFirst().accessToken(), domain, identity.application()).generation());
                invalid(() -> service.refresh(old.refreshToken(), domain, identity.application()));
                observe(Map.of("case", "R03", "domain", domain.name(), "contenders", 8, "winners", winners.size()));
            }
        }
    }

    @Test @Order(4) void R04_logoutWinsBothOrdersAndConcurrentRefresh() throws Exception {
        for (SessionDomain domain : SessionDomain.values()) {
            var identity = identity(domain, "race-logout");
            // Deterministic order 1: refresh commits before authenticated logout.
            var first = service.create(identity);
            var capability = service.verifyAccess(first.accessToken(), domain, identity.application());
            var refreshed = service.refresh(first.refreshToken(), domain, identity.application());
            service.revoke(capability);
            assertRevoked(first, refreshed, identity);
            // Deterministic order 2: logout commits first.
            var second = service.create(identity);
            service.revoke(service.verifyAccess(second.accessToken(), domain, identity.application()));
            assertRevoked(second, null, identity);
            for (int round = 0; round < 8; round++) {
                var old = service.create(identity);
                var verified = service.verifyAccess(old.accessToken(), domain, identity.application());
                var go = new CountDownLatch(1);
                try (var workers = Executors.newFixedThreadPool(2)) {
                    var refresh = workers.submit(() -> { require(go.await(5, TimeUnit.SECONDS), "RACE_START_TIMEOUT"); return refreshOrRejected(old, identity); });
                    var logout = workers.submit(() -> { require(go.await(5, TimeUnit.SECONDS), "RACE_START_TIMEOUT"); service.revoke(verified); return true; });
                    go.countDown();
                    var next = refresh.get(10, TimeUnit.SECONDS);
                    assertTrue(logout.get(10, TimeUnit.SECONDS));
                    assertRevoked(old, next, identity);
                }
            }
        }
        observe(Map.of("case", "R04", "deterministicOrders", 8, "concurrentRaces", 32));
    }

    @Test @Order(5) void R05_devicesDomainsAndApplicationsRemainIndependent() {
        var resident = identity(SessionDomain.RESIDENT, "shared-app");
        List<SessionIdentity> identities = new ArrayList<>(List.of(resident, resident,
                identity(SessionDomain.APPLICANT, "shared-app"), identity(SessionDomain.STORE, "shared-app"),
                identity(SessionDomain.MANAGER, "manager-app"), identity(SessionDomain.RESIDENT, "other-app")));
        List<SessionTokens> sessions = identities.stream().map(service::create).toList();
        service.revoke(service.verifyAccess(sessions.getFirst().accessToken(), resident.domain(), resident.application()));
        assertRevoked(sessions.getFirst(), null, resident);
        for (int i = 1; i < sessions.size(); i++) {
            var identity = identities.get(i); var old = sessions.get(i);
            service.verifyAccess(old.accessToken(), identity.domain(), identity.application());
            var next = service.refresh(old.refreshToken(), identity.domain(), identity.application());
            service.verifyAccess(next.accessToken(), identity.domain(), identity.application());
        }
        // Same random sid, different namespace: separate physical keys in actual Redis.
        String sid = UUID.randomUUID().toString().replace("-", "");
        long now = System.currentTimeMillis();
        var one = fixture(sid, resident, now, now + 900_000, now + resident.domain().absoluteLifetime().toMillis());
        var otherApp = identity(SessionDomain.RESIDENT, "same-sid-other-app");
        var otherDomain = identity(SessionDomain.STORE, "shared-app");
        var two = fixture(sid, otherApp, now, now + 900_000, one.absoluteExpiresAt());
        var three = fixture(sid, otherDomain, now, now + 900_000, one.absoluteExpiresAt());
        assertTrue(GUARDED.create(one)); assertTrue(GUARDED.create(two)); assertTrue(GUARDED.create(three));
        assertTrue(GUARDED.revoke(one));
        assertEquals(SessionRecord.Status.ACTIVE, GUARDED.load(two.identity().domain(), two.identity().application(), sid).status());
        assertEquals(SessionRecord.Status.ACTIVE, GUARDED.load(three.identity().domain(), three.identity().application(), sid).status());
        observe(Map.of("case", "R05", "independentSessions", 6, "sameSidNamespaces", 3));
    }

    @Test @Order(6) void R06_realTtlAbsoluteDeadlineAndExpiry() throws Exception {
        for (SessionDomain domain : SessionDomain.values()) {
            var identity = identity(domain, "ttl"); var tokens = service.create(identity);
            var record = load(tokens, identity);
            assertEquals(domain.absoluteLifetime().toMillis(), record.absoluteExpiresAt() - record.createdAt());
            assertEquals(900_000, record.accessExpiresAt() - record.createdAt());
            long ttl = redis.getExpire(key(tokens, identity), TimeUnit.MILLISECONDS);
            assertTrue(ttl > domain.absoluteLifetime().toMillis() - 10_000 && ttl <= domain.absoluteLifetime().toMillis());
            assertDeadline(tokens, identity);
            var next = service.refresh(tokens.refreshToken(), domain, identity.application());
            assertDeadline(next, identity); // Refresh never slides the absolute deadline.
            var atAccessExpiry = serviceAt(Clock.fixed(Instant.ofEpochMilli(next.accessExpiresAt()), ZoneOffset.UTC));
            invalid(() -> atAccessExpiry.verifyAccess(next.accessToken(), domain, identity.application()));
            var atAbsoluteExpiry = serviceAt(Clock.fixed(Instant.ofEpochMilli(next.absoluteExpiresAt()), ZoneOffset.UTC));
            invalid(() -> atAbsoluteExpiry.refresh(next.refreshToken(), domain, identity.application()));
            service.revoke(service.verifyAccess(next.accessToken(), domain, identity.application()));
            assertDeadline(next, identity); // Revocation retains the same deadline.
            observe(Map.of("case", "R06", "domain", domain.name(), "observedTtlMs", ttl,
                    "absoluteLifetimeMs", domain.absoluteLifetime().toMillis(), "pxatDeadlineExact", true));
        }
        // Explicit fixture: exercise physical Redis expiry without waiting 1/15 days or changing server time.
        var identity = identity(SessionDomain.APPLICANT, "physical-expiry"); var expiring = service.create(identity);
        String key = key(expiring, identity);
        assertTrue(Boolean.TRUE.equals(redis.expire(key, Duration.ofMillis(1200))));
        observe(Map.of("case", "R06", "fixture", "shortened TTL on one registered test key only", "ttlMs", 1200));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (Boolean.TRUE.equals(redis.hasKey(key)) && System.nanoTime() < deadline) Thread.sleep(30);
        assertFalse(Boolean.TRUE.equals(redis.hasKey(key)));
        assertNull(load(expiring, identity));
        invalid(() -> service.verifyAccess(expiring.accessToken(), identity.domain(), identity.application()));
        invalid(() -> service.refresh(expiring.refreshToken(), identity.domain(), identity.application()));
    }

    @Test @Order(7) void R07_actualLuaUtf8LongRepliesAndPxat() {
        var identity = identity(SessionDomain.RESIDENT, "应用-é-🙂");
        var tokens = service.create(identity); var record = load(tokens, identity);
        assertEquals(identity, record.identity());
        assertEquals(record, JSON.fromJson(redis.opsForValue().get(key(tokens, identity)), SessionRecord.class));
        assertFalse(GUARDED.create(record), "CREATE conflict must return Lua integer 0 without overwrite");
        var next = record.rotated(SessionCredentials.digest("next-access-" + RUN),
                SessionCredentials.digest("next-refresh-" + RUN), record.accessExpiresAt());
        assertTrue(GUARDED.rotate(record, next));
        assertFalse(GUARDED.rotate(record, next), "stale generation must return Lua integer 0");
        assertEquals(next, load(tokens, identity));
        assertTrue(GUARDED.revoke(record));
        assertEquals(SessionRecord.Status.REVOKED, load(tokens, identity).status());
        assertDeadline(tokens, identity);
        assertTrue(successfulScripts > 0 && conflictScripts > 0);
        observe(Map.of("case", "R07", "utf8RoundTrip", true, "integerReplyType", "java.lang.Long",
                "scriptSuccessReplies", successfulScripts, "scriptConflictReplies", conflictScripts));
    }

    @AfterAll static void cleanupOnlyRegisteredKeys() {
        int removed = 0, alreadyAbsent = 0, remaining = 0;
        try {
            if (redis != null) for (KeyEvidence entry : KEYS.values()) {
                try {
                    boolean deleted = Boolean.TRUE.equals(redis.delete(entry.key));
                    if (deleted) removed++; else alreadyAbsent++;
                    entry.absentAfterCleanup = !Boolean.TRUE.equals(redis.hasKey(entry.key));
                    if (!entry.absentAfterCleanup) remaining++;
                } catch (RuntimeException failure) { entry.cleanupError = failure.getClass().getSimpleName(); remaining++; }
            }
            journal();
            observe(Map.of("phase", "cleanup", "registered", KEYS.size(), "created",
                    KEYS.values().stream().filter(k -> k.created).count(), "deleted", removed,
                    "alreadyAbsent", alreadyAbsent, "remainingOrUnverified", remaining));
            assertEquals(0, remaining, "only exact registered keys were considered; no wider cleanup attempted");
        } finally {
            if (signing != null) signing.destroy();
            if (factory != null) factory.destroy();
        }
    }

    private static SessionIdentity identity(SessionDomain domain, String suffix) {
        var identity = new SessionIdentity("synthetic-" + RUN, domain, "p02-it-" + RUN + "-" + suffix,
                domain == SessionDomain.MANAGER ? null : "synthetic-map-" + RUN,
                domain == SessionDomain.MANAGER ? null : 1L);
        IDENTITIES.add(identity); return identity;
    }
    private static SessionService serviceAt(Clock clock) {
        return new SessionService(GUARDED, signing, identity -> {
            require(IDENTITIES.contains(identity), "SYNTHETIC_AUTHORIZATION_REQUIRED");
        }, clock);
    }
    private static SessionRecord fixture(String sid, SessionIdentity identity, long now, long access, long absolute) {
        return new SessionRecord(1, sid, identity, SessionRecord.Status.ACTIVE, 0,
                SessionCredentials.digest("fixture-access-" + RUN), SessionCredentials.digest("fixture-refresh-" + RUN), now, access, absolute);
    }
    private static SessionRecord load(SessionTokens tokens, SessionIdentity identity) {
        return GUARDED.load(identity.domain(), identity.application(), tokens.sid());
    }
    private static String key(SessionTokens tokens, SessionIdentity identity) {
        return guard(identity.domain(), identity.application(), tokens.sid());
    }
    private static String guard(SessionDomain domain, String app, String sid) {
        String key = RedisSessionStore.key(domain, app, sid);
        synchronized (KEYS) { require(KEYS.containsKey(key), "UNREGISTERED_KEY_ACCESS"); }
        return key;
    }
    private static void assertDeadline(SessionTokens tokens, SessionIdentity identity) {
        byte[] k = key(tokens, identity).getBytes(StandardCharsets.UTF_8);
        try (var connection = factory.getConnection()) {
            // Spring's untyped execute defaults unknown commands to ByteArrayOutput. PEXPIRETIME
            // returns an integer; select the real driver's typed decoder for this read-only probe.
            Object actualDeadline = assertInstanceOf(LettuceConnection.class, connection)
                    .execute("PEXPIRETIME", new IntegerOutput<>(ByteArrayCodec.INSTANCE), k);
            assertEquals(tokens.absoluteExpiresAt(), ((Number) actualDeadline).longValue());
        }
    }
    private static SessionTokens refreshOrRejected(SessionTokens tokens, SessionIdentity identity) {
        try { return service.refresh(tokens.refreshToken(), identity.domain(), identity.application()); }
        catch (SessionException failure) { assertEquals(INVALID_CREDENTIAL, failure.getCode()); return null; }
    }
    private static void assertRevoked(SessionTokens old, SessionTokens next, SessionIdentity identity) {
        assertEquals(SessionRecord.Status.REVOKED, load(old, identity).status());
        invalid(() -> service.verifyAccess(old.accessToken(), identity.domain(), identity.application()));
        invalid(() -> service.refresh(old.refreshToken(), identity.domain(), identity.application()));
        if (next != null) {
            invalid(() -> service.verifyAccess(next.accessToken(), identity.domain(), identity.application()));
            invalid(() -> service.refresh(next.refreshToken(), identity.domain(), identity.application()));
        }
    }
    private static void invalid(org.junit.jupiter.api.function.Executable action) {
        assertEquals(INVALID_CREDENTIAL, assertThrows(SessionException.class, action).getCode());
    }
    private static void require(boolean condition, String message) { if (!condition) throw new IllegalStateException(message); }
    private static void journal() {
        if (evidence == null) return;
        try { Files.writeString(evidence.resolve("registered-keys.json"), JSON.toJson(KEYS.values()), StandardCharsets.UTF_8); }
        catch (Exception failure) { throw new IllegalStateException("KEY_JOURNAL_FAILED"); }
    }
    private static synchronized void observe(Map<String, Object> observation) {
        OBSERVATIONS.add(observation);
        try { if (evidence != null) Files.writeString(evidence.resolve("observations.json"), JSON.toJson(OBSERVATIONS), StandardCharsets.UTF_8); }
        catch (Exception failure) { throw new IllegalStateException("EVIDENCE_WRITE_FAILED"); }
    }
    private static final class KeyEvidence {
        final String testCase, key;
        boolean created, absentAfterCleanup;
        String cleanupError;
        KeyEvidence(String testCase, String key) { this.testCase = testCase; this.key = key; }
    }
}
