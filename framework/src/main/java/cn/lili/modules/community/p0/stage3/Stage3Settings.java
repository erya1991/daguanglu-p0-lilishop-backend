package cn.lili.modules.community.p0.stage3;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** No write or credential capability is enabled by merely enabling the Stage3 UI. */
@Data
@ConfigurationProperties("p0.stage3")
public class Stage3Settings {
    private boolean enabled;
    private boolean writesEnabled;
    private boolean credentialsEnabled;
    private boolean workersEnabled;
    private String admissionFile;
    private String securityApprovalFile;
    private String keyFile;
    @Override public String toString() { return "Stage3Settings[REDACTED]"; }
}
