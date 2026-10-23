package cn.lili.common.security.session;

import cn.lili.common.properties.JWTTokenProperties;
import cn.lili.common.security.token.SecretKeyUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

import static cn.lili.common.security.session.SessionException.Code.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Pure unit tests with a linearizable in-memory model, not a Redis/Lua integration test.
 * REQ-S0-S01-003..010 / AC-S0-S01-002..006; the formal TC IDs remain unchanged.
 */
class SessionServiceTest {
    @ParameterizedTest @EnumSource(SessionDomain.class)
    void T01_createsAndVerifiesAllFourDomains(SessionDomain domain) {
        var f = new Fixture(domain);
        var pair = f.create();
        var verified = f.verify(pair.accessToken());
        assertEquals(f.identity, verified.identity());
        assertEquals(pair.sid(), verified.sid());
        assertEquals(0, verified.generation());
        assertEquals(Duration.ofMinutes(15).toMillis(), pair.accessExpiresAt() - f.clock.millis());
        assertEquals(domain.absoluteLifetime().toMillis(), pair.absoluteExpiresAt() - f.clock.millis());
        var record = f.store.only();
        assertTrue(SessionCredentials.matches(pair.accessToken(), record.accessDigest()));
        assertTrue(SessionCredentials.matches(pair.refreshToken(), record.refreshDigest()));
        if (domain == SessionDomain.APPLICANT) {
            assertTrue(pair.accessToken().startsWith("s0o1."));
        } else {
            Claims claims = f.keys.parseClaims(pair.accessToken());
            assertEquals(f.identity.subject(), claims.getSubject());
            assertEquals(domain.name(), claims.get("domain"));
            assertEquals("ACCESS", claims.get("use"));
            assertEquals(1, claims.get("sv", Integer.class));
            assertNotNull(claims.getId());
        }
    }

    @Test
    void T02_jwtAndOpaqueAreNotInterchangeable() {
        var applicant = new Fixture(SessionDomain.APPLICANT);
        var resident = new Fixture(SessionDomain.RESIDENT);
        var opaque = applicant.create();
        var jwt = resident.create();
        rejected(INVALID_CREDENTIAL, () -> resident.verify(opaque.accessToken()));
        rejected(INVALID_CREDENTIAL, () -> applicant.verify(jwt.accessToken()));
        rejected(INVALID_CREDENTIAL, () -> resident.refresh(opaque.refreshToken()));
        rejected(INVALID_CREDENTIAL, () -> applicant.refresh(jwt.refreshToken()));
    }

