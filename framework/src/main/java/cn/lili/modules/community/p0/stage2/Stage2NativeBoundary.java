package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.support.Stage1Exception;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Stage2 never dispatches native marketing/payment/fulfillment writers. Other profiles are unchanged. */
@Component @ConditionalOnProperty(name="p0.stage2.enabled",havingValue="true")
public class Stage2NativeBoundary {
    public void rejectLegacyWrite(){throw new Stage1Exception(409,"STAGE2_GUARDED_TRADE_REQUIRED");}
}
