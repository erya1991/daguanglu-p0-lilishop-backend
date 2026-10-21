package cn.lili.common.properties;

import lombok.Data;
import lombok.ToString;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * token过期配置
 *
 * @author Chopper
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "lili.jwt-setting")
public class JWTTokenProperties {


    /**
     * token默认过期时间
     */
    private long tokenExpireTime = 60;

    /** Standard Base64 HMAC key, at least 32 decoded bytes. No default. */
    @ToString.Exclude
    private String secretKey;
}
