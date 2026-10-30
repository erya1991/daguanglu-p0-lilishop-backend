package cn.lili.modules.community.p0.support;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import java.util.Set;

@Data
@ConfigurationProperties("p0.stage1")
public class Stage1Settings {
    private boolean enabled;
    private String environment = "TEST";
    private String mode = "MOCK";
    private String runId;
    private String mysqlUuid;
    private String database = "dgl_p0_s1";
    private String fileRoot;
    private Set<String> managerRoleIds = Set.of();
    private boolean realOutboundEnabled;
    /** Set only by the Stage2 compatibility assembly, never by a client request. */
    private boolean stage2;
    public boolean permitsMode(String candidate){return Set.of("DEV","TEST","PROD").contains(environment)&&Set.of("MOCK","REAL").contains(candidate)&&!(environment.equals("PROD")&&candidate.equals("MOCK"));}
}
