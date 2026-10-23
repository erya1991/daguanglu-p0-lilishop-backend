package cn.lili.common.security.session;

import cn.lili.common.security.token.SecretKeyUtil;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;

import static cn.lili.common.security.session.SessionException.Code.INVALID_CREDENTIAL;

/** Protocol-specific codec; all four domains still use the same SessionService. */
final class SessionCredentials {
    enum Use { ACCESS, REFRESH }
    private static final String ISSUER = "daguanglu-stage0-session";
    private final SecretKeyUtil keys;
    private final SecureRandom random = new SecureRandom();

    SessionCredentials(SecretKeyUtil keys) {
        this.keys = keys;
    }

    String sid() {
        byte[] bytes = new byte[16];
        random.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    String issue(String sid, SessionIdentity identity, Use use, long generation, long now, long expiresAt) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String unique = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        if (identity.domain() == SessionDomain.APPLICANT) {
            return "s0o1." + sid + "." + use.name() + "." + unique;
        }
        return Jwts.builder().issuer(ISSUER).claim("sv", 1).claim("sid", sid)
                .claim("domain", identity.domain().name()).claim("app", identity.application())
                .claim("use", use.name()).claim("gen", generation)
                .claim("bid", identity.bindingId()).claim("bv", identity.bindingVersion())
                .id(unique).subject(identity.subject()).issuedAt(new Date(now)).expiration(new Date(expiresAt))
                .signWith(keys.generalKey(), Jwts.SIG.HS256).compact();
    }

    Ticket decode(String token, Use use, SessionDomain domain, String application) {
        try {
            if (token == null || token.length() > 8192 || domain == null || application == null) {
                throw new SessionException(INVALID_CREDENTIAL);
            }
            if (domain == SessionDomain.APPLICANT) {
                String[] parts = token.split("\\.", -1);
                if (parts.length != 4 || !parts[0].equals("s0o1") || !parts[1].matches("[a-f0-9]{32}")
                        || !parts[2].equals(use.name()) || !parts[3].matches("[A-Za-z0-9_-]{43}")) {
                    throw new SessionException(INVALID_CREDENTIAL);
                }
                return new Ticket(parts[1], null, -1, -1);
            }
            Claims claims = keys.parseClaims(token);
            if (!ISSUER.equals(claims.getIssuer()) || number(claims, "sv") != 1
                    || !domain.name().equals(claims.get("domain")) || !application.equals(claims.get("app"))
                    || !use.name().equals(claims.get("use")) || claims.getId() == null
                    || !claims.getId().matches("[A-Za-z0-9_-]{43}") || claims.getExpiration() == null
                    || claims.getIssuedAt() == null) {
                throw new SessionException(INVALID_CREDENTIAL);
            }
            String bindingId = claims.get("bid", String.class);
            Long bindingVersion = claims.containsKey("bv") ? number(claims, "bv") : null;
            var identity = new SessionIdentity(claims.getSubject(), domain, application, bindingId, bindingVersion);
            String sid = claims.get("sid", String.class);
            if (sid == null || !sid.matches("[a-f0-9]{32}")) throw new SessionException(INVALID_CREDENTIAL);
            return new Ticket(sid, identity, number(claims, "gen"), claims.getExpiration().getTime());
        } catch (RuntimeException ignored) {
            // Parser/decoder errors can contain the input token; never keep their cause.
            throw new SessionException(INVALID_CREDENTIAL);
        }
    }

    private static long number(Claims claims, String name) {
        Long value = claims.get(name, Long.class);
        if (value == null || value < 0) throw new SessionException(INVALID_CREDENTIAL);
        return value;
    }

    static String digest(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SESSION_DIGEST_UNAVAILABLE");
        }
    }

    static boolean matches(String token, String digest) {
        return MessageDigest.isEqual(digest(token).getBytes(StandardCharsets.US_ASCII),
                digest.getBytes(StandardCharsets.US_ASCII));
    }

    record Ticket(String sid, SessionIdentity identity, long generation, long expiresAt) { }
}
