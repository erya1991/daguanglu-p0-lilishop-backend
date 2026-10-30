package cn.lili.modules.community.p0.catalog;
import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@cn.lili.modules.community.p0.support.P0BusinessEnabled
public class GoodsManagementController {
    private final GoodsManagementService goods;private final InventoryService inventory;
    public GoodsManagementController(GoodsManagementService goods,InventoryService inventory){this.goods=goods;this.inventory=inventory;}
    @GetMapping({Stage1Routes.MANAGER+"/goods",Stage1Routes.STORE+"/goods"}) public Object list(@RequestParam(name="targetStore",required=false) String storeId,@RequestParam(required=false) String status,@RequestParam(required=false) String search,@RequestParam(required=false) Boolean forced,@RequestParam(required=false) String marketStatus,@RequestParam(required=false) String stockState,@RequestParam(required=false) String saleability,@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(goods.list(storeId,status,search,forced,marketStatus,stockState,saleability,pageNumber,pageSize));}
    @GetMapping(Stage1Routes.MANAGER+"/goods/reviews") public Object reviews(@RequestParam(name="targetStore",required=false) String storeId,@RequestParam(required=false) String search,@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(goods.list(storeId,"SUBMITTED",search,null,pageNumber,pageSize));}
    @GetMapping({Stage1Routes.MANAGER+"/goods/{id}",Stage1Routes.STORE+"/goods/{id}"}) public Object detail(@PathVariable String id){return Stage1Responses.success(goods.detail(id));}
    @PostMapping(Stage1Routes.STORE+"/goods") public Object create(@RequestBody Map<String,Object> body){return Stage1Responses.success(goods.save(null,body));}
    @PutMapping(Stage1Routes.STORE+"/goods/{id}/content") public Object save(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(goods.save(id,body));}
    @PostMapping(Stage1Routes.STORE+"/goods/{id}/{action:submit|list|unlist}") public Object command(@PathVariable String id,@PathVariable String action,@RequestBody Map<String,Object> body){return Stage1Responses.success(goods.merchantCommand(id,body,action));}
    @PostMapping(Stage1Routes.MANAGER+"/goods/{id}/{action:approve|reject|force-off|release}") public Object review(@PathVariable String id,@PathVariable String action,@RequestBody Map<String,Object> body){return Stage1Responses.success(goods.managerCommand(id,body,action));}
    @PutMapping(Stage1Routes.STORE+"/skus/{id}/stock") public Object stock(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(inventory.stock(id,body));}
    @PutMapping(Stage1Routes.STORE+"/skus/{id}/cost") public Object cost(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(inventory.cost(id,body));}
    @GetMapping(Stage1Routes.STORE+"/skus/{id}/stock-history") public Object history(@PathVariable String id,@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(inventory.history(id,pageNumber,pageSize));}
}
