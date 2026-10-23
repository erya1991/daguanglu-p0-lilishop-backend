package cn.lili.common.security.session.http;

import cn.lili.common.security.AuthUser;
import cn.lili.common.security.enums.SecurityEnum;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.session.*;
import cn.lili.common.utils.ResponseUtil;
import com.google.gson.JsonParser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static cn.lili.common.security.session.SessionException.Code.*;

/** Registered only in the explicit security chains, never as a servlet-wide permitAll filter. */
public final class SessionHttpFilter extends OncePerRequestFilter {
    private final SessionService sessions;
    private final CurrentSessionAuthorization authorization;
    private final SessionApplications applications;
    private final SessionDomain domain;

    public SessionHttpFilter(SessionService sessions, CurrentSessionAuthorization authorization,
                             SessionApplications applications, SessionDomain domain) {
        this.sessions = sessions;
        this.authorization = authorization;
        this.applications = applications;
        this.domain = domain;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        String method = request.getMethod();
        String token = request.getHeader(SecurityEnum.HEADER_TOKEN.getValue());
        try {
            // Exact POST endpoints authenticate their body Refresh, not a possibly expired Access header.
            if (path.equals(SessionRoutes.refresh(domain))) {
                require(method.equals("POST"));
                byte[] raw = request.getInputStream().readNBytes(9001);
                require(raw.length <= 9000);
                var body = JsonParser.parseString(new String(raw, StandardCharsets.UTF_8));
                require(body.isJsonObject() && body.getAsJsonObject().size() == 1
                        && body.getAsJsonObject().has("refreshToken")
                        && body.getAsJsonObject().get("refreshToken").isJsonPrimitive()
                        && body.getAsJsonObject().getAsJsonPrimitive("refreshToken").isString());
                var next = sessions.refresh(body.getAsJsonObject().get("refreshToken").getAsString(),
                        domain, applications.application(domain));
                reply(response, next);
                return;
            }
            if (SessionRoutes.isLegacyRefresh(method, path, domain)
                    && SessionProtocol.isStage0(path.substring(SessionRoutes.refresh(domain).length() + 1))) {
                throw new SessionException(INVALID_CREDENTIAL);
            }
            if (!SessionProtocol.isStage0(token)) {
                require(domain != SessionDomain.APPLICANT); // No legacy Applicant fallback.
                chain.doFilter(request, response);
                return;
            }
            require(SessionRoutes.allowed(domain, method, path));
            // These endpoints never select a different identity from request fields.
            for (String selector : List.of("memberId", "userId", "applicantId", "storeId", "clerkId", "role", "isSuper", "appId")) {
                require(request.getParameter(selector) == null);
            }
            var verified = sessions.verifyAccess(token, domain, applications.application(domain));
            AuthUser user = authorization.current(verified.identity());
            if (domain == SessionDomain.APPLICANT) require(user == null);
            else require(user != null && verified.identity().subject().equals(user.getId())
                    && user.getRole() == switch (domain) {
                        case RESIDENT -> UserEnums.MEMBER;
                        case STORE -> UserEnums.STORE;
                        case MANAGER -> UserEnums.MANAGER;
                        default -> throw new SessionException(DENIED);
                    });
            authorization.checkRoute(user, method, path, true);
            var principal = new Principal(verified, user);
            var authentication = new UsernamePasswordAuthenticationToken(verified.identity().subject(), null,
                    List.of(new SimpleGrantedAuthority("ROLE_STAGE0_" + domain.name())));
            authentication.setDetails(principal);
            SecurityContextHolder.getContext().setAuthentication(authentication);
            if (method.equals("POST") && path.equals(SessionRoutes.logout(domain))) {
                sessions.revoke(verified);
                SecurityContextHolder.clearContext();
                reply(response, null);
                return;
            }
            if (domain == SessionDomain.APPLICANT) {
                reply(response, Map.of("memberId", verified.identity().subject(), "identity", "APPLICANT",
                        "access", "RESTRICTED", "nextAction", "SHOW_APPLICATION_ENTRY"));
                return;
            }
            chain.doFilter(request, response);
        } catch (SessionException e) {
            reject(response, e.getCode() == STORE_UNAVAILABLE || e.getCode() == AUTHORIZATION_REQUIRED ? 503 : 403);
        } catch (RuntimeException e) {
            reject(response, 403); // No credential, input body, parser cause or URI in diagnostics.
        }
    }

    public static boolean verifiedRequest() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated() && authentication.getDetails() instanceof Principal;
    }

    private static void require(boolean condition) { if (!condition) throw new SessionException(INVALID_CREDENTIAL); }
    private static void reply(HttpServletResponse response, Object result) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        ResponseUtil.output(response, 200, ResponseUtil.resultMap(true, 200, "SUCCESS", result));
    }
    private static void reject(HttpServletResponse response, int status) {
        SecurityContextHolder.clearContext();
        response.setHeader("Cache-Control", "no-store");
        ResponseUtil.output(response, status, ResponseUtil.resultMap(false, status, "SESSION_REJECTED"));
    }

    public record Principal(SessionService.VerifiedSession session, AuthUser user) {
        @Override public String toString() { return "SessionPrincipal[REDACTED]"; }
    }
}
