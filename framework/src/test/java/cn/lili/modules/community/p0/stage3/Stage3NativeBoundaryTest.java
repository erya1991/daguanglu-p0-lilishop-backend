package cn.lili.modules.community.p0.stage3;
import cn.lili.modules.community.p0.stage2.Stage2NativeBoundary;
import cn.lili.modules.community.p0.support.Stage1Exception;
import cn.lili.modules.order.order.serviceimpl.OrderServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class Stage3NativeBoundaryTest {
    @Test void directLegacyServiceCallsFailBeforeAnyNativeSideEffect(){
        var nativeOrders=mock(OrderServiceImpl.class,CALLS_REAL_METHODS);ReflectionTestUtils.setField(nativeOrders,"stage2Boundary",new Stage2NativeBoundary());
        assertThrows(Stage1Exception.class,()->nativeOrders.delivery("1","X","1"));
        assertThrows(Stage1Exception.class,()->nativeOrders.fulfillOrder(null));
        assertThrows(Stage1Exception.class,()->nativeOrders.take("1","12345678"));
        assertThrows(Stage1Exception.class,()->nativeOrders.take("12345678"));
        assertThrows(Stage1Exception.class,()->nativeOrders.confirmVerification("12345678"));
        assertThrows(Stage1Exception.class,()->nativeOrders.previewVerification("12345678"));
        assertThrows(Stage1Exception.class,()->nativeOrders.complete("1"));
        assertThrows(Stage1Exception.class,()->nativeOrders.complete(null,"1"));
    }
}
