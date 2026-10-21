package cn.lili.common.security.token;

import cn.lili.common.properties.JWTTokenProperties;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.ConfigTreePropertySource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class SecretKeyUtilSecurityTest {
    private static JWTTokenProperties properties(int bytes) {
        byte[] raw = new byte[bytes];
        new SecureRandom().nextBytes(raw);
        JWTTokenProperties p = new JWTTokenProperties();
        p.setSecretKey(Base64.getEncoder().encodeToString(raw));
        return p;
    }
    private static AnnotationConfigApplicationContext context(JWTTokenProperties p) {
        var c = new AnnotationConfigApplicationContext();
        c.registerBean(JWTTokenProperties.class, () -> p);
        c.register(SecretKeyUtil.class);
        return c;
    }
    @Test
    void noDefaultSecretAndNoSecretInPropertiesToString() {
        assertNull(new JWTTokenProperties().getSecretKey());
        var p = properties(32);
        assertFalse(p.toString().contains(p.getSecretKey()), "properties must redact secret");
        assertEquals(60, p.getTokenExpireTime());
    }
    @Test
    void missingSecretFailsBeanCreation() {
        try (var c = context(new JWTTokenProperties())) {
            var e = assertThrows(BeanCreationException.class, c::refresh);
            assertTrue(e.getMostSpecificCause().getMessage().startsWith("JWT_SECRET_REQUIRED"));
        }
    }
    @Test
    void malformedSecretFailsBeanCreationWithoutInputOrDecoderCause() {
        var p = new JWTTokenProperties();
        p.setSecretKey("not-base64!");
        try (var c = context(p)) {
            var e = assertThrows(BeanCreationException.class, c::refresh);
            Throwable cause = e.getMostSpecificCause();
            assertTrue(cause.getMessage().startsWith("JWT_SECRET_INVALID_BASE64"));
            assertFalse(cause.getMessage().contains(p.getSecretKey()), "input must not be disclosed");
            assertNull(cause.getCause());
        }
    }
    @Test
    void shortDecodedKeysAreRejected() {
        for (int size : new int[]{1, 16, 31}) {
            var p = properties(size);
            var e = assertThrows(IllegalStateException.class, () -> new SecretKeyUtil(p));
            assertTrue(e.getMessage().startsWith("JWT_SECRET_TOO_SHORT"));
            assertFalse(e.getMessage().contains(p.getSecretKey()), "input must not be disclosed");
        }
    }
    @Test
    void nonCanonicalBase64AndWhitespaceAreRejected() {
        var p = properties(32);
        String canonical = p.getSecretKey();
        for (String invalid : new String[]{canonical + "\n", " " + canonical,
                canonical.substring(0, canonical.length() - 1), "${UNRESOLVED_JWT_SECRET}"}) {
            p.setSecretKey(invalid);
            assertThrows(IllegalStateException.class, () -> new SecretKeyUtil(p));
        }
    }
    @Test
    void bothInterfacesUseOneKeyAndRoundTripUsesHs256() {
        var keys = new SecretKeyUtil(properties(32));
        assertSame(keys.generalKey(), keys.generalKeyByDecoders());
        String token = Jwts.builder().subject("synthetic").expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(keys.generalKey(), Jwts.SIG.HS256).compact();
        assertEquals("synthetic", keys.parseClaims(token).getSubject());
        assertEquals("HS256", Jwts.parser().verifyWith(keys.generalKey()).build()
                .parseSignedClaims(token).getHeader().getAlgorithm());
    }
    @Test
    void longerKeyStillUsesOnlyHs256() {
        var keys = new SecretKeyUtil(properties(64));
        String valid = Jwts.builder().subject("synthetic").signWith(keys.generalKey(), Jwts.SIG.HS256).compact();
        assertEquals("synthetic", keys.parseClaims(valid).getSubject());
        String wrong = Jwts.builder().subject("synthetic")
                .signWith(new SecretKeySpec(keys.generalKey().getEncoded(), "HmacSHA512"), Jwts.SIG.HS512).compact();
        assertThrows(JwtException.class, () -> keys.parseClaims(wrong));
    }
    @Test
    void anotherRandomKeyCannotVerify() {
        var a = new SecretKeyUtil(properties(32));
        var b = new SecretKeyUtil(properties(32));
        String t = Jwts.builder().subject("synthetic").signWith(a.generalKey(), Jwts.SIG.HS256).compact();
        assertThrows(JwtException.class, () -> b.parseClaims(t));
    }
    @Test
    void expiredTokenIsRejected() {
        var keys = new SecretKeyUtil(properties(32));
        String t = Jwts.builder().subject("synthetic").expiration(new Date(System.currentTimeMillis() - 60000))
                .signWith(keys.generalKey(), Jwts.SIG.HS256).compact();
        assertThrows(JwtException.class, () -> keys.parseClaims(t));
    }
    @Test
    void tamperedAndUnsignedTokensAreRejected() {
        var keys = new SecretKeyUtil(properties(32));
        String t = Jwts.builder().subject("synthetic").signWith(keys.generalKey(), Jwts.SIG.HS256).compact();
        String[] parts = t.split("\\.");
        parts[1] = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"sub\":\"modified\"}".getBytes());
        assertThrows(JwtException.class, () -> keys.parseClaims(String.join(".", parts)));
        String unsigned = Jwts.builder().subject("synthetic").compact();
        assertThrows(JwtException.class, () -> keys.parseClaims(unsigned));
    }
    @Test
    void realEnvironmentBindingConsumesCanonicalSecretProperty() {
        var generated = properties(32);
        var env = new StandardEnvironment();
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                Map.of("LILI_JWTSETTING_SECRETKEY", generated.getSecretKey())));
        var bound = Binder.get(env).bind("lili.jwt-setting", Bindable.of(JWTTokenProperties.class)).get();
        assertTrue(generated.getSecretKey().equals(bound.getSecretKey()), "environment binding must match");
        assertNotNull(new SecretKeyUtil(bound));
    }
    @Test
    void externalConfigTreeCanSupplyTheSameProperty(@TempDir Path dir) throws Exception {
        var generated = properties(32);
        Path f = dir.resolve("lili.jwt-setting.secret-key");
        try {
            Files.writeString(f, generated.getSecretKey());
            var env = new StandardEnvironment();
            env.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            env.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            env.getPropertySources().addFirst(new ConfigTreePropertySource("synthetic-secret-file", dir));
            var bound = Binder.get(env).bind("lili.jwt-setting", Bindable.of(JWTTokenProperties.class)).get();
            assertTrue(generated.getSecretKey().equals(bound.getSecretKey()), "secret-file binding must match");
            assertNotNull(new SecretKeyUtil(bound));
        } finally {
            Files.deleteIfExists(f);
        }
        assertFalse(Files.exists(f), "temporary secret must be deleted");
    }
    @Test
    void separateContextsOwnDifferentKeysAndCloseInvalidatesOldComponent() {
        try (var a = context(properties(32)); var b = context(properties(32))) {
            a.refresh();
            b.refresh();
            var ka = a.getBean(SecretKeyUtil.class);
            var kb = b.getBean(SecretKeyUtil.class);
            String t = Jwts.builder().subject("synthetic").signWith(ka.generalKey(), Jwts.SIG.HS256).compact();
            assertThrows(JwtException.class, () -> kb.parseClaims(t));
            a.close();
            assertThrows(IllegalStateException.class, () -> ka.parseClaims(t));
            assertNotNull(kb.generalKey());
        }
    }
}
