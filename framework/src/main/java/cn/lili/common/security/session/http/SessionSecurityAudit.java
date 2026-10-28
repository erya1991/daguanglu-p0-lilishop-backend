package cn.lili.common.security.session.http;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.common.security.session.SessionException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

/** Fixed-field refusal events at the HTTP boundary; never accept request values or exception text. */
final class SessionSecurityAudit {
    private static final Logger LOG = LoggerFactory.getLogger(SessionSecurityAudit.class);

    enum Action { LOGIN, ACCESS, REFRESH, LOGOUT }
    enum Reason {
        INVALID_CONTEXT, INVALID_CREDENTIAL, AUTHORIZATION_REQUIRED, DENIED, STORE_UNAVAILABLE,
        UNVERIFIED_REQUEST, AUTHENTICATION_FAILED, UNEXPECTED_FAILURE;

        static Reason from(SessionException.Code code) { return valueOf(code.name()); }
    }

    private SessionSecurityAudit() { }

    static Action action(SessionDomain domain, String method, String path) {
        if ("POST".equals(method) && ((domain == SessionDomain.RESIDENT && SessionRoutes.RESIDENT_LOGIN.equals(path))
                || (domain == SessionDomain.STORE && SessionRoutes.MERCHANT_LOGIN.equals(path))
                || (domain == SessionDomain.MANAGER && SessionRoutes.MANAGER_LOGIN.equals(path)))) return Action.LOGIN;
        if (SessionRoutes.refresh(domain).equals(path) || SessionRoutes.isLegacyRefresh(method, path, domain)) return Action.REFRESH;
        if (SessionRoutes.logout(domain).equals(path)) return Action.LOGOUT;
        return Action.ACCESS;
    }

    static void rejected(HttpServletResponse response, SessionDomain domain, Action action, Reason reason, int status) {
        String eventId = UUID.randomUUID().toString().replace("-", "");
        response.setHeader("X-Session-Audit-Id", eventId);
        // Domain/action/reason are server-owned enums. No raw URI (legacy Refresh URLs contain credentials),
        // subject, sid, credential digest, headers, parameters, body, cause or stack is retained.
        LOG.warn("STAGE0_SESSION_REJECT eventId={} time={} domain={} action={} reason={} status={}",
                eventId, Instant.now(), domain, action, reason, status);
    }
}
