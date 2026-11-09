package cn.lili.modules.community.p0.stage45;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(name="p0.stage45.enabled", havingValue="true")
@EnableConfigurationProperties(Stage45Settings.class)
public class Stage45Configuration {}
