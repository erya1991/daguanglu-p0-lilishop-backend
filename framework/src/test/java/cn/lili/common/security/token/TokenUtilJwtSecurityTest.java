package cn.lili.common.security.token;

import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.properties.JWTTokenProperties;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.enums.SecurityEnum;
import cn.lili.common.security.enums.UserEnums;
import com.google.gson.Gson;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class TokenUtilJwtSecurityTest {
    private final Map<Object, Object> entries = new HashMap<>();
    private final Map<Object, Long> ttl = new HashMap<>();
    private final JWTTokenProperties properties = properties();
    private final SecretKeyUtil keys = new SecretKeyUtil(properties);
    private final Cache<Object> cache = mock(Cache.class, call -> {
        String name = call.getMethod().getName();
        if (name.equals("hasKey")) return entries.containsKey(call.getArgument(0));
        if (name.equals("put")) {
            entries.put(call.getArgument(0), call.getArgument(1));
            if (call.getArguments().length == 4) {
                assertEquals(TimeUnit.MINUTES, call.getArgument(3));
                ttl.put(call.getArgument(0), call.getArgument(2));
            }
            return null;
        }
        if (name.equals("remove")) return entries.remove(call.getArgument(0)) != null;
        return null;
    });
    private static JWTTokenProperties properties() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        var p = new JWTTokenProperties();
        p.setSecretKey(Base64.getEncoder().encodeToString(raw));
        return p;
    }
    private TokenUtil utility() {
        var util = new TokenUtil();
        ReflectionTestUtils.setField(util, "tokenProperties", properties);
        ReflectionTestUtils.setField(util, "secretKeyUtil", keys);
        ReflectionTestUtils.setField(util, "cache", cache);
        return util;
    }
    private AuthUser user(UserEnums role) {
        return AuthUser.builder().id("synthetic-actor").username("synthetic-user")
                .role(role).storeId("synthetic-store").clerkId("synthetic-clerk").build();
    }
    private String oldRefresh(AuthUser user, Date expiry, SecretKeyUtil signer) {
        return Jwts.builder().claim(SecurityEnum.USER_CONTEXT.getValue(), new Gson().toJson(user))
                .subject(user.getUsername()).expiration(expiry)
                .signWith(signer.generalKey(), Jwts.SIG.HS256).compact();
    }
    @ParameterizedTest
    @EnumSource(value = UserEnums.class, names = {"MANAGER", "STORE", "MEMBER"})
    void issuesSameClaimShapeAndSeparatedServerSessions(UserEnums role) {
        var user = user(role);
        var t = utility().createToken(user);
        Claims access = keys.parseClaims(t.getAccessToken());
        Claims refresh = keys.parseClaims(t.getRefreshToken());
        var decoded = new Gson().fromJson(access.get(SecurityEnum.USER_CONTEXT.getValue()).toString(), AuthUser.class);
        assertEquals(role, decoded.getRole());
        assertEquals(user.getId(), decoded.getId());
        assertEquals(user.getStoreId(), decoded.getStoreId());
        assertEquals(user.getUsername(), access.getSubject());
        assertEquals(Set.of(SecurityEnum.USER_CONTEXT.getValue(), "sub", "exp"), access.keySet());
        assertEquals("HS256", Jwts.parser().verifyWith(keys.generalKey()).build()
                .parseSignedClaims(t.getAccessToken()).getHeader().getAlgorithm());
        String ak = CachePrefix.ACCESS_TOKEN.getPrefix(role, user.getId()) + t.getAccessToken();
        String rk = CachePrefix.REFRESH_TOKEN.getPrefix(role, user.getId()) + t.getRefreshToken();
        assertTrue(entries.containsKey(ak), "access session must be registered");
        assertTrue(entries.containsKey(rk), "refresh session must be registered");
        assertEquals(60L, ttl.get(ak));
        assertEquals(120L, ttl.get(rk));
        long gap = (refresh.getExpiration().getTime() - access.getExpiration().getTime()) / 1000;
        assertTrue(gap >= 3600L && gap <= 3605L, "refresh TTL must preserve the configured policy");
    }
    @ParameterizedTest
    @EnumSource(value = UserEnums.class, names = {"MANAGER", "STORE", "MEMBER"})
    void refreshRequiresOldServerSessionAndPreservesRole(UserEnums role) {
        var user = user(role);
        // Valid historical token with earlier expiry avoids identical-second reissue.
        String old = oldRefresh(user, new Date(System.currentTimeMillis() + 90 * 60000), keys);
        String oldKey = CachePrefix.REFRESH_TOKEN.getPrefix(role, user.getId()) + old;
        entries.put(oldKey, 1);
        var t = utility().refreshToken(old);
        assertFalse(entries.containsKey(oldKey), "old refresh must be removed");
        assertTrue(entries.containsKey(CachePrefix.ACCESS_TOKEN.getPrefix(role, user.getId()) + t.getAccessToken()));
        assertTrue(entries.containsKey(CachePrefix.REFRESH_TOKEN.getPrefix(role, user.getId()) + t.getRefreshToken()));
        var decoded = new Gson().fromJson(keys.parseClaims(t.getAccessToken())
                .get(SecurityEnum.USER_CONTEXT.getValue()).toString(), AuthUser.class);
        assertEquals(role, decoded.getRole());
        assertEquals(user.getStoreId(), decoded.getStoreId());
        assertFalse(decoded.getIsSuper());
    }
    @Test
    void longTermRefreshKeepsExistingFifteenDayPolicy() {
        var u = user(UserEnums.STORE);
        u.setLongTerm(true);
        var t = utility().createToken(u);
        String rk = CachePrefix.REFRESH_TOKEN.getPrefix(UserEnums.STORE, u.getId()) + t.getRefreshToken();
        assertEquals(21600L, ttl.get(rk));
    }
    @Test
    void missingOrWrongDomainRefreshSessionIsRejected() {
        var u = user(UserEnums.MANAGER);
        String old = oldRefresh(u, new Date(System.currentTimeMillis() + 60000), keys);
        entries.put(CachePrefix.REFRESH_TOKEN.getPrefix(UserEnums.STORE, u.getId()) + old, 1);
        var e = assertThrows(ServiceException.class, () -> utility().refreshToken(old));
        assertEquals(ResultCode.USER_AUTH_EXPIRED, e.getResultCode());
        assertEquals(1, entries.size());
    }
    @Test
    void otherKeyExpiredAndTamperedRefreshAreRejectedBeforeCacheWrites() {
        var u = user(UserEnums.MEMBER);
        String wrong = oldRefresh(u, new Date(System.currentTimeMillis() + 60000), new SecretKeyUtil(properties()));
        String expired = oldRefresh(u, new Date(System.currentTimeMillis() - 60000), keys);
        String valid = oldRefresh(u, new Date(System.currentTimeMillis() + 60000), keys);
        String[] parts = valid.split("\\.");
        parts[2] = (parts[2].charAt(0) == 'A' ? "B" : "A") + parts[2].substring(1);
        for (String token : List.of(wrong, expired, String.join(".", parts))) {
            var e = assertThrows(ServiceException.class, () -> utility().refreshToken(token));
            assertEquals(ResultCode.USER_AUTH_EXPIRED, e.getResultCode());
        }
        assertTrue(entries.isEmpty());
    }
    @Test
    void accessSessionDoesNotAuthorizeRefresh() {
        var u = user(UserEnums.MANAGER);
        var t = utility().createToken(u);
        var e = assertThrows(ServiceException.class, () -> utility().refreshToken(t.getAccessToken()));
        assertEquals(ResultCode.USER_AUTH_EXPIRED, e.getResultCode());
    }
}
