package cn.lili.modules.community.p0.agreement;
import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
@RequestMapping(Stage1Routes.MANAGER)
public class ManagerCooperationController {
    private final AgreementService agreements;private final CommissionRuleService rules;
    public ManagerCooperationController(AgreementService agreements,CommissionRuleService rules){this.agreements=agreements;this.rules=rules;}
    @GetMapping("/agreements") public Object agreements(@RequestParam(name="targetStore",required=false) String storeId,@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(agreements.list(storeId,pageNumber,pageSize));}
    @GetMapping("/agreements/{id}") public Object agreement(@PathVariable String id){return Stage1Responses.success(agreements.detail(id));}
    @GetMapping("/agreements/{id}/versions") public Object agreementVersions(@PathVariable String id){return Stage1Responses.success(agreements.versions(id));}
    @PostMapping("/agreements") public Object createAgreement(@RequestBody Map<String,Object> body){return Stage1Responses.success(agreements.create(body));}
    @PutMapping("/agreements/{id}/draft") public Object saveAgreement(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(agreements.save(id,body));}
    @PostMapping("/agreements/{id}/confirm") public Object confirm(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(agreements.command(id,body,"confirm"));}
    @PostMapping("/agreements/{id}/cancel") public Object cancel(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(agreements.command(id,body,"cancel"));}
    @PostMapping("/agreements/{id}/terminate") public Object terminate(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(agreements.command(id,body,"terminate"));}
    @GetMapping("/commission-rules") public Object rules(@RequestParam(name="targetStore",required=false) String storeId,@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(rules.list(storeId,pageNumber,pageSize));}
    @GetMapping("/commission-rules/{id}") public Object rule(@PathVariable String id){return Stage1Responses.success(rules.detail(id));}
    @GetMapping("/commission-rules/{id}/versions") public Object ruleVersions(@PathVariable String id){return Stage1Responses.success(rules.versions(id));}
    @GetMapping("/commission-rules/{id}/preview") public Object preview(@PathVariable String id,@RequestParam long productPaidCent,@RequestParam long shippingCent){return Stage1Responses.success(rules.preview(id,productPaidCent,shippingCent));}
    @PostMapping("/commission-rules") public Object createRule(@RequestBody Map<String,Object> body){return Stage1Responses.success(rules.create(body));}
    @PutMapping("/commission-rules/{id}/draft") public Object saveRule(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(rules.save(id,body));}
    @PostMapping("/commission-rules/{id}/publish") public Object publish(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(rules.command(id,body,true));}
    @PostMapping("/commission-rules/{id}/disable") public Object disable(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(rules.command(id,body,false));}
}
