package cn.lili.common.security.session;

import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static cn.lili.common.security.session.SessionException.Code.STORE_UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Adapter/serialization tests use mocks only. The Lua script is loaded, not executed in Redis. */
@SuppressWarnings({"unchecked", "rawtypes"})
class RedisSessionStoreTest {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedisSessionStore store = new RedisSessionStore(redis);
    private final SessionRecord record = new SessionRecord(1, "0123456789abcdef0123456789abcdef",
            new SessionIdentity("synthetic-subject", SessionDomain.RESIDENT, "synthetic-app", "synthetic-map", 1L),
            SessionRecord.Status.ACTIVE, 0, "a".repeat(64), "b".repeat(64), 1000, 901000, 1296001000);

    @BeforeEach void setup() {
        when(redis.opsForValue()).thenReturn(values);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);
    }

    @Test void T06_scriptAndSerializationContractUseOneKeyAndOneFinalMutation() {
        assertTrue(store.create(record));
        var script = ArgumentCaptor.forClass(RedisScript.class);
        var keys = ArgumentCaptor.forClass(List.class);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(redis).execute(script.capture(), keys.capture(), args.capture());
        assertEquals(Long.class, script.getValue().getResultType());
        assertEquals(List.of(RedisSessionStore.key(SessionDomain.RESIDENT, "synthetic-app", record.sid())), keys.getValue());
        assertEquals(3, args.getValue().length);
        assertEquals("CREATE", args.getValue()[0]);
        String json = (String) args.getValue()[1];
        assertTrue(json.startsWith("{"), "JSON is plain UTF-8, not a JSON-quoted string");
        assertEquals(record, new Gson().fromJson(json, SessionRecord.class));
        var serializer = new StringRedisSerializer(StandardCharsets.UTF_8);
        assertEquals(json, serializer.deserialize(serializer.serialize(json)));
        String lua = script.getValue().getScriptAsString();
        int write = lua.indexOf("redis.call('SET'");
        assertTrue(write > lua.indexOf("local encoded = cjson.encode(replacement)"));
        assertEquals(write, lua.lastIndexOf("redis.call('SET'"), "one state mutation");
        assertTrue(lua.contains("current.refreshDigest ~= expected.refreshDigest"));
        assertTrue(lua.contains("current.generation ~= expected.generation"));
        assertTrue(lua.contains("current.status ~= 'ACTIVE'"));
        assertTrue(lua.contains("'PXAT', deadline"));
        assertFalse(lua.contains("redis.call('DEL'"));
    }

    @Test void T07_rotationPassesOnlyDigestsAndExpectedState() {
        var next = record.rotated("c".repeat(64), "d".repeat(64), record.accessExpiresAt());
        assertTrue(store.rotate(record, next));
        verify(redis).execute(any(RedisScript.class), anyList(), eq("ROTATE"), eq(new Gson().toJson(record)), eq(new Gson().toJson(next)));
    }

    @Test void T09_revocationUsesSameAtomicScriptAndSidKey() {
        assertTrue(store.revoke(record));
        verify(redis).execute(any(RedisScript.class), eq(List.of(RedisSessionStore.key(
                SessionDomain.RESIDENT, "synthetic-app", record.sid()))), eq("REVOKE"), anyString(), anyString());
    }

    @Test void T11_keyNamespaceSeparatesDomainsAndApplications() {
        String first = RedisSessionStore.key(SessionDomain.RESIDENT, "synthetic-app", record.sid());
        assertFalse(first.equals(RedisSessionStore.key(SessionDomain.STORE, "synthetic-app", record.sid())));
        assertFalse(first.equals(RedisSessionStore.key(SessionDomain.RESIDENT, "synthetic-other", record.sid())));
        assertFalse(first.contains("synthetic-app"));
    }

    @Test void T13_unknownRepliesAndRedisExceptionsFailClosed() {
        for (Long reply : new Long[]{null, -1L, 2L}) {
            when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(reply);
            assertUnavailable(() -> store.create(record));
            assertUnavailable(() -> store.rotate(record, record));
            assertUnavailable(() -> store.revoke(record));
        }
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new IllegalStateException("synthetic-secret-must-not-be-retained"));
        assertUnavailable(() -> store.create(record));
        assertUnavailable(() -> store.rotate(record, record));
        assertUnavailable(() -> store.revoke(record));
        when(values.get(anyString())).thenThrow(new IllegalStateException("synthetic-secret-must-not-be-retained"));
        assertUnavailable(() -> store.load(SessionDomain.RESIDENT, "synthetic-app", record.sid()));
    }

    @Test void T13_missingAndCorruptStateNeverFallsBackToJwt() {
        assertNull(store.load(SessionDomain.RESIDENT, "synthetic-app", record.sid()));
        for (String invalid : List.of("null", "{}", "not-json", "\"quoted-json\"", "x".repeat(8193))) {
            when(values.get(anyString())).thenReturn(invalid);
            assertUnavailable(() -> store.load(SessionDomain.RESIDENT, "synthetic-app", record.sid()));
        }
        when(values.get(anyString())).thenReturn(new Gson().toJson(record));
        assertEquals(record, store.load(SessionDomain.RESIDENT, "synthetic-app", record.sid()));
    }

    @Test void T15_zeroReplyIsAnExplicitConflictAndErrorsAreSanitized() {
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
        assertFalse(store.rotate(record, record));
        when(values.get(anyString())).thenThrow(new IllegalStateException("synthetic-secret-must-not-be-retained"));
        var failure = assertThrows(SessionException.class, () -> store.load(SessionDomain.RESIDENT, "synthetic-app", record.sid()));
        assertEquals("SESSION_STORE_UNAVAILABLE", failure.getMessage());
        assertNull(failure.getCause());
    }

    private static void assertUnavailable(org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(SessionException.class, action);
        assertEquals(STORE_UNAVAILABLE, failure.getCode());
        assertNull(failure.getCause());
    }
}
