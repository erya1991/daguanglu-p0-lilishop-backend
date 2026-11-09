package cn.lili.modules.community.p0.stage3;
import cn.lili.modules.community.p0.support.Stage1Exception;import org.junit.jupiter.api.Test;import org.springframework.data.redis.core.StringRedisTemplate;import java.util.List;import static org.junit.jupiter.api.Assertions.*;import static org.mockito.Mockito.*;
class Stage3AttemptLimiterTest {
 @Test void missingOrUnavailableLimiterFailsClosed(){var redis=mock(StringRedisTemplate.class);var error=assertThrows(Stage1Exception.class,()->new Stage3AttemptLimiter(redis).begin(List.of("unit:actor","unit:store","unit:ip")));assertEquals(503,error.status());assertEquals("VERIFICATION_LIMIT_UNAVAILABLE",error.code());}
}
