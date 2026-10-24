package cn.lili.modules.connect.wechat;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.common.security.session.SessionException;
import cn.lili.common.security.session.http.SessionApplications;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.lili.modules.connect.wechat.ResidentLoginException.Code.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic transport only. M01-001/009/011, S03-001, S01-010; no real WeChat evidence. */
class OfficialMerchantWechatCodeGatewayTest {
    private static final String RESPONSE = "{\"openid\":\"merchant-synthetic-open\",\"session_key\":\"synthetic-session-key\"}";
    private final SessionApplications applications = new SessionApplications();
    private final ResidentWechatProperties resident = new ResidentWechatProperties();
    private final MerchantWechatProperties merchant = new MerchantWechatProperties();

    OfficialMerchantWechatCodeGatewayTest() {
        applications.setResidentApplication("wx0000000000000001");
        applications.setMerchantApplication("wx0000000000000002");
        merchant.setEnabled(true);
        merchant.setSchemaReady(true);
        merchant.setAppSecret(UUID.randomUUID().toString().replace("-", ""));
    }

    @Test void merchantUsesItsServerApplicationAndSecretWhileResidentIsDisabled() {
        resident.setAppSecret(UUID.randomUUID().toString().replace("-", ""));
        var calls = new AtomicInteger();
        var gateway = gateway(url -> {
            calls.incrementAndGet();
            assertTrue(url.startsWith("https://api.weixin.qq.com/sns/jscode2session?appid=wx0000000000000002&"));
            assertTrue(url.contains("&secret=" + merchant.getAppSecret() + "&"));
            assertFalse(url.contains(resident.getAppSecret()));
            assertTrue(url.endsWith("&js_code=merchant-code&grant_type=authorization_code"));
            return RESPONSE;
        });
        var identity = gateway.authenticate("merchant-code", SessionDomain.APPLICANT);
        assertEquals(1, calls.get());
        assertEquals(applications.getMerchantApplication(), identity.appId());
        assertEquals("merchant-synthetic-open", identity.openId());
        assertNull(identity.unionId());
        assertFalse(identity.toString().contains(identity.openId()));
        assertFalse(identity.toString().contains("synthetic-session-key"));
    }

    @Test void enabledResidentCannotEnableDisabledMerchant() {
        resident.setEnabled(true);
        resident.setSchemaReady(true);
        resident.setAppSecret(UUID.randomUUID().toString().replace("-", ""));
        merchant.setEnabled(false);
        var calls = new AtomicInteger();
        var gateway = gateway(url -> { calls.incrementAndGet(); return RESPONSE; });
        assertEquals(CONFIG_REQUIRED, assertThrows(ResidentLoginException.class,
                () -> gateway.authenticate("merchant-code", SessionDomain.APPLICANT)).getCode());
        assertEquals(0, calls.get());
    }

    @Test void merchantSchemaGateIsIndependentOfResidentApproval() {
        resident.setSchemaReady(true);
        merchant.setSchemaReady(false);
        var calls = new AtomicInteger();
        var gateway = gateway(url -> { calls.incrementAndGet(); return RESPONSE; });
        assertEquals(B04_PENDING, assertThrows(ResidentLoginException.class,
                () -> gateway.authenticate("merchant-code", SessionDomain.APPLICANT)).getCode());
        assertEquals(0, calls.get());
    }

    @Test void defaultMerchantConfigurationFailsBeforeTransport() {
        var calls = new AtomicInteger();
        var gateway = new OfficialWechatCodeGateway(applications, resident, new MerchantWechatProperties(),
                url -> { calls.incrementAndGet(); return RESPONSE; });
        assertEquals(B04_PENDING, assertThrows(ResidentLoginException.class,
                () -> gateway.authenticate("merchant-code", SessionDomain.APPLICANT)).getCode());
        assertEquals(0, calls.get());
    }

    @Test void sameResidentAndMerchantApplicationIsRejectedBeforeTransport() {
        applications.setMerchantApplication(applications.getResidentApplication());
        var calls = new AtomicInteger();
        var gateway = gateway(url -> { calls.incrementAndGet(); return RESPONSE; });
        assertEquals(SessionException.Code.INVALID_CONTEXT, assertThrows(SessionException.class,
                () -> gateway.authenticate("merchant-code", SessionDomain.APPLICANT)).getCode());
        assertEquals(0, calls.get());
    }

    @ParameterizedTest @ValueSource(strings = {"merchant-app", "wx1", "wx000000000000000Z"})
    void malformedServerApplicationCannotBecomeATrustedMerchant(String app) {
        applications.setMerchantApplication(app);
        var calls = new AtomicInteger();
        var gateway = gateway(url -> { calls.incrementAndGet(); return RESPONSE; });
        assertEquals(CONFIG_REQUIRED, assertThrows(ResidentLoginException.class,
                () -> gateway.authenticate("merchant-code", SessionDomain.APPLICANT)).getCode());
        assertEquals(0, calls.get());
    }

