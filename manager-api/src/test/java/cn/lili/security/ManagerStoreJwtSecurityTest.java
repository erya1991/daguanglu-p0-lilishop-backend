package cn.lili.security;

import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.common.properties.IgnoredUrlsProperties;
import cn.lili.common.properties.JWTTokenProperties;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.enums.SecurityEnum;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.token.SecretKeyUtil;
import cn.lili.modules.member.service.ClerkService;
import cn.lili.modules.member.service.StoreMenuRoleService;
import cn.lili.modules.member.token.StoreTokenGenerate;
import cn.lili.modules.permission.service.MenuService;
import cn.lili.modules.system.token.ManagerTokenGenerate;
import com.google.gson.Gson;
import io.jsonwebtoken.Jwts;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import javax.naming.NoPermissionException;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class ManagerStoreJwtSecurityTest {
    @AfterEach
    void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    private static JWTTokenProperties properties() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        var p = new JWTTokenProperties();
        p.setSecretKey(Base64.getEncoder().encodeToString(raw));
        return p;
    }
    private record Result(int chainCalls, int status, String body, Authentication authentication) {}
    private static class Fixture {
        final UserEnums domain;
        final JWTTokenProperties props = properties();
        final SecretKeyUtil keys = new SecretKeyUtil(props);
        final Map<Object, Object> entries = new HashMap<>();
        final AtomicInteger sessionReads = new AtomicInteger();
        final Cache<Object> cache = mock(Cache.class, call -> {
            if (call.getMethod().getName().equals("hasKey")) {
                sessionReads.incrementAndGet();
                return entries.containsKey(call.getArgument(0));
            }
            if (call.getMethod().getName().equals("get")) return entries.get(call.getArgument(0));
            return null;
        });
        final AuthenticationManager manager = mock(AuthenticationManager.class);
        final IgnoredUrlsProperties ignored = new IgnoredUrlsProperties();
        final ManagerAuthenticationFilter mf;
        final StoreAuthenticationFilter sf;
        Fixture(UserEnums domain) {
            this.domain = domain;
            ignored.setUrls(new ArrayList<>(List.of("/manager/public/**")));
            mf = new ManagerAuthenticationFilter(manager, mock(MenuService.class), mock(ManagerTokenGenerate.class),
                    cache, ignored, keys);
            sf = new StoreAuthenticationFilter(manager, mock(StoreTokenGenerate.class),
                    mock(StoreMenuRoleService.class), mock(ClerkService.class), cache, keys);
            entries.put(CachePrefix.PERMISSION_LIST.getPrefix(domain) + "synthetic-actor",
                    Map.of("QUERY", List.of("/" + path() + "/allowed/**"), "SUPER", List.of()));
        }
        String path() { return domain == UserEnums.MANAGER ? "manager" : "store"; }
        String token(UserEnums role, SecretKeyUtil signer, long delta, boolean superUser) {
            var u = AuthUser.builder().id("synthetic-actor").username("synthetic-user").role(role)
                    .storeId("synthetic-store").clerkId("synthetic-clerk").isSuper(superUser).build();
            return Jwts.builder().subject(u.getUsername())
                    .claim(SecurityEnum.USER_CONTEXT.getValue(), new Gson().toJson(u))
                    .expiration(new Date(System.currentTimeMillis() + delta))
                    .signWith(signer.generalKey(), Jwts.SIG.HS256).compact();
        }
        void allow(UserEnums role, String token) {
            entries.put(CachePrefix.ACCESS_TOKEN.getPrefix(role, "synthetic-actor") + token, 1);
        }
        Result run(String token, String method, String route) throws Exception {
            SecurityContextHolder.clearContext();
            var req = new MockHttpServletRequest(method, route);
            req.setParameter("storeId", "request-cannot-rewrite-token-store");
            if (token != null) req.addHeader(SecurityEnum.HEADER_TOKEN.getValue(), token);
            var response = new MockHttpServletResponse();
            var calls = new AtomicInteger();
            try {
                if (domain == UserEnums.MANAGER) mf.doFilterInternal(req, response, (r, s) -> calls.incrementAndGet());
                else sf.doFilterInternal(req, response, (r, s) -> calls.incrementAndGet());
            } catch (Exception e) {
                if (!(e instanceof NoPermissionException)) throw e;
            }
            return new Result(calls.get(), response.getStatus(), response.getContentAsString(),
                    SecurityContextHolder.getContext().getAuthentication());
        }
    }

    @ParameterizedTest
    @EnumSource(value = UserEnums.class, names = {"MANAGER", "STORE"})
    void configuredKeySessionAndExistingQueryPermissionAllow(UserEnums domain) throws Exception {
        var f = new Fixture(domain);
        String t = f.token(domain, f.keys, 60000, false);
        f.allow(domain, t);
        var result = f.run(t, "GET", "/" + f.path() + "/allowed/item");
        assertEquals(1, result.chainCalls());
        assertNotNull(result.authentication());
        var u = (AuthUser) result.authentication().getDetails();
        assertEquals(domain, u.getRole());
        assertEquals("synthetic-store", u.getStoreId());
        assertTrue(result.authentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + domain.name())));
    }

    @ParameterizedTest
    @EnumSource(value = UserEnums.class, names = {"MANAGER", "STORE"})
    void revokedSessionDenies(UserEnums domain) throws Exception {
        var f = new Fixture(domain);
        var result = f.run(f.token(domain, f.keys, 60000, false), "GET", "/" + f.path() + "/allowed/item");
        assertEquals(0, result.chainCalls());
        assertEquals(403, result.status());
        assertNull(result.authentication());
    }

    @ParameterizedTest
    @EnumSource(value = UserEnums.class, names = {"MANAGER", "STORE"})
    void otherIdentityDomainSessionCannotAuthorize(UserEnums domain) throws Exception {
        var f = new Fixture(domain);
        for (UserEnums foreign : new UserEnums[]{UserEnums.MEMBER,
                domain == UserEnums.MANAGER ? UserEnums.STORE : UserEnums.MANAGER}) {
            String t = f.token(foreign, f.keys, 60000, false);
            f.allow(foreign, t);
            var result = f.run(t, "GET", "/" + f.path() + "/allowed/item");
            assertEquals(0, result.chainCalls());
            assertNull(result.authentication());
        }
    }

    @ParameterizedTest
    @EnumSource(value = UserEnums.class, names = {"MANAGER", "STORE"})
    void wrongKeyExpiredAndTamperedTokensDenyBeforeSessionLookup(UserEnums domain) throws Exception {
        var f = new Fixture(domain);
        String foreign = f.token(domain, new SecretKeyUtil(properties()), 60000, false);
        String expired = f.token(domain, f.keys, -60000, false);
        String valid = f.token(domain, f.keys, 60000, false);
        String[] parts = valid.split("\\.");
        parts[2] = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        for (String t : List.of(foreign, expired, String.join(".", parts))) {
            f.allow(domain, t);
            var result = f.run(t, "GET", "/" + f.path() + "/allowed/item");
            assertEquals(0, result.chainCalls());
            assertNull(result.authentication());
        }
        assertEquals(0, f.sessionReads.get());
    }

    @ParameterizedTest
    @EnumSource(value = UserEnums.class, names = {"MANAGER", "STORE"})
    void queryPermissionDoesNotGrantWritesOrOtherRoutes(UserEnums domain) throws Exception {
        var f = new Fixture(domain);
        String t = f.token(domain, f.keys, 60000, false);
        f.allow(domain, t);
        assertEquals(0, f.run(t, "POST", "/" + f.path() + "/allowed/item").chainCalls());
        assertEquals(0, f.run(t, "GET", "/" + f.path() + "/denied/item").chainCalls());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @ParameterizedTest
    @EnumSource(value = UserEnums.class, names = {"MANAGER", "STORE"})
    void superUserAndMissingHeaderKeepExistingFilterSemantics(UserEnums domain) throws Exception {
        var f = new Fixture(domain);
        String t = f.token(domain, f.keys, 60000, true);
        f.allow(domain, t);
        assertEquals(1, f.run(t, "POST", "/" + f.path() + "/denied/item").chainCalls());
        var missing = f.run(null, "GET", "/" + f.path() + "/allowed/item");
        assertEquals(1, missing.chainCalls()); // The outer SecurityFilterChain still requires authentication.
        assertNull(missing.authentication());
    }

    @ParameterizedTest
    @EnumSource(value = UserEnums.class, names = {"MANAGER", "STORE"})
    void malformedTokenLogsNeitherFullTokenSecretNorThrowable(UserEnums domain) throws Exception {
        var f = new Fixture(domain);
        String t = f.token(domain, f.keys, 60000, false) + ".extra";
        Logger logger = (Logger) LoggerFactory.getLogger(domain == UserEnums.MANAGER
                ? ManagerAuthenticationFilter.class : StoreAuthenticationFilter.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        try {
            var result = f.run(t, "GET", "/" + f.path() + "/allowed/item");
            assertEquals(0, result.chainCalls());
            assertFalse(result.body().contains(t), "response must not expose token");
            assertFalse(appender.list.isEmpty());
            for (var e : appender.list) {
                assertFalse(e.getFormattedMessage().contains(t), "log must not expose token");
                assertFalse(e.getFormattedMessage().contains(f.props.getSecretKey()), "log must not expose key");
                assertNull(e.getThrowableProxy(), "JWT parser exception must not be logged");
            }
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void filtersRequireTheKeyComponent() {
        var f = new Fixture(UserEnums.MANAGER);
        assertThrows(NullPointerException.class, () -> new ManagerAuthenticationFilter(f.manager,
                mock(MenuService.class), mock(ManagerTokenGenerate.class), f.cache, f.ignored, null));
        assertThrows(NullPointerException.class, () -> new StoreAuthenticationFilter(f.manager,
                mock(StoreTokenGenerate.class), mock(StoreMenuRoleService.class), mock(ClerkService.class), f.cache, null));
    }
}
