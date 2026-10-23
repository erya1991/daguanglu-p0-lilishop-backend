package cn.lili.modules.connect.wechat;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import static cn.lili.modules.connect.wechat.ResidentLoginException.Code.*;

/** Deployment gates are opt-in; neither code approval nor an offline test approves B04. */
@Component
@ConfigurationProperties(prefix = "lili.stage0-resident-wechat")
public class ResidentWechatProperties {
    private boolean enabled;
    private boolean schemaReady;
    private String appSecret;

    public void requireSchema() {
        if (!schemaReady) throw new ResidentLoginException(B04_PENDING);
    }
    public String requireSecret() {
        requireSchema();
        if (!enabled || appSecret == null || !appSecret.matches("[A-Za-z0-9]{32}")) {
            throw new ResidentLoginException(CONFIG_REQUIRED);
        }
        return appSecret;
    }
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public boolean isSchemaReady() { return schemaReady; }
    public void setSchemaReady(boolean value) { schemaReady = value; }
    public String getAppSecret() { return appSecret; }
    public void setAppSecret(String value) { appSecret = value; }
    @Override public String toString() { return "ResidentWechatProperties[REDACTED]"; }
}
