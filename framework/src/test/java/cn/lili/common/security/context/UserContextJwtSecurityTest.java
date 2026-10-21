package cn.lili.common.security.context;

import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.common.properties.JWTTokenProperties;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.enums.SecurityEnum;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.token.SecretKeyUtil;
import com.google.gson.Gson;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.context.support.GenericWebApplicationContext;
import java.security.SecureRandom;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class UserContextJwtSecurityTest {
    @AfterEach
    void clearRequest() {
        RequestContextHolder.resetRequestAttributes();
    }
    private static GenericWebApplicationContext context() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        var p = new JWTTokenProperties();
        p.setSecretKey(Base64.getEncoder().encodeToString(raw));
        var servlet = new MockServletContext();
        var c = new GenericWebApplicationContext(servlet);
        org.springframework.context.annotation.AnnotationConfigUtils.registerAnnotationConfigProcessors(c);
        c.registerBean(JWTTokenProperties.class, () -> p);
        c.registerBean(SecretKeyUtil.class);
        c.refresh();
        servlet.setAttribute(WebApplicationContext.ROOT_WEB_APPLICATION_CONTEXT_ATTRIBUTE, c);
        return c;
    }
    private static String token(SecretKeyUtil keys, UserEnums role, long delta) {
        var u = AuthUser.builder().id("synthetic-actor").username("synthetic-user")
                .role(role).storeId("synthetic-store").build();
        return Jwts.builder().subject(u.getUsername())
                .claim(SecurityEnum.USER_CONTEXT.getValue(), new Gson().toJson(u))
                .expiration(new Date(System.currentTimeMillis() + delta))
                .signWith(keys.generalKey(), Jwts.SIG.HS256).compact();
    }
    private static void request(GenericWebApplicationContext c, String token) {
        var r = new MockHttpServletRequest(c.getServletContext());
        r.addHeader(SecurityEnum.HEADER_TOKEN.getValue(), token);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(r));
    }
    @Test
    void staticHttpEntryUsesOnlyTheCurrentServletContext() {
        try (var a = context(); var b = context()) {
            String ta = token(a.getBean(SecretKeyUtil.class), UserEnums.MANAGER, 60000);
            String tb = token(b.getBean(SecretKeyUtil.class), UserEnums.STORE, 60000);
            request(a, ta);
            assertEquals(UserEnums.MANAGER, UserContext.getCurrentUser().getRole());
            assertNull(UserContext.getAuthUser(tb));
            request(b, tb);
            assertEquals("synthetic-store", UserContext.getCurrentUser().getStoreId());
            assertNull(UserContext.getAuthUser(ta));
        }
    }
    @Test
    void missingOrClosedContextHasNoGlobalFallback() {
        try (var c = context()) {
            String t = token(c.getBean(SecretKeyUtil.class), UserEnums.MEMBER, 60000);
            assertNull(UserContext.getAuthUser(t));
            request(c, t);
            assertNotNull(UserContext.getAuthUser(t));
            c.close();
            assertNull(UserContext.getAuthUser(t));
        }
    }
    @Test
    void explicitVerifierSupportsNonHttpCallersAndChecksServerSession() {
        try (var c = context()) {
            var keys = c.getBean(SecretKeyUtil.class);
            String t = token(keys, UserEnums.STORE, 60000);
            Set<Object> sessions = new HashSet<>();
            Cache<?> cache = mock(Cache.class, call -> call.getMethod().getName().equals("hasKey")
                    && sessions.contains(call.getArgument(0)));
            assertNull(UserContext.getAuthUser(cache, t, keys));
            sessions.add(CachePrefix.ACCESS_TOKEN.getPrefix(UserEnums.MEMBER, "synthetic-actor") + t);
            assertNull(UserContext.getAuthUser(cache, t, keys));
            sessions.add(CachePrefix.ACCESS_TOKEN.getPrefix(UserEnums.STORE, "synthetic-actor") + t);
            assertNotNull(UserContext.getAuthUser(cache, t, keys));
            sessions.clear();
            assertNull(UserContext.getAuthUser(cache, t, keys));
        }
    }
    @Test
    void httpSessionCheckUsesTheConfiguredContextKey() {
        try (var c = context()) {
            var keys = c.getBean(SecretKeyUtil.class);
            String t = token(keys, UserEnums.MANAGER, 60000);
            request(c, t);
            String allowed = CachePrefix.ACCESS_TOKEN.getPrefix(UserEnums.MANAGER, "synthetic-actor") + t;
            Cache<?> cache = mock(Cache.class, call -> call.getMethod().getName().equals("hasKey")
                    && allowed.equals(call.getArgument(0)));
            assertNotNull(UserContext.getAuthUser(cache, t));
        }
    }
    @Test
    void wrongKeyExpiredTamperedAndClosedVerifierAreRejected() {
        try (var a = context(); var b = context()) {
            var keys = a.getBean(SecretKeyUtil.class);
            String good = token(keys, UserEnums.MEMBER, 60000);
            assertNull(UserContext.getAuthUser(token(b.getBean(SecretKeyUtil.class), UserEnums.MEMBER, 60000), keys));
            assertNull(UserContext.getAuthUser(token(keys, UserEnums.MEMBER, -60000), keys));
            String[] parts = good.split("\\.");
            parts[2] = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
            assertNull(UserContext.getAuthUser(String.join(".", parts), keys));
            a.close();
            assertNull(UserContext.getAuthUser(good, keys));
        }
    }
}
