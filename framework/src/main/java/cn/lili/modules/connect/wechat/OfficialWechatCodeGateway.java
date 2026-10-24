package cn.lili.modules.connect.wechat;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.common.security.session.http.SessionApplications;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import static cn.lili.modules.connect.wechat.ResidentLoginException.Code.*;

/** Fixed official endpoint, bounded time/body, no redirects or credential-bearing diagnostics. */
@Component
public final class OfficialWechatCodeGateway implements WechatCodeGateway {
    private final SessionApplications applications;
    private final ResidentWechatProperties properties;
    private final MerchantWechatProperties merchant;
    private final Exchange exchange;

    @Autowired
    public OfficialWechatCodeGateway(SessionApplications applications, ResidentWechatProperties properties,
                                     MerchantWechatProperties merchant) {
        this(applications, properties, merchant, OfficialWechatCodeGateway::get);
    }
    public OfficialWechatCodeGateway(SessionApplications applications, ResidentWechatProperties properties) {
        this(applications, properties, new MerchantWechatProperties(), OfficialWechatCodeGateway::get);
    }
    // Package-only transport seam; used by offline tests, never selected by a production profile.
    OfficialWechatCodeGateway(SessionApplications applications, ResidentWechatProperties properties, Exchange exchange) {
        this(applications, properties, new MerchantWechatProperties(), exchange);
    }
    OfficialWechatCodeGateway(SessionApplications applications, ResidentWechatProperties properties,
                              MerchantWechatProperties merchant, Exchange exchange) {
        this.applications = applications; this.properties = properties; this.merchant = merchant; this.exchange = exchange;
    }
    @Override public Identity authenticate(String loginCode) {
        return authenticate(loginCode, SessionDomain.RESIDENT);
    }
    @Override public Identity authenticate(String loginCode, SessionDomain domain) {
        ResidentWechatLoginService.requireCode(loginCode);
        if (domain != SessionDomain.RESIDENT && domain != SessionDomain.APPLICANT) {
            throw new ResidentLoginException(INVALID_REQUEST);
        }
        String secret = domain == SessionDomain.RESIDENT ? properties.requireSecret() : merchant.requireSecret();
        String app = applications.application(domain);
        if (!app.matches("wx[0-9a-fA-F]{16}")) throw new ResidentLoginException(CONFIG_REQUIRED);
        try {
            String url = "https://api.weixin.qq.com/sns/jscode2session?appid=" + encode(app)
                    + "&secret=" + encode(secret) + "&js_code=" + encode(loginCode) + "&grant_type=authorization_code";
            String body = exchange.get(url);
            if (body == null || body.length() > 8192) throw new ResidentLoginException(WECHAT_UNAVAILABLE);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("errcode") && (!json.get("errcode").isJsonPrimitive()
                    || !json.getAsJsonPrimitive("errcode").isNumber()
                    || !json.get("errcode").getAsString().equals("0"))) {
                throw new ResidentLoginException(WECHAT_REJECTED);
            }
            // session_key proves a complete official success response, but is never retained/returned.
            String sessionKey = string(json, "session_key");
            if (sessionKey.isBlank() || sessionKey.length() > 256) throw new ResidentLoginException(WECHAT_REJECTED);
            return new Identity(app, string(json, "openid"), json.has("unionid") ? string(json, "unionid") : null);
        } catch (ResidentLoginException known) { throw new ResidentLoginException(known.getCode()); }
        catch (Exception ignored) { throw new ResidentLoginException(WECHAT_UNAVAILABLE); }
    }
    private static String string(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonPrimitive() || !json.getAsJsonPrimitive(key).isString()) {
            throw new ResidentLoginException(WECHAT_REJECTED);
        }
        return json.get(key).getAsString();
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String get(String url) throws Exception {
        // HttpUtils.doGet follows redirects and reads an unbounded body; do not change that legacy helper.
        var connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(3000); connection.setReadTimeout(5000);
            connection.setUseCaches(false);
            if (connection.getResponseCode() != 200) throw new ResidentLoginException(WECHAT_UNAVAILABLE);
            try (var input = connection.getInputStream()) {
                byte[] bytes = input.readNBytes(8193);
                if (bytes.length > 8192) throw new ResidentLoginException(WECHAT_UNAVAILABLE);
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } finally { connection.disconnect(); }
    }
    @FunctionalInterface interface Exchange { String get(String url) throws Exception; }
}
