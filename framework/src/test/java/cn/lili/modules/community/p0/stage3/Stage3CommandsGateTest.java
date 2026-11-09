package cn.lili.modules.community.p0.stage3;
import cn.lili.modules.community.p0.support.*;
import cn.lili.modules.community.p0.stage2.*;
import cn.lili.modules.community.p0.fulfillment.FulfillmentService;
import org.junit.jupiter.api.Test;
import java.time.Clock;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class Stage3CommandsGateTest {
    @Test void disabledNormalCommandsDoNotTouchDatabaseIdentityOrInventory(){
        var db=mock(Stage1Db.class);var context=mock(Stage1Context.class);var tx=mock(Stage2Transactions.class);
        var orders=new Stage3Orders(db,context,mock(Stage3Authorization.class),mock(Stage3Eligibility.class),new Stage3Gate(new Stage3Settings(),new Stage2Settings()),tx,mock(FulfillmentService.class),Clock.systemUTC());
        for(String action:new String[]{"prepare","dispatch","delivered","ship","receive"})assertThrows(Stage1Exception.class,()->orders.command("1",action,Map.of()));
        verifyNoInteractions(db,context,tx);
    }
}
