package cn.lili.controller.im;

import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.common.properties.JWTTokenProperties;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.enums.SecurityEnum;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.token.SecretKeyUtil;
import cn.lili.modules.im.service.ImMessageService;
import cn.lili.modules.im.service.ImTalkService;
import com.google.gson.Gson;
import io.jsonwebtoken.Jwts;
import jakarta.websocket.Session;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import tools.jackson.databind.json.JsonMapper;
import java.security.SecureRandom;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class WebSocketJwtSecurityTest {
    private static JWTTokenProperties properties() {
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        var p = new JWTTokenProperties();
        p.setSecretKey(Base64.getEncoder().encodeToString(raw));
        return p;
    }
    private static String token(SecretKeyUtil key) {
        var u = AuthUser.builder().id("c2c-im-actor").username("synthetic-user")
                .role(UserEnums.STORE).storeId("c2c-im-store").build();
        return Jwts.builder().subject(u.getUsername()).expiration(new Date(System.currentTimeMillis() + 60000))
                .claim(SecurityEnum.USER_CONTEXT.getValue(), new Gson().toJson(u))
                .signWith(key.generalKey(), Jwts.SIG.HS256).compact();
    }
    private static AnnotationConfigApplicationContext context(Set<Object> sessions) {
        var c = new AnnotationConfigApplicationContext();
        c.registerBean(JWTTokenProperties.class, WebSocketJwtSecurityTest::properties);
        c.registerBean(Cache.class, () -> mock(Cache.class, call ->
                call.getMethod().getName().equals("hasKey") && sessions.contains(call.getArgument(0))));
        c.registerBean(ImMessageService.class, () -> mock(ImMessageService.class));
        c.registerBean(ImTalkService.class, () -> mock(ImTalkService.class));
        c.registerBean(JsonMapper.class, () -> mock(JsonMapper.class));
        c.register(SecretKeyUtil.class, WebSocketServer.class);
        c.refresh();
        return c;
    }
    @Test
    void springPrototypeUsesInjectedVerifierWithoutAnHttpRequest() {
        RequestContextHolder.resetRequestAttributes();
        Set<Object> sessions = new HashSet<>();
        try (var c = context(sessions)) {
            String t = token(c.getBean(SecretKeyUtil.class));
            sessions.add(CachePrefix.ACCESS_TOKEN.getPrefix(UserEnums.STORE, "c2c-im-actor") + t);
            var endpoint = c.getBean(WebSocketServer.class);
            var socket = mock(Session.class);
            Map<?, ?> pool = (Map<?, ?>) ReflectionTestUtils.getField(WebSocketServer.class, "sessionPools");
            endpoint.onOpen(t, socket);
            try {
                assertSame(socket, pool.get("c2c-im-store"));
            } finally {
                endpoint.onClose(t);
            }
            assertFalse(pool.containsKey("c2c-im-store"));
        }
    }
    @Test
    void wrongSigningKeyCannotCreateAnImSession() {
        RequestContextHolder.resetRequestAttributes();
        Set<Object> sessions = new HashSet<>();
        try (var c = context(sessions)) {
            String t = token(new SecretKeyUtil(properties()));
            sessions.add(CachePrefix.ACCESS_TOKEN.getPrefix(UserEnums.STORE, "c2c-im-actor") + t);
            var endpoint = c.getBean(WebSocketServer.class);
            assertThrows(NullPointerException.class, () -> endpoint.onOpen(t, mock(Session.class)));
            Map<?, ?> pool = (Map<?, ?>) ReflectionTestUtils.getField(WebSocketServer.class, "sessionPools");
            assertFalse(pool.containsKey("c2c-im-store"));
        }
    }
}
