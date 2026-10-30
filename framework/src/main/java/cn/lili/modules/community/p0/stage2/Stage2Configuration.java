package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.support.Stage1Settings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;

@Configuration
@ConditionalOnProperty(name="p0.stage2.enabled", havingValue="true")
@EnableConfigurationProperties(Stage2Settings.class)
public class Stage2Configuration {
    /** Compatibility view for existing shared supply services. No p0.stage1 resource is reused. */
    @Bean @Primary
    public Stage1Settings stage2SharedBusinessSettings(Stage2Settings source) {
        var target = new Stage1Settings();
        target.setStage2(true);
        target.setEnvironment(source.getEnvironment());
        target.setMode(source.getMode());
        target.setRunId(source.getRunId());
        target.setMysqlUuid(source.getMysqlUuid());
        target.setDatabase(source.getDatabase());
        target.setFileRoot(source.getFileRoot());
        target.setManagerRoleIds(source.getManagerRoleIds());
        target.setRealOutboundEnabled(false);
        return target;
    }
}
