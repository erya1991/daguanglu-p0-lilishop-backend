package cn.lili.common.security.session;

import cn.lili.common.security.token.SecretKeyUtil;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

import static cn.lili.common.security.session.SessionCredentials.Use.ACCESS;
import static cn.lili.common.security.session.SessionCredentials.Use.REFRESH;
import static cn.lili.common.security.session.SessionException.Code.*;

/**
 * Internal Stage 0 protocol only. Does not install a Controller/filter or change legacy login.
 * P02-2 must pass fixed server-owned domain/application values, never untrusted headers.
 */
@Service
public final class SessionService {
    private static final long ACCESS_MILLIS = Duration.ofMinutes(15).toMillis();
    private final Store store;
    private final SessionCredentials credentials;
    private final SessionAuthorization authorization;
    private final Clock clock;

    @Autowired
    public SessionService(RedisSessionStore store, SecretKeyUtil keys,
                          ObjectProvider<SessionAuthorization> providers) {
        this(store, keys, identity -> {
            SessionAuthorization current = providers.getIfUnique();
            if (current == null) throw new SessionException(AUTHORIZATION_REQUIRED);
            current.check(identity);
        }, Clock.systemUTC());
    }

    // Package-scoped seam for offline tests; no production in-memory fallback.
    SessionService(Store store, SecretKeyUtil keys, SessionAuthorization authorization, Clock clock) {
        this.store = Objects.requireNonNull(store);
        this.credentials = new SessionCredentials(Objects.requireNonNull(keys));
        this.authorization = authorization;
        this.clock = Objects.requireNonNull(clock);
    }

    public SessionTokens create(SessionIdentity trustedIdentity) {
        if (trustedIdentity == null) throw new SessionException(INVALID_CONTEXT);
        authorize(trustedIdentity);
        long now = now();
        long absolute = now + trustedIdentity.domain().absoluteLifetime().toMillis();
        long accessExpires = Math.min(now + ACCESS_MILLIS, absolute);
        String sid = credentials.sid();
        SessionTokens tokens = issue(sid, trustedIdentity, 0, now, accessExpires, absolute);
        var record = new SessionRecord(1, sid, trustedIdentity, SessionRecord.Status.ACTIVE, 0,
                SessionCredentials.digest(tokens.accessToken()), SessionCredentials.digest(tokens.refreshToken()),
                now, accessExpires, absolute);
        if (!store.create(record)) throw new SessionException(STORE_UNAVAILABLE);
        return tokens;
    }

    public VerifiedSession verifyAccess(String token, SessionDomain expectedDomain, String trustedApplication) {
        return new VerifiedSession(authenticate(token, ACCESS, expectedDomain, trustedApplication));
    }

    public SessionTokens refresh(String token, SessionDomain expectedDomain, String trustedApplication) {
        SessionRecord old = authenticate(token, REFRESH, expectedDomain, trustedApplication);
        long now = now();
        if (now >= old.absoluteExpiresAt() || old.generation() == 1_000_000_000L) {
            throw new SessionException(INVALID_CREDENTIAL);
        }
        long accessExpires = Math.min(now + ACCESS_MILLIS, old.absoluteExpiresAt());
        SessionTokens next = issue(old.sid(), old.identity(), old.generation() + 1,
                now, accessExpires, old.absoluteExpiresAt());
        SessionRecord replacement = old.rotated(SessionCredentials.digest(next.accessToken()),
                SessionCredentials.digest(next.refreshToken()), accessExpires);
        if (!store.rotate(old, replacement)) throw new SessionException(INVALID_CREDENTIAL);
        return next;
    }

    /**
     * A short-lived capability produced by verifyAccess, normally kept in the request context.
     * Ignore generation when revoking: a concurrent refresh must not defeat an authenticated logout.
     */
    public void revoke(VerifiedSession currentSession) {
        if (currentSession == null || now() >= currentSession.record.accessExpiresAt()) {
            throw new SessionException(INVALID_CREDENTIAL);
        }
        if (!store.revoke(currentSession.record)) throw new SessionException(INVALID_CREDENTIAL);
    }

    private SessionRecord authenticate(String token, SessionCredentials.Use use,
                                       SessionDomain domain, String application) {
        SessionCredentials.Ticket ticket = credentials.decode(token, use, domain, application);
        SessionRecord record = store.load(domain, application, ticket.sid());
        long now = now();
        if (record == null || !record.sid().equals(ticket.sid()) || record.identity().domain() != domain
                || !record.identity().application().equals(application) || record.status() != SessionRecord.Status.ACTIVE
                || now < record.createdAt() || now >= record.absoluteExpiresAt()
                || (use == ACCESS && now >= record.accessExpiresAt())
                || !SessionCredentials.matches(token, use == ACCESS ? record.accessDigest() : record.refreshDigest())
                || (ticket.identity() != null && (!ticket.identity().equals(record.identity())
                    || ticket.generation() != record.generation()
                    || ticket.expiresAt() != (use == ACCESS ? record.accessExpiresAt() : record.absoluteExpiresAt())))) {
            throw new SessionException(INVALID_CREDENTIAL);
        }
        authorize(record.identity());
        return record;
    }

    private void authorize(SessionIdentity identity) {
        if (authorization == null) throw new SessionException(AUTHORIZATION_REQUIRED);
        try {
            authorization.check(identity);
        } catch (SessionException known) {
            throw new SessionException(known.getCode());
        } catch (RuntimeException ignored) {
            throw new SessionException(DENIED);
        }
    }

    private SessionTokens issue(String sid, SessionIdentity identity, long generation, long now,
                                 long accessExpires, long absoluteExpires) {
        try {
            return new SessionTokens(sid, credentials.issue(sid, identity, ACCESS, generation, now, accessExpires),
                    credentials.issue(sid, identity, REFRESH, generation, now, absoluteExpires),
                    accessExpires, absoluteExpires);
        } catch (RuntimeException ignored) {
            throw new SessionException(INVALID_CONTEXT);
        }
    }

    // JWT NumericDate is seconds. Keep record and JWT expiries exactly aligned.
    private long now() {
        return Math.floorDiv(clock.millis(), 1000) * 1000;
    }

    public static final class VerifiedSession {
        private final SessionRecord record;

        private VerifiedSession(SessionRecord record) { this.record = record; }
        public String sid() { return record.sid(); }
        public SessionIdentity identity() { return record.identity(); }
        public long generation() { return record.generation(); }

        @Override
        public String toString() { return "VerifiedSession[REDACTED]"; }
    }

    interface Store {
        boolean create(SessionRecord record);
        SessionRecord load(SessionDomain domain, String application, String sid);
        boolean rotate(SessionRecord expected, SessionRecord replacement);
        boolean revoke(SessionRecord authenticated);
    }
}
