package cn.lili.modules.community.p0.stage2;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.Map;

/** Read-only A/H projection in the same SQL statement as native quantity/version. */
@Component
@ConditionalOnProperty(name={"p0.stage2.enabled","p0.stage2.web-ui-enabled"},havingValue="true")
public class Stage2StockProjection {
    private final String run;
    public Stage2StockProjection(Stage2Settings settings){run=settings.getRunId();if(run==null||!run.matches(settings.isUatResourceMode()?"UAT_[0-9]{17}":settings.isStage45ResourceMode()?"S45_[0-9]{8}_[0-9]{6}":settings.isStage3ResourceMode()?"S3_[0-9]{8}_[0-9]{6}":"S2_[0-9]{8}_[0-9]{6}"))throw new IllegalArgumentException("Owned run required for selected resource mode");if(settings.isStage3ResourceMode()&&(!"dgl_p0_s3".equals(settings.getDatabase())||!"TEST".equals(settings.getEnvironment())||!"MOCK".equals(settings.getMode())))throw new IllegalArgumentException("Stage3 isolated resources required");}
    public String columns(){return ",CASE WHEN c.source_run='"+run+"' THEN (SELECT COALESCE(SUM(h.quantity),0) FROM li_p0_inventory_hold h WHERE h.sku_id=s.id AND h.status='HELD') ELSE NULL END AS held_quantity";}
    public void apply(Map<String,Object> target,Map<String,Object> row){target.put("heldQuantity",row.get("held_quantity"));}
}
