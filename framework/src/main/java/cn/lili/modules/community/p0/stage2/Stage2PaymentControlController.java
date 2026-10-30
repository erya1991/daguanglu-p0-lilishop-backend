package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.support.Stage1Responses;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/internal/p0/s2")
@ConditionalOnProperty(name="p0.stage2.enabled",havingValue="true")
public class Stage2PaymentControlController {
    private final PersistentPaymentMock mock;private final Stage2PaymentService payments;
    public Stage2PaymentControlController(PersistentPaymentMock mock,Stage2PaymentService payments){this.mock=mock;this.payments=payments;}
    @PostMapping("/mock/scenario") public Object scenario(@RequestBody Object body,@RequestHeader(value="X-S2-Mock-Control",required=false)String key){return Stage1Responses.success(mock.scenario(body,key));}
    @PostMapping("/payment/notification") public Object notification(@RequestBody Object body){payments.notification(body);return Stage1Responses.success(null);}
}
