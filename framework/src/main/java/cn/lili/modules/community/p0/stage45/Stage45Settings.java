package cn.lili.modules.community.p0.stage45;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@Data
@ConfigurationProperties("p0.stage45")
public class Stage45Settings {
    private boolean enabled;
    private boolean writesEnabled;
    private boolean workersEnabled;
    private boolean channelWritesEnabled;
    private String authorizationFile;
    @Override public String toString() { return "Stage45Settings[REDACTED]"; }
}
