package cn.lili.modules.community.p0.eligibility;
import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.agreement.*;
import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
@RestController
@cn.lili.modules.community.p0.support.P0BusinessEnabled
public class EligibilityController {
    private final Stage1Context context;private final EligibilityService eligibility;private final AgreementService agreements;private final CommissionRuleService rules;
    public EligibilityController(Stage1Context context,EligibilityService eligibility,AgreementService agreements,CommissionRuleService rules){this.context=context;this.eligibility=eligibility;this.agreements=agreements;this.rules=rules;}
    @GetMapping(Stage1Routes.MANAGER+"/stores/{id}/eligibility") public Object manager(@PathVariable String id,@RequestParam(required=false) String categoryId,@RequestParam(required=false) Instant at){context.require(SessionDomain.MANAGER);return Stage1Responses.success(eligibility.evaluate(id,categoryId,at));}
    @GetMapping(Stage1Routes.STORE+"/eligibility") public Object merchant(@RequestParam(required=false) String categoryId){return Stage1Responses.success(eligibility.evaluate(context.require(SessionDomain.STORE).store(),categoryId,null));}
    @GetMapping(Stage1Routes.STORE+"/cooperation") public Object cooperation(){String store=context.require(SessionDomain.STORE).store();return Stage1Responses.success(map("agreements",agreements.storeList(store),"rules",rules.storeList(store),"eligibility",eligibility.evaluate(store,null,null)));}
    @GetMapping(Stage1Routes.STORE+"/agreements/{id}") public Object agreement(@PathVariable String id){context.require(SessionDomain.STORE);return Stage1Responses.success(agreements.detail(id));}
}
