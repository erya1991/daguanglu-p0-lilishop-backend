package cn.lili.security;

import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.common.properties.IgnoredUrlsProperties;
import cn.lili.common.properties.JWTTokenProperties;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.enums.SecurityEnum;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.token.SecretKeyUtil;
import com.google.gson.Gson;
import io.jsonwebtoken.Jwts;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class BuyerJwtSecurityTest {
    private final JWTTokenProperties properties = properties();
    private final SecretKeyUtil keys = new SecretKeyUtil(properties);
    private final Set<Object> sessions = new HashSet<>();
    private final AtomicInteger reads = new AtomicInteger();
    private final Cache<Object> cache = mock(Cache.class, call -> {
        if (call.getMethod().getName().equals("hasKey")) {
            reads.incrementAndGet();
            return sessions.contains(call.getArgument(0));
        }
        return null;
    });
    private final IgnoredUrlsProperties ignored = new IgnoredUrlsProperties();
    private final AuthenticationManager manager = mock(AuthenticationManager.class);
    private final BuyerAuthenticationFilter filter;
    BuyerJwtSecurityTest() {
        ignored.setUrls(new ArrayList<>(List.of("/buyer/public/**")));
        filter = new BuyerAuthenticationFilter(manager, cache, ignored, keys);
    }
    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }
    private static JWTTokenProperties properties() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        var p = new JWTTokenProperties();
        p.setSecretKey(Base64.getEncoder().encodeToString(raw));
        return p;
    }
    private String token(UserEnums role, SecretKeyUtil signer, long delta) {
        var u = AuthUser.builder().id("synthetic-actor").username("synthetic-user").role(role).build();
        return Jwts.builder().subject(u.getUsername()).claim(SecurityEnum.USER_CONTEXT.getValue(), new Gson().toJson(u))
                .expiration(new Date(System.currentTimeMillis() + delta))
                .signWith(signer.generalKey(), Jwts.SIG.HS256).compact();
    }
    private record Result(int chainCalls, int status, String body, Authentication authentication) {}
    private Result run(String token, String uri) throws Exception {
        SecurityContextHolder.clearContext();
        var req = new MockHttpServletRequest("GET", uri);
        if (token != null) req.addHeader(SecurityEnum.HEADER_TOKEN.getValue(), token);
        var res = new MockHttpServletResponse();
        var called = new AtomicInteger();
        filter.doFilterInternal(req, res, (r, s) -> called.incrementAndGet());
        return new Result(called.get(), res.getStatus(), res.getContentAsString(),
                SecurityContextHolder.getContext().getAuthentication());
    }
    @Test
    void memberRequiresConfiguredKeyAndServerSession() throws Exception {
        String t = token(UserEnums.MEMBER, keys, 60000);
        assertEquals(0, run(t, "/buyer/private/item").chainCalls());
        sessions.add(CachePrefix.ACCESS_TOKEN.getPrefix(UserEnums.MEMBER, "synthetic-actor") + t);
        var result = run(t, "/buyer/private/item");
        assertEquals(1, result.chainCalls());
        assertEquals(UserEnums.MEMBER, ((AuthUser) result.authentication().getDetails()).getRole());
    }
    @Test
    void managerAndStoreSessionsCannotAuthorizeBuyer() throws Exception {
        for (var role : List.of(UserEnums.MANAGER, UserEnums.STORE)) {
            String t = token(role, keys, 60000);
            sessions.add(CachePrefix.ACCESS_TOKEN.getPrefix(role, "synthetic-actor") + t);
            var result = run(t, "/buyer/private/item");
            assertEquals(0, result.chainCalls());
            assertEquals(403, result.status());
            assertNull(result.authentication());
        }
    }
    @Test
    void otherKeyExpiredAndTamperedTokensRejectBeforeCache() throws Exception {
        String valid = token(UserEnums.MEMBER, keys, 60000);
        String[] parts = valid.split("\\.");
        parts[2] = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        for (String t : List.of(token(UserEnums.MEMBER, new SecretKeyUtil(properties()), 60000),
                token(UserEnums.MEMBER, keys, -60000), String.join(".", parts))) {
            sessions.add(CachePrefix.ACCESS_TOKEN.getPrefix(UserEnums.MEMBER, "synthetic-actor") + t);
            assertEquals(0, run(t, "/buyer/private/item").chainCalls());
            assertNull(SecurityContextHolder.getContext().getAuthentication());
        }
        assertEquals(0, reads.get());
    }
    @Test
    void existingPublicAndMissingHeaderBehaviorDoesNotCreateAuthentication() throws Exception {
        assertEquals(1, run("not-a-token", "/buyer/public/item").chainCalls());
        var missing = run(null, "/buyer/private/item");
        assertEquals(1, missing.chainCalls()); // Authentication still required by the outer security chain.
        assertNull(missing.authentication());
    }
    @Test
    void malformedTokenLogsNoTokenKeyOrParserThrowable() throws Exception {
        String t = token(UserEnums.MEMBER, keys, 60000) + ".extra";
        var logger = (Logger) LoggerFactory.getLogger(BuyerAuthenticationFilter.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var result = run(t, "/buyer/private/item");
            assertEquals(0, result.chainCalls());
            assertFalse(result.body().contains(t), "response must not expose token");
            assertFalse(appender.list.isEmpty());
            for (var e : appender.list) {
                assertFalse(e.getFormattedMessage().contains(t), "log must not expose token");
                assertFalse(e.getFormattedMessage().contains(properties.getSecretKey()), "log must not expose key");
                assertNull(e.getThrowableProxy());
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }
    @Test
    void keyComponentCannotBeMissing() {
        assertThrows(NullPointerException.class, () -> new BuyerAuthenticationFilter(manager, cache, ignored, null));
    }
}
