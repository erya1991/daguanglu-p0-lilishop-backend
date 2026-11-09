package cn.lili.event.impl;
import cn.lili.modules.community.p0.stage2.Stage2NativeBoundary;
import cn.lili.modules.order.order.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
class Stage3NativeEventBoundaryTest {
    @Test void oldVerificationEventCannotGenerateWeakCodesOrAfterSaleState(){
        var event=new VerificationOrderExecute();var orders=mock(OrderService.class);var items=mock(OrderItemService.class);
        ReflectionTestUtils.setField(event,"stage2Boundary",new Stage2NativeBoundary());ReflectionTestUtils.setField(event,"orderService",orders);ReflectionTestUtils.setField(event,"orderItemService",items);
        event.orderChange(null);verifyNoInteractions(orders,items);
    }
}
