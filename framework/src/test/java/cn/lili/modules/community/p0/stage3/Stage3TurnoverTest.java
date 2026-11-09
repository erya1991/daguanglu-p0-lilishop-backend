package cn.lili.modules.community.p0.stage3;
import cn.lili.modules.community.p0.support.Stage1Exception;
import org.junit.jupiter.api.Test;
import java.time.LocalDate;
import java.util.List;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
class Stage3TurnoverTest {
    private Object sub(String id,String store,long cents,String at){return map("orderId",id,"storeId",store,"amountCent",cents,"paidAt",at);}
    @Test void shanghaiBoundaryAndSuborderAmountIgnoreTradeTotalAndOtherStores(){
        var total=new Stage3Turnover("4",LocalDate.parse("2026-10-31"));
        total.add(object(map("amountCent",999999,"subOrders",List.of(sub("1","4",1001,"2026-10-30T16:00:00Z"),sub("2","4",2500,"2026-10-31T15:59:59Z"),sub("3","4",500,"2026-10-31T16:00:00Z"),sub("4","5",9999,"2026-10-31T01:00:00Z")))));
        assertEquals(3501,total.amount());
    }
    @Test void duplicatePaidEventsDoNotDuplicateTurnoverAndContradictionsAreNotZero(){
        var total=new Stage3Turnover("4",LocalDate.parse("2026-10-31"));var facts=object(map("subOrders",List.of(sub("1","4",1001,"2026-10-31T01:00:00Z"))));
        total.add(facts);total.add(facts);assertEquals(1001,total.amount());
        assertThrows(Stage1Exception.class,()->total.add(object(map("subOrders",List.of(sub("1","4",1002,"2026-10-31T01:00:00Z"))))));
    }
}
