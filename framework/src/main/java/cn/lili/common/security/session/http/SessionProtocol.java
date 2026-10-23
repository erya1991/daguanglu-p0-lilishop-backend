package cn.lili.common.security.session.http;

import com.google.gson.JsonParser;
import io.jsonwebtoken.Claims;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Dispatch only. Classification never authenticates a credential. */
public final class SessionProtocol {
    private SessionProtocol() { }

    public static boolean isStage0(String token) {
        if (token == null) return false;
        if (token.startsWith("s0o1.") || token.length() > 8192) return true;
        try {
            String[] parts = token.split("\\.", -1);
            if (parts.length != 3) return false;
            var body = JsonParser.parseString(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            return body.isJsonObject() && (body.getAsJsonObject().has("sv") || body.getAsJsonObject().has("sid")
                    || body.getAsJsonObject().has("use"));
        } catch (RuntimeException ignored) { return false; }
    }

    public static boolean isStage0(Claims claims) {
        return claims.containsKey("sv") || claims.containsKey("sid") || claims.containsKey("use");
    }
}
