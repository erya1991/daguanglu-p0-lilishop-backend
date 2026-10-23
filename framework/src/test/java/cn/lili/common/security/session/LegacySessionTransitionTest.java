package cn.lili.common.security.session;

import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.properties.JWTTokenProperties;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.enums.SecurityEnum;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.session.http.CurrentSessionAuthorization;
import cn.lili.common.security.token.*;
import cn.lili.modules.member.token.MemberTokenGenerate;
import cn.lili.modules.member.token.StoreTokenGenerate;
import cn.lili.modules.system.token.ManagerTokenGenerate;
import com.google.gson.Gson;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("unchecked")
class LegacySessionTransitionTest {
    @Test void unrelatedLegacySeatRefreshKeepsItsExistingBehaviorWithoutStageZeroAuthorization() {
        var f = new Fixture(UserEnums.SEAT);
        when(f.providers.getIfUnique()).thenReturn(null);
        String old = f.oldRefresh();
        f.entries.put(CachePrefix.REFRESH_TOKEN.getPrefix(UserEnums.SEAT, "synthetic-actor") + old, 1);
        var pair = f.tokens.refreshToken(old);
        var user = new Gson().fromJson(f.keys.parseClaims(pair.getAccessToken()).get("userContext").toString(), AuthUser.class);
        assertEquals(UserEnums.SEAT, user.getRole());
        assertEquals("synthetic-actor", user.getId());
        verifyNoInteractions(f.current);
        int writesBefore = f.writes.get();
        assertThrows(ServiceException.class, () -> f.tokens.refreshToken("s0o1.synthetic-new-credential"));
        assertEquals(writesBefore, f.writes.get(), "new protocol must not downgrade through any legacy role");
    }

    @ParameterizedTest @EnumSource(value = UserEnums.class, names = {"MEMBER", "STORE", "MANAGER"})
    void validLegacyRefreshKeepsItsProtocolAndUsesCurrentAccount(UserEnums role) {
        var f = new Fixture(role);
        String old = f.oldRefresh();
        f.entries.put(CachePrefix.REFRESH_TOKEN.getPrefix(role, "synthetic-actor") + old, 1);
        var pair = f.tokens.refreshToken(old, role);
        var claims = f.keys.parseClaims(pair.getAccessToken());
        assertEquals(Set.of("sub", "exp", "userContext"), claims.keySet());
        var user = new Gson().fromJson(claims.get("userContext").toString(), AuthUser.class);
        assertFalse(user.getIsSuper(), "current DB permissions replace old JWT super flag");
        assertEquals(role, user.getRole());
        assertFalse(f.entries.containsKey(CachePrefix.REFRESH_TOKEN.getPrefix(role, "synthetic-actor") + old));
        verify(f.current).legacy(any(AuthUser.class));
    }

    @ParameterizedTest @EnumSource(value = UserEnums.class, names = {"MEMBER", "STORE", "MANAGER"})
    void oldRefreshCannotBeUsedAtAnotherDomainEntry(UserEnums role) {
        var f = new Fixture(role);
        String old = f.oldRefresh();
        f.entries.put(CachePrefix.REFRESH_TOKEN.getPrefix(role, "synthetic-actor") + old, 1);
        var other = role == UserEnums.MANAGER ? UserEnums.MEMBER : UserEnums.MANAGER;
        assertThrows(ServiceException.class, () -> f.tokens.refreshToken(old, other));
        assertEquals(0, f.writes.get());
    }

    @ParameterizedTest @EnumSource(value = UserEnums.class, names = {"MEMBER", "STORE", "MANAGER"})
    void newProtocolCannotDowngradeEvenWithAnAccidentalLegacyCacheEntry(UserEnums role) {
        var f = new Fixture(role);
        String newJwt = Jwts.builder().subject("synthetic-actor").claim("sv", 1).claim("sid", "synthetic-session")
                .claim("use", "REFRESH").expiration(new Date(System.currentTimeMillis() + 60000))
                .claim("userContext", new Gson().toJson(f.user)) // Deliberate mixed-protocol injection.
                .signWith(f.keys.generalKey(), Jwts.SIG.HS256).compact();
        for (String token : List.of(newJwt, "s0o1.synthetic-opaque-credential")) {
            f.entries.put(CachePrefix.REFRESH_TOKEN.getPrefix(role, "synthetic-actor") + token, 1);
            var error = assertThrows(ServiceException.class, () -> f.tokens.refreshToken(token, role));
            assertFalse(error.getMessage().contains(token), "error must not contain credential");
        }
        assertEquals(0, f.writes.get());
        verifyNoInteractions(f.current);
    }

