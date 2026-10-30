package cn.lili.modules.community.p0.stage2;
import cn.lili.modules.community.p0.support.Stage1Exception;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class Stage2MoneyTest {
    @Test void orderLevelCommissionAndLargeAmountsStayExact(){assertEquals(1,Stage2Money.commission(99+99,100));assertEquals(66,Stage2Money.commission(1001+1001,333));assertEquals(9_899_999_901L,Stage2Money.item(99_999_999,99));assertEquals(9_899_999_901L,Stage2Money.commission(9_899_999_901L,10000));assertEquals(0,Stage2Money.commission(1001,0));}
    @Test void invalidOrUnrepresentableAmountsAreRejected(){assertThrows(Stage1Exception.class,()->Stage2Money.item(0,1));assertThrows(Stage1Exception.class,()->Stage2Money.item(1,100));assertThrows(Stage1Exception.class,()->Stage2Money.add(Stage2Money.MAX_SAFE_CENT,1));assertThrows(Stage1Exception.class,()->Stage2Money.commission(1,10001));}
}
