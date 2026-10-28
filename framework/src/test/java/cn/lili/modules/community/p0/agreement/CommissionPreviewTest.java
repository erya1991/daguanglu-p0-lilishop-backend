package cn.lili.modules.community.p0.agreement;
import cn.lili.modules.community.p0.support.Stage1Exception;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class CommissionPreviewTest {
    @Test void bnd004ExcludesShippingFloorsSmallAmountsAndConservesProductAmount(){
        var result=CommissionPreview.calculate(10001,500,333);assertEquals(333L,result.get("commissionCent"));assertEquals(9668L,result.get("merchantProductCent"));assertEquals(false,result.get("executionAllowed"));
        for(int rate:new int[]{0,1,9999,10000}){var small=CommissionPreview.calculate(1,99999999,rate);assertEquals(rate==10000?1L:0L,small.get("commissionCent"));assertEquals(1L,(long)small.get("commissionCent")+(long)small.get("merchantProductCent"));}
        assertEquals(99999999L,CommissionPreview.calculate(99999999,99999999,10000).get("commissionCent"));
        for(long[] invalid:new long[][]{{-1,0,0},{100000000,0,0},{1,-1,0},{1,100000000,0},{1,0,-1},{1,0,10001},{Long.MAX_VALUE,0,10000}})assertThrows(Stage1Exception.class,()->CommissionPreview.calculate(invalid[0],invalid[1],invalid[2]));
    }
}