    @ParameterizedTest @ValueSource(ints = {40029, 40163, 40013, 45011, -1})
    void officialInvalidExpiredWrongAppAndThrottledErrorsDoNotAuthenticate(int code) {
        var gateway = gateway(url -> "{\"errcode\":" + code
                + ",\"openid\":\"must-not-trust\",\"session_key\":\"must-not-trust\",\"errmsg\":\"private\"}");
        assertEquals(WECHAT_REJECTED, assertThrows(ResidentLoginException.class,
                () -> gateway.authenticate("merchant-code", SessionDomain.APPLICANT)).getCode());
    }

    @ParameterizedTest @NullSource
    @ValueSource(strings = {"{}", "null", "[]", "not-json", "{\"openid\":\"o\"}",
            "{\"openid\":\"o\",\"session_key\":\"\"}", "{\"openid\":123,\"session_key\":\"s\"}",
            "{\"openid\":\"o\",\"session_key\":\"s\",\"errcode\":\"0\"}",
            "{\"openid\":\"o\",\"session_key\":\"s\",\"unionid\":{}}"})
    void malformedOrIncompleteOfficialResponseFailsClosed(String response) {
        var failure = assertThrows(ResidentLoginException.class,
                () -> gateway(url -> response).authenticate("merchant-code", SessionDomain.APPLICANT));
        assertNull(failure.getCause());
    }

    @ParameterizedTest @NullSource
    @ValueSource(strings = {"", " ", "code?secret=bad", "code\n", "中文"})
    void malformedCodeNeverReachesMerchantTransport(String code) {
        var calls = new AtomicInteger();
        var gateway = gateway(url -> { calls.incrementAndGet(); return RESPONSE; });
        assertEquals(INVALID_REQUEST, assertThrows(ResidentLoginException.class,
                () -> gateway.authenticate(code, SessionDomain.APPLICANT)).getCode());
        assertEquals(0, calls.get());
    }

    @ParameterizedTest @ValueSource(strings = {"STORE", "MANAGER"})
    void codeGatewayCannotDirectlyGrantAStoreOrManagerDomain(String domain) {
        var calls = new AtomicInteger();
        var gateway = gateway(url -> { calls.incrementAndGet(); return RESPONSE; });
        assertEquals(INVALID_REQUEST, assertThrows(ResidentLoginException.class,
                () -> gateway.authenticate("merchant-code", SessionDomain.valueOf(domain))).getCode());
        assertEquals(0, calls.get());
    }

    @Test void oversizedResponseIsRejected() {
        assertEquals(WECHAT_UNAVAILABLE, assertThrows(ResidentLoginException.class,
                () -> gateway(url -> "x".repeat(8193)).authenticate("merchant-code", SessionDomain.APPLICANT)).getCode());
    }

    @Test void legacyResidentGatewaySeamCannotSilentlyAuthenticateMerchant() {
        var calls = new AtomicInteger();
        WechatCodeGateway oldSeam = code -> {
            calls.incrementAndGet();
            return new WechatCodeGateway.Identity(applications.getResidentApplication(), "resident-open", null);
        };
        assertEquals(CONFIG_REQUIRED, assertThrows(ResidentLoginException.class,
                () -> oldSeam.authenticate("merchant-code", SessionDomain.APPLICANT)).getCode());
        assertEquals(0, calls.get());
    }

    @Test @ExtendWith(OutputCaptureExtension.class)
    void transportFailureCannotExposeMerchantSecretCodeOrCredentialUrl(CapturedOutput output) {
        var gateway = gateway(url -> { throw new IllegalStateException(url); });
        var failure = assertThrows(ResidentLoginException.class,
                () -> gateway.authenticate("merchant-private-code", SessionDomain.APPLICANT));
        assertEquals(WECHAT_UNAVAILABLE, failure.getCode());
        assertNull(failure.getCause());
        var stack = new StringWriter();
        failure.printStackTrace(new PrintWriter(stack));
        String diagnostics = output.getAll() + failure + stack + merchant;
        for (String secret : new String[]{merchant.getAppSecret(), "merchant-private-code", "js_code=", "secret="}) {
            assertFalse(diagnostics.contains(secret));
        }
    }

    @Test @ExtendWith(OutputCaptureExtension.class)
    void parsingFailureCannotExposeOfficialResponseSecrets(CapturedOutput output) {
        String sessionKey = "synthetic-private-session-key";
        var failure = assertThrows(ResidentLoginException.class, () -> gateway(url ->
                "{\"openid\":{},\"session_key\":\"" + sessionKey + "\",\"errmsg\":\"sensitive-official-diagnostic\"}")
                .authenticate("merchant-private-code", SessionDomain.APPLICANT));
        assertNull(failure.getCause());
        String diagnostics = output.getAll() + failure;
        assertFalse(diagnostics.contains(sessionKey));
        assertFalse(diagnostics.contains("sensitive-official-diagnostic"));
        assertFalse(diagnostics.contains("merchant-private-code"));
    }

    private OfficialWechatCodeGateway gateway(OfficialWechatCodeGateway.Exchange exchange) {
        return new OfficialWechatCodeGateway(applications, resident, merchant, exchange);
    }
}
