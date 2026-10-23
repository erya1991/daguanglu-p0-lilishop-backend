package cn.lili.modules.connect.wechat;

import cn.lili.common.security.session.http.SessionApplications;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static cn.lili.modules.connect.wechat.ResidentLoginException.Code.*;

/** Stubbed HTTP only; never calls WeChat. REQ-S0-R01-001/005/006, S01-010, S02-001/004/008. */
class OfficialWechatCodeGatewayTest {
    final SessionApplications apps = new SessionApplications();
    final ResidentWechatProperties properties = new ResidentWechatProperties();
    OfficialWechatCodeGatewayTest() {
        apps.setResidentApplication("wx0000000000000001"); apps.setMerchantApplication("wx0000000000000002");
        properties.setEnabled(true); properties.setSchemaReady(true);
        properties.setAppSecret(UUID.randomUUID().toString().replace("-", ""));
    }

    @Test void trustedServerAppAndNoUnionIdAreEnough() {
        var gateway = new OfficialWechatCodeGateway(apps, properties, url -> {
            assertTrue(url.startsWith("https://api.weixin.qq.com/sns/jscode2session?appid=wx0000000000000001&"));
            assertTrue(url.contains("&js_code=synthetic-code&grant_type=authorization_code"));
            return "{\"openid\":\"synthetic-open\",\"session_key\":\"synthetic-session-key\"}";
        });
        var result = gateway.authenticate("synthetic-code");
        assertEquals(apps.getResidentApplication(), result.appId());
        assertEquals("synthetic-open", result.openId()); assertNull(result.unionId());
        assertFalse(result.toString().contains(result.openId()));
    }
    @ParameterizedTest @ValueSource(ints = {40029, 40163, 40013, 45011, -1})
    void invalidExpiredWrongAppAndThrottledCodesReject(int code) {
        var gateway = new OfficialWechatCodeGateway(apps, properties, url -> "{\"errcode\":" + code
                + ",\"openid\":\"must-not-trust\",\"session_key\":\"must-not-trust\",\"errmsg\":\"private\"}");
        assertEquals(WECHAT_REJECTED, assertThrows(ResidentLoginException.class, () -> gateway.authenticate("synthetic-code")).getCode());
    }
    @ParameterizedTest @ValueSource(strings = {"{}", "null", "[]", "not-json", "{\"openid\":\"o\"}",
            "{\"openid\":\"o\",\"session_key\":\"\"}", "{\"openid\":123,\"session_key\":\"s\"}",
            "{\"openid\":\"o\",\"session_key\":\"s\",\"errcode\":\"0\"}"})
    void incompleteOrMalformedResponsesFailClosed(String response) {
        var gateway = new OfficialWechatCodeGateway(apps, properties, url -> response);
        assertThrows(ResidentLoginException.class, () -> gateway.authenticate("synthetic-code"));
    }
    @ParameterizedTest @ValueSource(strings = {"", " ", "code?secret=bad", "code\n", "中文"})
    void malformedRequestNeverReachesTransport(String code) {
        var calls = new AtomicInteger();
        var gateway = new OfficialWechatCodeGateway(apps, properties, url -> { calls.incrementAndGet(); return "{}"; });
        assertEquals(INVALID_REQUEST, assertThrows(ResidentLoginException.class, () -> gateway.authenticate(code)).getCode());
        assertEquals(0, calls.get());
    }
    @Test void deploymentGatesNeverEnableMockOrOutboundByDefault() {
        var defaults = new ResidentWechatProperties();
        var calls = new AtomicInteger();
        var gateway = new OfficialWechatCodeGateway(apps, defaults, url -> { calls.incrementAndGet(); return "{}"; });
        assertEquals(B04_PENDING, assertThrows(ResidentLoginException.class, () -> gateway.authenticate("synthetic-code")).getCode());
        defaults.setSchemaReady(true);
        assertEquals(CONFIG_REQUIRED, assertThrows(ResidentLoginException.class, () -> gateway.authenticate("synthetic-code")).getCode());
        assertEquals(0, calls.get());
    }
    @Test @ExtendWith(OutputCaptureExtension.class)
    void transportFailuresCannotRetainCredentialUrlOrSecret(CapturedOutput output) {
        var gateway = new OfficialWechatCodeGateway(apps, properties, url -> { throw new IllegalStateException(url); });
        var failure = assertThrows(ResidentLoginException.class, () -> gateway.authenticate("synthetic-private-code"));
        assertEquals(WECHAT_UNAVAILABLE, failure.getCode()); assertNull(failure.getCause());
        String diagnostics = output.getAll() + failure + properties;
        for (String secret : new String[]{properties.getAppSecret(), "synthetic-private-code", "js_code="}) {
            assertFalse(diagnostics.contains(secret));
        }
    }
}
