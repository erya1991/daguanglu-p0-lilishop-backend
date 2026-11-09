package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.catalog.InventoryService;
import cn.lili.modules.community.p0.support.Stage1Responses;
import cn.lili.modules.community.p0.support.Stage1Routes;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

/** Approved multi-SKU atomic adjustment; absent from the Stage1 runtime. */
@RestController
@ConditionalOnProperty(name="p0.stage2.enabled",havingValue="true")
public class Stage2StockController {
    private final InventoryService inventory;
    public Stage2StockController(InventoryService inventory){this.inventory=inventory;}
    @PutMapping(Stage1Routes.STORE+"/goods/{id}/stock")
    public Object stock(@PathVariable("id") String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(inventory.stockBatch(id,body));}
}
