package cn.lili.common.security.session;

import com.google.gson.Gson;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

import static cn.lili.common.security.session.SessionException.Code.STORE_UNAVAILABLE;

/** Uses the existing StringRedisTemplate, not the FastJson-valued legacy template. */
@Component
public final class RedisSessionStore implements SessionService.Store {
    private static final Gson JSON = new Gson();
    private final StringRedisTemplate redis;
    private final DefaultRedisScript<Long> update;

    public RedisSessionStore(StringRedisTemplate redis) {
        this.redis = redis;
        this.update = new DefaultRedisScript<>();
        update.setLocation(new ClassPathResource("script/session-update.lua"));
        update.setResultType(Long.class);
    }

    @Override
    public boolean create(SessionRecord record) {
        return execute("CREATE", record, record);
    }

    @Override
    public SessionRecord load(SessionDomain domain, String application, String sid) {
        try {
            String value = redis.opsForValue().get(key(domain, application, sid));
            if (value == null) return null;
            if (value.length() > 8192) throw new SessionException(STORE_UNAVAILABLE);
            SessionRecord record = JSON.fromJson(value, SessionRecord.class);
            if (record == null) throw new SessionException(STORE_UNAVAILABLE);
            return record;
        } catch (RuntimeException ignored) {
            throw new SessionException(STORE_UNAVAILABLE);
        }
    }

    @Override
    public boolean rotate(SessionRecord expected, SessionRecord replacement) {
        return execute("ROTATE", expected, replacement);
    }

    @Override
    public boolean revoke(SessionRecord authenticated) {
        return execute("REVOKE", authenticated, authenticated);
    }

    private boolean execute(String operation, SessionRecord expected, SessionRecord replacement) {
        try {
            Long result = redis.execute(update,
                    List.of(key(expected.identity().domain(), expected.identity().application(), expected.sid())),
                    operation, JSON.toJson(expected), JSON.toJson(replacement));
            if (result == null || (result != 0L && result != 1L)) throw new SessionException(STORE_UNAVAILABLE);
            return result == 1L;
        } catch (RuntimeException ignored) {
            // Includes timeouts after a possible commit. Never return candidate tokens on uncertainty.
            throw new SessionException(STORE_UNAVAILABLE);
        }
    }

    static String key(SessionDomain domain, String application, String sid) {
        if (domain == null || application == null || application.isBlank() || application.length() > 128
                || sid == null || !sid.matches("[a-f0-9]{32}")) throw new SessionException(STORE_UNAVAILABLE);
        return "stage0:session:v1:" + domain.name() + ":" + SessionCredentials.digest(application) + ":" + sid;
    }
}
