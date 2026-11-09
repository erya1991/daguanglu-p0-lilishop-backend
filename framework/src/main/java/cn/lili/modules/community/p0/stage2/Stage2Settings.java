package cn.lili.modules.community.p0.stage2;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Set;

@Data
@ConfigurationProperties("p0.stage2")
public class Stage2Settings {
    private boolean enabled;
    private boolean stage3ResourceMode;
    private boolean stage45ResourceMode;
    private boolean uatResourceMode;
    private String environment = "TEST";
    private String mode = "MOCK";
    private String runId;
    private String mysqlUuid;
    private String database = "dgl_p0_s2";
    private String fileRoot;
    private Set<String> managerRoleIds = Set.of();
    private boolean workersEnabled;
    private String recoveryScopeFile;
    private String recoveryScopeSha256;
    private String mockJdbcUrl;
    private String mockUsername;
    private String mockPassword;
    private String mockControlKey;
    private int mockMinimumValiditySeconds = 1;
    @Override public String toString() { return "Stage2Settings[REDACTED]"; }
}
