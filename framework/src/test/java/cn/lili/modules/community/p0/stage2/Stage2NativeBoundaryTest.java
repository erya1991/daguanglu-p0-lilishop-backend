package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.support.Stage1Exception;
import cn.lili.modules.goods.serviceimpl.GoodsSkuServiceImpl;
import cn.lili.modules.order.order.serviceimpl.TradeServiceImpl;
import cn.lili.modules.payment.serviceimpl.PaymentServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class Stage2NativeBoundaryTest {
    @Test void legacyPaymentStopsBeforeAnyCashierOrNativeTradeSideEffect(){
        var guard=new Stage2NativeBoundary();var cashier=new PaymentServiceImpl();ReflectionTestUtils.setField(cashier,"stage2Boundary",guard);
        assertEquals("STAGE2_GUARDED_TRADE_REQUIRED",assertThrows(Stage1Exception.class,()->cashier.success(null)).code());
        assertThrows(Stage1Exception.class,()->cashier.adminPaySuccess(null));
        var trade=new TradeServiceImpl();ReflectionTestUtils.setField(trade,"stage2Boundary",guard);assertThrows(Stage1Exception.class,()->trade.payTrade("S2_TEST","legacy","none"));
    }
    @Test void oldDatabaseAndRedisStockWritersStopBeforeTheirDependencies(){
        var sku=new GoodsSkuServiceImpl();ReflectionTestUtils.setField(sku,"stage2Boundary",new Stage2NativeBoundary());
        assertThrows(Stage1Exception.class,()->sku.updateStock("1",1));
        assertThrows(Stage1Exception.class,()->sku.updateStock("1",1,"ADD"));
        assertThrows(Stage1Exception.class,()->sku.updateGoodsStock(java.util.List.of()));
    }
    @Test void everyLegacyCartReadAndMutationRequiresPersistentCommands(){var settings=new Stage2Settings();settings.setEnabled(true);var cart=new cn.lili.modules.order.cart.service.CartServiceImpl();ReflectionTestUtils.setField(cart,"stage2Settings",settings);assertThrows(Stage1Exception.class,cart::clean);assertThrows(Stage1Exception.class,()->cart.delete(new String[]{"1"}));assertThrows(Stage1Exception.class,()->cart.checked("1",true));assertThrows(Stage1Exception.class,cart::getAllTradeDTO);assertThrows(Stage1Exception.class,()->cart.getCartNum(false));}
}
