package cn.lili.modules.community.p0.catalog;
import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.eligibility.EligibilityService;
import cn.lili.modules.community.p0.support.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.transaction.annotation.Transactional;
import java.time.Clock;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
@RestController
@cn.lili.modules.community.p0.support.P0BusinessEnabled
public class MerchantWorkbench {
    private final Stage1Context context;private final Stage1Access access;private final EligibilityService eligibility;private final GoodsManagementService goods;private final Clock clock;
    public MerchantWorkbench(Stage1Context context,Stage1Access access,EligibilityService eligibility,GoodsManagementService goods,@Qualifier("stage1Clock") Clock clock){this.context=context;this.access=access;this.eligibility=eligibility;this.goods=goods;this.clock=clock;}
    @GetMapping(Stage1Routes.STORE+"/workbench")
    @Transactional(readOnly=true,isolation=org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    public Object summary(){var actor=context.require(SessionDomain.STORE);var counts=goods.counts(actor.store());return Stage1Responses.success(map("storeId",actor.store(),"context",access.contextView(actor.user()),"eligibility",eligibility.evaluate(actor.store(),null,null),"goods",counts,"evaluatedAt",clock.instant()));}
}