    @ParameterizedTest @EnumSource(value = UserEnums.class, names = {"MEMBER", "STORE", "MANAGER"})
    void revokedOrMissingCurrentAuthorizationCannotRefreshLegacyCredentials(UserEnums role) {
        var f = new Fixture(role);
        String old = f.oldRefresh();
        f.entries.put(CachePrefix.REFRESH_TOKEN.getPrefix(role, "synthetic-actor") + old, 1);
        when(f.providers.getIfUnique()).thenReturn(null);
        assertThrows(ServiceException.class, () -> f.tokens.refreshToken(old, role));
        when(f.providers.getIfUnique()).thenReturn(f.current);
        when(f.current.legacy(any(AuthUser.class))).thenThrow(new IllegalStateException(old));
        var error = assertThrows(ServiceException.class, () -> f.tokens.refreshToken(old, role));
        assertFalse(error.getMessage().contains(old));
        assertNull(error.getCause());
        assertEquals(0, f.writes.get());
    }

    @ParameterizedTest @EnumSource(value = UserEnums.class, names = {"MEMBER", "STORE", "MANAGER"})
    void eachNativeTokenGeneratorPinsItsOwnRefreshDomain(UserEnums role) {
        var tokens = mock(TokenUtil.class);
        var generator = switch (role) {
            case MEMBER -> new MemberTokenGenerate();
            case STORE -> new StoreTokenGenerate();
            case MANAGER -> new ManagerTokenGenerate();
            default -> throw new AssertionError();
        };
        ReflectionTestUtils.setField(generator, "tokenUtil", tokens);
        generator.refreshToken("synthetic-legacy-refresh");
        verify(tokens).refreshToken("synthetic-legacy-refresh", role);
        verify(tokens, never()).refreshToken(anyString());
    }

    private static final class Fixture {
        final Map<Object, Object> entries = new HashMap<>();
        final AtomicInteger writes = new AtomicInteger();
        final JWTTokenProperties properties = new JWTTokenProperties();
        final SecretKeyUtil keys;
        final TokenUtil tokens = new TokenUtil();
        final CurrentSessionAuthorization current = mock(CurrentSessionAuthorization.class);
        final ObjectProvider<CurrentSessionAuthorization> providers = mock(ObjectProvider.class);
        final AuthUser user;

        Fixture(UserEnums role) {
            byte[] raw = new byte[32]; new SecureRandom().nextBytes(raw);
            properties.setSecretKey(Base64.getEncoder().encodeToString(raw));
            keys = new SecretKeyUtil(properties);
            user = AuthUser.builder().id("synthetic-actor").username("synthetic-user").role(role).isSuper(false).build();
            when(current.legacy(any(AuthUser.class))).thenReturn(user);
            when(providers.getIfUnique()).thenReturn(current);
            Cache<Object> cache = mock(Cache.class, invocation -> {
                return switch (invocation.getMethod().getName()) {
                    case "hasKey" -> entries.containsKey(invocation.getArgument(0));
                    case "put" -> { writes.incrementAndGet(); entries.put(invocation.getArgument(0), invocation.getArgument(1)); yield null; }
                    case "remove" -> entries.remove(invocation.getArgument(0)) != null;
                    default -> null;
                };
            });
            ReflectionTestUtils.setField(tokens, "tokenProperties", properties);
            ReflectionTestUtils.setField(tokens, "secretKeyUtil", keys);
            ReflectionTestUtils.setField(tokens, "cache", cache);
            ReflectionTestUtils.setField(tokens, "currentAuthorizations", providers);
        }
        String oldRefresh() {
            var old = AuthUser.builder().id(user.getId()).username(user.getUsername()).role(user.getRole()).isSuper(true).build();
            return Jwts.builder().subject(old.getUsername()).claim(SecurityEnum.USER_CONTEXT.getValue(), new Gson().toJson(old))
                    .expiration(new Date(System.currentTimeMillis() + 90 * 60000))
                    .signWith(keys.generalKey(), Jwts.SIG.HS256).compact();
        }
    }
}
