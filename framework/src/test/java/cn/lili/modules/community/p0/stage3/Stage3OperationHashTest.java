package cn.lili.modules.community.p0.stage3;
import org.junit.jupiter.api.Test;import java.util.Map;import static org.junit.jupiter.api.Assertions.*;
class Stage3OperationHashTest {
 @Test void commandIdentityIncludesTargetOrderActionAndPayload(){var body=Map.of("expectedVersion",0,"operationId","same_op_123");assertEquals(Stage3Orders.operationHash("prepare","1",body),Stage3Orders.operationHash("prepare","1",body));assertNotEquals(Stage3Orders.operationHash("prepare","1",body),Stage3Orders.operationHash("prepare","2",body));assertNotEquals(Stage3Orders.operationHash("prepare","1",body),Stage3Orders.operationHash("dispatch","1",body));assertNotEquals(Stage3Orders.operationHash("prepare","1",body),Stage3Orders.operationHash("prepare","1",Map.of("expectedVersion",1,"operationId","same_op_123")));}
}
