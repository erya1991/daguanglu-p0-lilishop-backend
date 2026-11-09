package cn.lili.modules.community.p0.catalog;
import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.agreement.AgreementService;
import cn.lili.modules.community.p0.eligibility.EligibilityService;
import cn.lili.modules.community.p0.support.*;
import cn.lili.modules.goods.service.GoodsSkuService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service
@cn.lili.modules.community.p0.support.P0BusinessEnabled
public class InventoryService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.lili.modules.community.p0.stage2.Stage2Inventory stage2Inventory;
    private final Stage1Db db;private final Stage1Context context;private final Stage1Mutations mutations;private final NativeGoodsProjector nativeGoods;private final EligibilityService eligibility;private final Stage1Settings settings;private final StringRedisTemplate redis;private final Clock clock;
    public InventoryService(Stage1Db db,Stage1Context context,Stage1Mutations mutations,NativeGoodsProjector nativeGoods,EligibilityService eligibility,Stage1Settings settings,StringRedisTemplate redis,@Qualifier("stage1Clock") Clock clock){this.db=db;this.context=context;this.mutations=mutations;this.nativeGoods=nativeGoods;this.eligibility=eligibility;this.settings=settings;this.redis=redis;this.clock=clock;}
    /** S2 batch adapter: the existing single-SKU path joins one outer transaction. */
    public Object stockBatch(String goodsId,Object body){
        Stage1Exception.require(settings.isStage2(),403,"STAGE2_ENTRANCE_DISABLED");
        var actor=context.require(SessionDomain.STORE);var f=new Fields(body).only("requestId","reason","items");String reason=f.text("reason",1,200,true);
        var selected=new TreeMap<String,Fields>(Comparator.comparingLong(Long::parseLong));
        for(Object item:f.list("items",1,100,true)){var line=new Fields(item).only("skuId","expectedVersion","targetQuantity");String sku=line.id("skuId",true);line.version();line.integer("targetQuantity",0,9999999,true);Stage1Exception.require(selected.put(sku,line)==null,422,"DUPLICATE_SKU");}
        return mutations.run("stock.batch:"+goodsId,body,actor.store(),()->{
            db.required("SELECT id FROM li_goods WHERE id=? AND store_id=? AND delete_flag=0",goodsId,actor.store());
            var result=new ArrayList<Object>();
            for(var item:selected.entrySet()){
                var row=sku(item.getKey(),actor.store());Stage1Exception.require(goodsId.equals(id(row,"goods_id")),404,"RESOURCE_NOT_FOUND");
                String child=hash(map("batch",f.requestId(),"goods",goodsId,"sku",item.getKey()));
                result.add(stock(item.getKey(),map("requestId",child,"expectedVersion",item.getValue().version(),"targetQuantity",item.getValue().integer("targetQuantity",0,9999999,true),"reason",reason)));
            }
            return map("goodsId",goodsId,"items",result);
        });
    }
    public Object stock(String id,Object body){
        var actor=context.require(SessionDomain.STORE);var f=new Fields(body).only("requestId","expectedVersion","targetQuantity","reason");long target=f.integer("targetQuantity",0,9999999,true);String reason=f.text("reason",1,200,true);
        return mutations.run("stock.set:"+id,body,actor.store(),()->{
            var row=sku(id,actor.store());long before=number(row,"quantity"),version=number(row,"inventory_version");Stage1Exception.require(version==f.version(),409,"VERSION_CONFLICT");safeInventory(row);
            if(settings.isStage2())stage2Inventory.manual(row,target);
            if(target>before){var goods=db.required("SELECT g.category_id,g.review_status,g.ever_listed,s.store_disable FROM li_p0_goods_control g JOIN li_store s ON s.id=g.store_id WHERE g.goods_id=?",row.get("goods_id"));Stage1Exception.require("OPEN".equals(id(goods,"store_disable")),403,"STORE_CLOSED");if(flag(goods,"ever_listed")||!"DRAFT".equals(id(goods,"review_status")))eligibility.requireBasic(actor.store(),id(goods,"category_id"));}
            db.cas(db.update("UPDATE li_p0_sku_control SET inventory_version=inventory_version+1 WHERE sku_id=? AND store_id=? AND inventory_version=?",id,actor.store(),version));nativeGoods.updateSku(id,id(row,"goods_id"),actor.store(),map("quantity",target));nativeGoods.summary(id(row,"goods_id"),actor.store());String journal=db.id();
            db.update("INSERT INTO li_p0_inventory_adjustment(id,sku_id,store_id,actor_id,request_id,before_quantity,after_quantity,delta_quantity,before_version,after_version,reason,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",journal,id,actor.store(),actor.id(),f.requestId(),before,target,target-before,version,version+1,reason,clock.instant());if(settings.isStage2())stage2Inventory.manualEvent(row,target);mutations.audit("stock.set",actor.store(),id,f.requestId(),version,version+1,reason);mutations.invalidated(actor.store(),id,"SKU_STOCK",version+1);return map("skuId",id,"quantity",target,"inventoryVersion",version+1,"adjustmentId",journal,"beforeQuantity",before,"deltaQuantity",target-before);
        });
    }
    public Object cost(String id,Object body){var actor=context.require(SessionDomain.STORE);var f=new Fields(body).only("requestId","expectedVersion","costCent","reason");long cost=f.integer("costCent",0,99999999,true);String reason=f.text("reason",1,200,true);return mutations.run("cost.set:"+id,body,actor.store(),()->{var row=sku(id,actor.store());db.cas(db.update("UPDATE li_p0_sku_control SET cost_version=cost_version+1,cost_provided=1 WHERE sku_id=? AND store_id=? AND cost_version=?",id,actor.store(),f.version()));nativeGoods.updateSku(id,id(row,"goods_id"),actor.store(),map("cost",money(cost)));nativeGoods.summary(id(row,"goods_id"),actor.store());mutations.audit("cost.set",actor.store(),id,f.requestId(),f.version(),f.version()+1,reason);mutations.invalidated(actor.store(),id,"SKU_COST",f.version()+1);return map("skuId",id,"costCent",cost,"costVersion",f.version()+1);});}
    public Map<String,Object> sku(String id,String store){return db.required("SELECT s.*,c.inventory_version,c.cost_version,c.cost_provided,c.weight_provided,c.enabled,c.submitted_once,c.historical_reference,c.source_run FROM li_goods_sku s JOIN li_p0_sku_control c ON c.sku_id=s.id AND c.goods_id=s.goods_id AND c.store_id=s.store_id WHERE s.id=? AND s.store_id=? AND s.delete_flag=0",id,store);}
    private void safeInventory(Map<String,Object> row){
        if(settings.isStage2()){Stage1Exception.require(stage2Inventory!=null,503,"INVENTORY_CONTEXT_UNAVAILABLE");stage2Inventory.requireOwned(row);return;}
        // Native PAID handling decrements Redis then copies it into the DB (StockUpdateExecute).
        // Stage1 owns only freshly created ordinary SKUs with that consumer disabled. Unknown legacy context is refused.
        Stage1Exception.require(settings.getRunId().equals(id(row,"source_run"))&&!flag(row,"historical_reference")&&"PHYSICAL_GOODS".equals(id(row,"goods_type")),409,"INVENTORY_OCCUPANCY_UNKNOWN");
        if(db.count("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name='li_order_item'")>0)Stage1Exception.require(db.count("SELECT COUNT(*) FROM li_order_item WHERE sku_id=?",row.get("id"))==0,409,"INVENTORY_OCCUPANCY_UNKNOWN");
        try{String cached=redis.opsForValue().get(GoodsSkuService.getStockCacheKey(id(row,"id")));if(cached!=null){try{Stage1Exception.require(Long.parseLong(cached)==number(row,"quantity"),409,"INVENTORY_OCCUPANCY_UNKNOWN");}catch(NumberFormatException unknown){throw new Stage1Exception(409,"INVENTORY_OCCUPANCY_UNKNOWN");}}}catch(org.springframework.dao.DataAccessException unavailable){throw new Stage1Exception(503,"INVENTORY_CONTEXT_UNAVAILABLE");}
    }
    public Object history(String id,int page,int size){var actor=context.require(SessionDomain.STORE);sku(id,actor.store());AgreementService.page(page,size);return map("total",db.count("SELECT COUNT(*) FROM li_p0_inventory_adjustment WHERE sku_id=?",id),"records",db.rows("SELECT * FROM li_p0_inventory_adjustment WHERE sku_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",id,size,(page-1)*size).stream().map(r->map("id",id(r,"id"),"beforeQuantity",r.get("before_quantity"),"afterQuantity",r.get("after_quantity"),"deltaQuantity",r.get("delta_quantity"),"beforeVersion",r.get("before_version"),"afterVersion",r.get("after_version"),"actorId",id(r,"actor_id"),"reason",r.get("reason"),"at",instant(r.get("created_at")))).toList());}
}
