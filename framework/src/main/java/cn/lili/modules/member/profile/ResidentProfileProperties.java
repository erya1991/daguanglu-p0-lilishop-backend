package cn.lili.modules.member.profile;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import static cn.lili.modules.member.profile.ResidentProfileException.Code.*;

@Getter @Setter @Component
@ConfigurationProperties(prefix = "lili.stage0-resident-profile")
public class ResidentProfileProperties {
    private boolean schemaReady;
    private boolean historyReady;
    private boolean uploadEnabled;
    private boolean phoneEnabled;
    public void requireSchema() { if (!schemaReady) throw new ResidentProfileException(B04_PENDING); }
    public void requireHistory() { requireSchema(); if (!historyReady) throw new ResidentProfileException(HISTORY_PENDING); }
}
