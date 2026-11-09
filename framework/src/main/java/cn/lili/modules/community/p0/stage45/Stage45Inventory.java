package cn.lili.modules.community.p0.stage45;

import cn.lili.modules.community.p0.catalog.NativeGoodsProjector;
import cn.lili.modules.community.p0.stage2.*;
import cn.lili.modules.community.p0.support.*;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import cn.lili.modules.goods.service.GoodsSkuService;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service @ConditionalOnProperty(name="p0.stage45.enabled",havingValue="true")
public class Stage45Inventory {
    private final Stage45Data d;private final Stage2Inventory inventory;private final NativeGoodsProjector projector;private final StringRedisTemplate redis;private final Stage2Settings settings;
    private static final DefaultRedisScript<Long> PROJECT=new DefaultRedisScript<>("local old=redis.call('GET',KEYS[2]); if old and tonumber(old)>tonumber(ARGV[1]) then return 0 end; redis.call('SET',KEYS[1],ARGV[2]); redis.call('SET',KEYS[2],ARGV[1]); return 1",Long.class);
    public Stage45Inventory(Stage45Data d,Stage2Inventory inventory,NativeGoodsProjector projector,StringRedisTemplate redis,Stage2Settings settings){this.d=d;this.inventory=inventory;this.projector=projector;this.redis=redis;this.settings=settings;}
    public boolean one(){
        var pending=d.db.one("SELECT * FROM li_p0_s45_inventory_return WHERE status='PENDING' ORDER BY after_sale_id LIMIT 1");if(pending==null)return false;
        var order=d.orders.row(id(pending,"order_id"));
        try{d.locked(order,()->{
            var r=d.db.required("SELECT * FROM li_p0_s45_inventory_return WHERE after_sale_id=? FOR UPDATE",pending.get("after_sale_id"));if(!"PENDING".equals(id(r,"status")))return null;
            var sale=d.db.required("SELECT * FROM li_p0_s45_aftersale WHERE id=?",r.get("after_sale_id"));
            Stage1Exception.require("COMPLETE".equals(id(sale,"state"))&&!flag(sale,"delivered"),409,"INVENTORY_RETURN_SOURCE_INVALID");
            var item=d.item(order,id(r,"order_item_id"));var sku=inventory.lockedSku(id(item,"sku_id"));inventory.requireOwned(sku);
            var hold=d.db.required("SELECT * FROM li_p0_inventory_hold WHERE order_item_id=? AND status='CAPTURED' FOR UPDATE",item.get("id"));
            long already=d.db.count("SELECT COALESCE(SUM(quantity),0) FROM li_p0_s45_inventory_return WHERE order_item_id=? AND status IN ('APPLIED','PROJECT_PENDING')",item.get("id")),q=number(r,"quantity"),before=number(sku,"quantity"),held=inventory.held(id(item,"sku_id")),version=number(sku,"inventory_version");
            Stage1Exception.require(q>0&&already+q<=number(hold,"quantity")&&before+q+held<=9999999,409,"INVENTORY_RETURN_LIMIT");
            d.db.cas(d.db.update("UPDATE li_p0_sku_control SET inventory_version=inventory_version+1 WHERE sku_id=? AND inventory_version=?",sku.get("id"),version));
            d.db.update("UPDATE li_goods_sku SET quantity=? WHERE id=?",before+q,sku.get("id"));projector.summary(id(sku,"goods_id"),id(sku,"store_id"));
            d.db.update("INSERT INTO li_p0_inventory_event VALUES(?,?,?,'AFTERSALE_RETURN',?,?,?,?,?,?)",d.db.id(),sku.get("id"),order.get("trade_id"),before,before+q,held,held,version+1,d.clock.instant());
            d.db.update("UPDATE li_p0_s45_inventory_return SET status='PROJECT_PENDING',last_error=NULL,applied_at=? WHERE after_sale_id=?",d.clock.instant(),r.get("after_sale_id"));return null;
        });}catch(RuntimeException e){String code=e instanceof Stage1Exception s?s.code():"INVENTORY_RETURN_RETRY";d.db.update("UPDATE li_p0_s45_inventory_return SET last_error=? WHERE after_sale_id=?",code,pending.get("after_sale_id"));d.issue(id(order,"id"),"INVENTORY:"+id(pending,"after_sale_id"),code,map("afterSaleId",id(pending,"after_sale_id")));return false;}
        projectOne();return true;
    }
    public boolean projectOne(){
        var row=d.db.one("SELECT r.after_sale_id,i.sku_id,s.quantity,c.inventory_version FROM li_p0_s45_inventory_return r JOIN li_order_item i ON i.id=r.order_item_id JOIN li_goods_sku s ON s.id=i.sku_id JOIN li_p0_sku_control c ON c.sku_id=s.id WHERE r.status='PROJECT_PENDING' ORDER BY r.after_sale_id LIMIT 1");if(row==null)return false;
        Long result=redis.execute(PROJECT,List.of(GoodsSkuService.getStockCacheKey(id(row,"sku_id")),"stage45:"+settings.getRunId()+":stock-version:"+id(row,"sku_id")),String.valueOf(number(row,"inventory_version")),String.valueOf(number(row,"quantity")));
        Stage1Exception.require(result!=null,503,"CACHE_PROJECTION_UNKNOWN");d.db.update("UPDATE li_p0_s45_inventory_return SET status='APPLIED' WHERE after_sale_id=? AND status='PROJECT_PENDING'",row.get("after_sale_id"));return true;
    }
}
