package cn.lili.modules.community.p0.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.beans.factory.annotation.Qualifier;
import java.time.Clock;

@Configuration
@ConditionalOnProperty(name="p0.stage1.enabled", havingValue="true")
@EnableConfigurationProperties(Stage1Settings.class)
@org.springframework.scheduling.annotation.EnableScheduling
public class Stage1Configuration {
    @Bean("stage1Clock") public Clock stage1Clock() { return Clock.systemUTC(); }
}
