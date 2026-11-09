package cn.lili.modules.community.p0.stage3;
import cn.lili.modules.community.p0.stage2.Stage2Settings;
import cn.lili.modules.community.p0.support.*;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import java.time.Clock;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class Stage3VerificationGateTest {
    @Test void lookupAndConfirmStayClosedWithoutAnyCredentialOrRedisMutation(){
        var db=mock(Stage1Db.class);var context=mock(Stage1Context.class);var redis=mock(StringRedisTemplate.class);var settings=new Stage2Settings();
        var service=new Stage3Verification(db,context,mock(Stage3Authorization.class),mock(Stage3Orders.class),mock(Stage3Eligibility.class),new Stage3Gate(new Stage3Settings(),settings),redis,settings,Clock.systemUTC());
        assertThrows(Stage1Exception.class,()->service.lookup(Map.of("token","NEVER_ISSUED","pointId","1")));
        assertThrows(Stage1Exception.class,()->service.confirm(Map.of()));
        verifyNoInteractions(db,context,redis);
    }
}
