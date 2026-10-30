package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.support.Stage1Responses;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping(Stage2Routes.BASE)
@ConditionalOnProperty(name="p0.stage2.enabled",havingValue="true")
public class Stage2CheckoutController {
    private final Stage2QuoteService quotes;private final Stage2TradeService trades;private final Stage2PaymentService payments;
    public Stage2CheckoutController(Stage2QuoteService quotes,Stage2TradeService trades,Stage2PaymentService payments){this.quotes=quotes;this.trades=trades;this.payments=payments;}
    @PostMapping("/checkout/options") public Object options(@RequestBody Object body){return Stage1Responses.success(quotes.options(body));}
    @PostMapping("/checkout/quotes") public Object quote(@RequestBody Object body){return Stage1Responses.success(quotes.quote(body));}
    @PostMapping("/checkout/intents") public Object create(@RequestBody Object body){return Stage1Responses.success(trades.create(body));}
    @GetMapping("/checkout/intents/{id}") public Object intent(@PathVariable String id){return Stage1Responses.success(trades.intent(id));}
    @GetMapping("/pending-transactions") public Object pending(@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize){return Stage1Responses.success(trades.pending(page,pageSize));}
    @GetMapping("/trades/{id}/summary") public Object summary(@PathVariable String id){return Stage1Responses.success(trades.summary(id));}
    @PostMapping("/trades/{id}/payment/prepare") public Object prepare(@PathVariable String id,@RequestBody Object body){return Stage1Responses.success(payments.prepare(id,body));}
    @PostMapping("/trades/{id}/payment/launch") public Object launch(@PathVariable String id,@RequestBody Object body){return Stage1Responses.success(payments.launch(id,body));}
    @PostMapping("/trades/{id}/payment/refresh") public Object refresh(@PathVariable String id){return Stage1Responses.success(payments.refresh(id));}
    @PostMapping("/trades/{id}/cancel") public Object cancel(@PathVariable String id,@RequestBody Object body){return Stage1Responses.success(payments.cancel(id,body));}
}
