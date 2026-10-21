package cn.lili.common.security.token;

import cn.lili.common.properties.JWTTokenProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Application-context-owned HS256 key and verifier. Never falls back to a source key.
 *
 * @author Chopper
 * @version v1.0
 * 2020-11-18 17:30
 */
@Component
public final class SecretKeyUtil {
    private final SecretKey key;
    private final JwtParser parser;
    private volatile boolean active = true;

    public SecretKeyUtil(JWTTokenProperties properties) {
        String configured = properties.getSecretKey();
        if (configured == null || configured.isBlank()) {
            throw new IllegalStateException("JWT_SECRET_REQUIRED: lili.jwt-setting.secret-key");
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(configured);
        } catch (IllegalArgumentException ignored) {
            // Do not retain a decoder exception which might disclose supplied input.
            throw new IllegalStateException("JWT_SECRET_INVALID_BASE64: lili.jwt-setting.secret-key");
        }
        try {
            if (!Base64.getEncoder().encodeToString(decoded).equals(configured)) {
                throw new IllegalStateException("JWT_SECRET_INVALID_BASE64: lili.jwt-setting.secret-key");
            }
            if (decoded.length < 32) {
                throw new IllegalStateException("JWT_SECRET_TOO_SHORT: at least 32 decoded bytes required");
            }
            this.key = new SecretKeySpec(decoded, "HmacSHA256");
        } finally {
            Arrays.fill(decoded, (byte) 0);
        }
        this.parser = Jwts.parser().sig().clear().add(Jwts.SIG.HS256).and()
                .verifyWith(key).build();
    }

    public SecretKey generalKey() {
        requireActive();
        return key;
    }

    public SecretKey generalKeyByDecoders() {
        return generalKey();
    }

    public Claims parseClaims(String token) {
        requireActive();
        return parser.parseSignedClaims(token).getPayload();
    }

    private void requireActive() {
        if (!active) {
            throw new IllegalStateException("JWT_KEY_COMPONENT_CLOSED");
        }
    }

    @PreDestroy
    public void destroy() {
        active = false;
    }
}
