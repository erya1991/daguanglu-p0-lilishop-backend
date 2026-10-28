package cn.lili.modules.community.p0.agreement;

import cn.lili.modules.community.p0.support.Stage1Exception;
import java.util.Map;
import static cn.lili.modules.community.p0.support.Stage1Json.map;

/** Frozen commercial arithmetic only. No order, settlement, snapshot or external channel. */
public final class CommissionPreview {
    private CommissionPreview(){}
    public static Map<String,Object> calculate(long productPaidCent,long shippingCent,long rateBps){
        Stage1Exception.require(productPaidCent>=0&&productPaidCent<=99999999&&shippingCent>=0&&shippingCent<=99999999&&rateBps>=0&&rateBps<=10000,422,"PREVIEW_AMOUNT_INVALID");
        long commission=Math.multiplyExact(productPaidCent,rateBps)/10000;
        return map("productPaidCent",productPaidCent,"shippingCent",shippingCent,"rateBps",rateBps,"commissionCent",commission,"merchantProductCent",productPaidCent-commission,"baseCode",AgreementService.BASE,"roundCode",AgreementService.ROUND,"executionAllowed",false);
    }
}