    @Test
    void T02_legacyAndUnsignedJwtAreNotNewSessionCredentials() {
        var f = new Fixture(SessionDomain.RESIDENT);
        String legacy = Jwts.builder().subject("synthetic-subject")
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(f.keys.generalKey(), Jwts.SIG.HS256).compact();
        String unsigned = Jwts.builder().subject("synthetic-subject").compact();
        rejected(INVALID_CREDENTIAL, () -> f.verify(legacy));
        rejected(INVALID_CREDENTIAL, () -> f.refresh(legacy));
        rejected(INVALID_CREDENTIAL, () -> f.verify(unsigned));
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    void T03_accessCannotRefresh(SessionDomain domain) {
        var f = new Fixture(domain);
        var pair = f.create();
        rejected(INVALID_CREDENTIAL, () -> f.refresh(pair.accessToken()));
        assertEquals(0, f.store.only().generation());
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    void T04_refreshCannotAuthorizeAccess(SessionDomain domain) {
        var f = new Fixture(domain);
        var pair = f.create();
        rejected(INVALID_CREDENTIAL, () -> f.verify(pair.refreshToken()));
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    void T05_fixedSameSecondStillProducesUniqueTickets(SessionDomain domain) {
        var f = new Fixture(domain);
        Set<String> hashes = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            var first = f.create();
            var next = f.refresh(first.refreshToken());
            for (var pair : List.of(first, next)) {
                assertTrue(hashes.add(SessionCredentials.digest(pair.accessToken())), "unique access digest");
                assertTrue(hashes.add(SessionCredentials.digest(pair.refreshToken())), "unique refresh digest");
            }
            f.verify(next.accessToken());
        }
        assertEquals(80, hashes.size());
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    void T06_concurrentConsumersHaveExactlyOneWinner(SessionDomain domain) throws Exception {
        var f = new Fixture(domain);
        var pair = f.create();
        f.barrier = new CyclicBarrier(8);
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<SessionTokens>> jobs = new ArrayList<>();
            for (int i = 0; i < 8; i++) jobs.add(pool.submit(() -> {
                try { return f.refresh(pair.refreshToken()); }
                catch (SessionException denied) {
                    assertEquals(INVALID_CREDENTIAL, denied.getCode());
                    return null;
                }
            }));
            List<SessionTokens> winners = new ArrayList<>();
            for (var job : jobs) {
                var winner = job.get(10, TimeUnit.SECONDS);
                if (winner != null) winners.add(winner);
            }
            f.barrier = null;
            assertEquals(1, winners.size());
            assertEquals(1, f.store.only().generation());
            f.verify(winners.getFirst().accessToken());
        } finally {
            f.barrier = null;
            pool.shutdownNow();
        }
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    void T07_rotationInvalidatesBothOldCredentials(SessionDomain domain) {
        var f = new Fixture(domain);
        var old = f.create();
        var next = f.refresh(old.refreshToken());
        rejected(INVALID_CREDENTIAL, () -> f.verify(old.accessToken()));
        rejected(INVALID_CREDENTIAL, () -> f.refresh(old.refreshToken()));
        f.verify(next.accessToken());
        f.verify(f.refresh(next.refreshToken()).accessToken());
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    void T08_tamperingReplayAndExpiryAreRejected(SessionDomain domain) {
        var f = new Fixture(domain);
        var pair = f.create();
        rejected(INVALID_CREDENTIAL, () -> f.refresh(tamper(pair.refreshToken())));
        rejected(INVALID_CREDENTIAL, () -> f.verify(tamper(pair.accessToken())));
        f.clock.advance(Duration.ofMinutes(15));
        rejected(INVALID_CREDENTIAL, () -> f.verify(pair.accessToken()));
        var next = f.refresh(pair.refreshToken()); // Access expiry does not prematurely expire Refresh.
        rejected(INVALID_CREDENTIAL, () -> f.refresh(pair.refreshToken()));
        f.clock.set(pair.absoluteExpiresAt());
        rejected(INVALID_CREDENTIAL, () -> f.refresh(next.refreshToken()));
        rejected(INVALID_CREDENTIAL, () -> f.verify(next.accessToken()));
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    void T09_logoutIsTerminalEvenWithAnAuthenticatedRefreshRace(SessionDomain domain) {
        var f = new Fixture(domain);
        var pair = f.create();
        var logoutContext = f.verify(pair.accessToken());
        var raced = f.refresh(pair.refreshToken());
        f.service.revoke(logoutContext); // old generation must still revoke the current sid.
        f.service.revoke(logoutContext); // idempotent within the authenticated request window.
        for (var tokens : List.of(pair, raced)) {
            rejected(INVALID_CREDENTIAL, () -> f.verify(tokens.accessToken()));
            rejected(INVALID_CREDENTIAL, () -> f.refresh(tokens.refreshToken()));
        }
        assertEquals(SessionRecord.Status.REVOKED, f.store.only().status());
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    void T09_logoutWinningBeforeRefreshCannotBeResurrected(SessionDomain domain) throws Exception {
        var f = new Fixture(domain);
        var pair = f.create();
        var context = f.verify(pair.accessToken());
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        f.beforeAuthorize = () -> {
            entered.countDown();
            try { assertTrue(resume.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        };
        var pool = Executors.newSingleThreadExecutor();
        try {
            var job = pool.submit(() -> rejected(INVALID_CREDENTIAL, () -> f.refresh(pair.refreshToken())));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            f.service.revoke(context);
            resume.countDown();
            job.get(10, TimeUnit.SECONDS);
            assertEquals(SessionRecord.Status.REVOKED, f.store.only().status());
        } finally {
            resume.countDown();
            f.beforeAuthorize = null;
            pool.shutdownNow();
        }
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    void T10_otherDeviceAndOtherDomainsRemainIndependent(SessionDomain domain) {
        var f = new Fixture(domain);
        var first = f.create();
        var second = f.create();
        assertFalse(first.sid().equals(second.sid()));
        var firstContext = f.verify(first.accessToken());
        f.verify(second.accessToken());
        f.service.revoke(firstContext);
        f.verify(second.accessToken());
        f.verify(f.refresh(second.refreshToken()).accessToken());
        var otherIdentity = identity(domain == SessionDomain.MANAGER ? SessionDomain.RESIDENT : SessionDomain.MANAGER);
        f.allowed.add(otherIdentity);
        var other = f.service.create(otherIdentity);
        f.service.verifyAccess(other.accessToken(), otherIdentity.domain(), otherIdentity.application());
        f.service.refresh(other.refreshToken(), otherIdentity.domain(), otherIdentity.application());
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    void T11_domainsAndTrustedApplicationsMustMatch(SessionDomain domain) {
        var f = new Fixture(domain);
        var pair = f.create();
        for (var target : SessionDomain.values()) {
            if (target == domain) continue;
            rejected(INVALID_CREDENTIAL, () -> f.service.verifyAccess(pair.accessToken(), target, f.identity.application()));
            rejected(INVALID_CREDENTIAL, () -> f.service.refresh(pair.refreshToken(), target, f.identity.application()));
        }
        rejected(INVALID_CREDENTIAL, () -> f.service.verifyAccess(pair.accessToken(), domain, "synthetic-other-app"));
        rejected(INVALID_CREDENTIAL, () -> f.service.refresh(pair.refreshToken(), domain, "synthetic-other-app"));
        assertEquals(0, f.store.only().generation());
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    void T12_refreshNeverExtendsAbsoluteDeadline(SessionDomain domain) {
        var f = new Fixture(domain);
        var pair = f.create();
        f.clock.set(pair.absoluteExpiresAt() - Duration.ofMinutes(5).toMillis());
        var next = f.refresh(pair.refreshToken());
        assertEquals(pair.absoluteExpiresAt(), next.absoluteExpiresAt());
        assertEquals(pair.absoluteExpiresAt(), next.accessExpiresAt());
        assertEquals(pair.absoluteExpiresAt(), f.store.only().absoluteExpiresAt());
        f.clock.advance(Duration.ofMinutes(4));
        var last = f.refresh(next.refreshToken());
        assertEquals(pair.absoluteExpiresAt(), last.accessExpiresAt());
        f.clock.advance(Duration.ofMinutes(1));
        rejected(INVALID_CREDENTIAL, () -> f.refresh(last.refreshToken()));
        rejected(INVALID_CREDENTIAL, () -> f.verify(last.accessToken()));
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    void T13_storeFailureOrUnknownCommitNeverReturnsCredentials(SessionDomain domain) {
        var f = new Fixture(domain);
        f.store.fault = Fault.CREATE;
        rejected(STORE_UNAVAILABLE, f::create);
        f.store.fault = null;
        var pair = f.create();
        f.store.fault = Fault.LOAD;
        rejected(STORE_UNAVAILABLE, () -> f.verify(pair.accessToken()));
        rejected(STORE_UNAVAILABLE, () -> f.refresh(pair.refreshToken()));
        f.store.fault = Fault.ROTATE_BEFORE;
        rejected(STORE_UNAVAILABLE, () -> f.refresh(pair.refreshToken()));
        assertEquals(0, f.store.only().generation());
        f.store.fault = Fault.ROTATE_AFTER;
        rejected(STORE_UNAVAILABLE, () -> f.refresh(pair.refreshToken()));
        assertEquals(1, f.store.only().generation());
        f.store.fault = null;
        rejected(INVALID_CREDENTIAL, () -> f.refresh(pair.refreshToken()));
        rejected(INVALID_CREDENTIAL, () -> f.verify(pair.accessToken()));
        var recovery = f.create(); // Safe re-login creates an independent sid, not replayed tokens.
        var logout = f.verify(recovery.accessToken());
        f.store.fault = Fault.REVOKE_AFTER;
        rejected(STORE_UNAVAILABLE, () -> f.service.revoke(logout));
        f.store.fault = null;
        rejected(INVALID_CREDENTIAL, () -> f.verify(recovery.accessToken()));
        rejected(INVALID_CREDENTIAL, () -> f.refresh(recovery.refreshToken()));
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    void T14_currentAuthorizationAndBindingVersionAreRequired(SessionDomain domain) {
        var f = new Fixture(domain);
        var pair = f.create();
        f.allowed.remove(f.identity);
        if (domain != SessionDomain.MANAGER) {
            // Only the new mapping version is now authorized; the old JWT/opaque Session stays denied.
            f.allowed.add(new SessionIdentity(f.identity.subject(), domain, f.identity.application(),
                    f.identity.bindingId(), f.identity.bindingVersion() + 1));
        }
        rejected(DENIED, () -> f.verify(pair.accessToken()));
        rejected(DENIED, () -> f.refresh(pair.refreshToken()));
        rejected(DENIED, f::create);
        assertEquals(0, f.store.only().generation());
        var unavailable = new SessionService(f.store, f.keys, null, f.clock);
        rejected(AUTHORIZATION_REQUIRED, () -> unavailable.create(f.identity));
        rejected(AUTHORIZATION_REQUIRED, () -> unavailable.verifyAccess(pair.accessToken(), domain, f.identity.application()));
        rejected(AUTHORIZATION_REQUIRED, () -> unavailable.refresh(pair.refreshToken(), domain, f.identity.application()));
    }

    @Test
    @SuppressWarnings("unchecked")
    void T14_springEntryPointHasNoMissingProviderFallback() {
        var store = mock(RedisSessionStore.class);
        ObjectProvider<SessionAuthorization> providers = mock(ObjectProvider.class);
        var f = new Fixture(SessionDomain.RESIDENT);
        var production = new SessionService(store, f.keys, providers);
        rejected(AUTHORIZATION_REQUIRED, () -> production.create(f.identity));
        verifyNoInteractions(store);
    }

    @Test
    void T14_currentSyntheticClerkAndStoreGrantIsChecked() {
        var f = new Fixture(SessionDomain.STORE);
        var pair = f.create();
        assertEquals("synthetic-clerk", f.storeGrant.clerkId());
        assertEquals("synthetic-store", f.storeGrant.storeId());
        f.storeGrant = new StoreGrant(f.storeGrant.clerkId(), f.storeGrant.storeId(), false);
        rejected(DENIED, () -> f.verify(pair.accessToken()));
        rejected(DENIED, () -> f.refresh(pair.refreshToken()));
        var forged = new SessionIdentity("synthetic-other-subject", SessionDomain.STORE,
                f.identity.application(), "synthetic-other-mapping", 1L);
        rejected(DENIED, () -> f.service.create(forged));
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    @ExtendWith(OutputCaptureExtension.class)
    void T15_diagnosticsAndErrorsDoNotExposeCredentials(SessionDomain domain, CapturedOutput output) {
        var f = new Fixture(domain);
        var pair = f.create();
        String invalidAccess = tamper(pair.accessToken());
        var failure = rejected(INVALID_CREDENTIAL, () -> f.verify(invalidAccess));
        var writer = new StringWriter();
        failure.printStackTrace(new PrintWriter(writer));
        String objects = writer + pair.toString() + f.store.only() + f.identity + f.verify(pair.accessToken());
        assertNull(failure.getCause());
        f.beforeAuthorize = () -> { throw new IllegalStateException(pair.refreshToken()); };
        var denied = rejected(DENIED, () -> f.verify(pair.accessToken()));
        assertNull(denied.getCause());
        assertEquals("SESSION_DENIED", denied.getMessage());
        String diagnostic = output.getAll() + objects + denied;
        assertFalse(diagnostic.contains(pair.accessToken()), "access must be redacted");
        assertFalse(diagnostic.contains(pair.refreshToken()), "refresh must be redacted");
        assertFalse(diagnostic.contains(invalidAccess), "invalid credentials must also be redacted");
    }

    private static String tamper(String token) {
        int position = token.lastIndexOf('.') + 1;
        char replacement = token.charAt(position) == 'A' ? 'B' : 'A';
        return token.substring(0, position) + replacement + token.substring(position + 1);
    }

    private static SessionException rejected(SessionException.Code code, org.junit.jupiter.api.function.Executable call) {
        var failure = assertThrows(SessionException.class, call);
        assertEquals(code, failure.getCode());
        return failure;
    }

    private static SessionIdentity identity(SessionDomain domain) {
        String app = switch (domain) {
            case RESIDENT -> "synthetic-resident-app";
            case STORE, APPLICANT -> "synthetic-merchant-app";
            case MANAGER -> "synthetic-manager-web";
        };
        return new SessionIdentity("synthetic-subject", domain, app,
                domain == SessionDomain.MANAGER ? null : "synthetic-mapping",
                domain == SessionDomain.MANAGER ? null : 1L);
    }

    private static final class Fixture {
        final MutableClock clock = new MutableClock();
        final InMemoryStore store = new InMemoryStore(clock);
        final Set<SessionIdentity> allowed = ConcurrentHashMap.newKeySet();
        final SessionIdentity identity;
        final SecretKeyUtil keys;
        final SessionService service;
        volatile CyclicBarrier barrier;
        volatile Runnable beforeAuthorize;
        volatile StoreGrant storeGrant = new StoreGrant("synthetic-clerk", "synthetic-store", true);

        Fixture(SessionDomain domain) {
            identity = identity(domain);
            allowed.add(identity);
            byte[] secret = new byte[32];
            new SecureRandom().nextBytes(secret);
            var properties = new JWTTokenProperties();
            properties.setSecretKey(Base64.getEncoder().encodeToString(secret));
            keys = new SecretKeyUtil(properties);
            service = new SessionService(store, keys, context -> {
                if (!allowed.contains(context)) throw new SessionException(DENIED);
                if (context.domain() == SessionDomain.STORE && !storeGrant.enabled()) throw new SessionException(DENIED);
                var action = beforeAuthorize;
                if (action != null) action.run();
                var gate = barrier;
                if (gate != null) {
                    try { gate.await(5, TimeUnit.SECONDS); }
                    catch (Exception e) { throw new AssertionError("offline concurrency barrier failed"); }
                }
            }, clock);
        }

        SessionTokens create() { return service.create(identity); }
        SessionService.VerifiedSession verify(String access) {
            return service.verifyAccess(access, identity.domain(), identity.application());
        }
        SessionTokens refresh(String refresh) { return service.refresh(refresh, identity.domain(), identity.application()); }
    }

    private record StoreGrant(String clerkId, String storeId, boolean enabled) { }

    private enum Fault { CREATE, LOAD, ROTATE_BEFORE, ROTATE_AFTER, REVOKE_AFTER }

    private static final class InMemoryStore implements SessionService.Store {
        private final Map<String, SessionRecord> records = new HashMap<>();
        private final Clock clock;
        volatile Fault fault;
        InMemoryStore(Clock clock) { this.clock = clock; }
        private void fail(Fault value) { if (fault == value) throw new SessionException(STORE_UNAVAILABLE); }
        @Override public synchronized boolean create(SessionRecord record) {
            fail(Fault.CREATE);
            return records.putIfAbsent(key(record), record) == null;
        }
        @Override public synchronized SessionRecord load(SessionDomain domain, String app, String sid) {
            fail(Fault.LOAD);
            return records.get(RedisSessionStore.key(domain, app, sid));
        }
        @Override public synchronized boolean rotate(SessionRecord expected, SessionRecord replacement) {
            fail(Fault.ROTATE_BEFORE);
            var current = records.get(key(expected));
            if (!expected.equals(current) || current.status() != SessionRecord.Status.ACTIVE
                    || clock.millis() >= current.absoluteExpiresAt()) return false;
            records.put(key(expected), replacement);
            fail(Fault.ROTATE_AFTER);
            return true;
        }
        @Override public synchronized boolean revoke(SessionRecord authenticated) {
            var current = records.get(key(authenticated));
            if (current == null || !current.identity().equals(authenticated.identity())
                    || clock.millis() >= current.absoluteExpiresAt()) return false;
            records.put(key(current), current.revoked());
            fail(Fault.REVOKE_AFTER);
            return true;
        }
        synchronized SessionRecord only() {
            assertEquals(1, records.size());
            return records.values().iterator().next();
        }
        private String key(SessionRecord record) {
            return RedisSessionStore.key(record.identity().domain(), record.identity().application(), record.sid());
        }
    }

    private static final class MutableClock extends Clock {
        private final AtomicLong millis = new AtomicLong(Instant.now().truncatedTo(ChronoUnit.SECONDS).toEpochMilli());
        void set(long value) { millis.set(value); }
        void advance(Duration duration) { millis.addAndGet(duration.toMillis()); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis.get()); }
        @Override public long millis() { return millis.get(); }
    }
}
