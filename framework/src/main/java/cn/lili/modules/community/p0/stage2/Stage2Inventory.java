package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.catalog.NativeGoodsProjector;
import cn.lili.modules.community.p0.support.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Database A + durable HELD ledger. All callers hold the common Store/SKU lock order. */
@Service @ConditionalOnProperty(name="p0.stage2.enabled",havingValue="true")
public class Stage2Inventory {
    private final Stage1Db db;private final Stage2Settings settings;private final NativeGoodsProjector projector;private final Clock clock;
    public Stage2Inventory(Stage1Db db,Stage2Settings settings,NativeGoodsProjector projector,@Qualifier("stage1Clock")Clock clock){this.db=db;this.settings=settings;this.projector=projector;this.clock=clock;}
    public long held(String sku){return db.count("SELECT COALESCE(SUM(quantity),0) FROM li_p0_inventory_hold WHERE sku_id=? AND status='HELD'",sku);}
    public void requireOwned(Map<String,Object> row){
        String sku=id(row,"id");boolean source=settings.getRunId().equals(id(row,"source_run"))||db.count("SELECT COUNT(*) FROM li_p0_s2_sku_scope WHERE sku_id=? AND source_run=? AND target_run=?",sku,row.get("source_run"),settings.getRunId())==1;
        Stage1Exception.require(source&&!flag(row,"historical_reference")&&"PHYSICAL_GOODS".equals(id(row,"goods_type")),409,"INVENTORY_OCCUPANCY_UNKNOWN");
        Stage1Exception.require(db.count("SELECT COUNT(*) FROM li_order_item i WHERE i.sku_id=? AND NOT EXISTS(SELECT 1 FROM li_p0_inventory_hold h WHERE h.order_item_id=i.id AND h.sku_id=i.sku_id)",sku)==0,409,"INVENTORY_OCCUPANCY_UNKNOWN");
    }
    public Map<String,Object> lockedSku(String sku){return db.required("SELECT s.*,c.inventory_version,c.source_run,c.historical_reference,c.enabled FROM li_goods_sku s JOIN li_p0_sku_control c ON c.sku_id=s.id AND c.goods_id=s.goods_id AND c.store_id=s.store_id WHERE s.id=? AND s.delete_flag=0 FOR UPDATE",sku);}
    public void manual(Map<String,Object> row,long target){requireOwned(row);long h=held(id(row,"id"));Stage1Exception.require(target+h<=9_999_999,409,"INVENTORY_RELEASE_SPACE_REQUIRED");}
    public void manualEvent(Map<String,Object> row,long target){long h=held(id(row,"id"));event(id(row,"id"),null,"MANUAL",number(row,"quantity"),target,h,h,number(row,"inventory_version")+1);}
    public void reserve(String trade,String item,String sku,long quantity){
        var row=lockedSku(sku);requireOwned(row);long a=number(row,"quantity"),h=held(sku),version=number(row,"inventory_version");Stage1Exception.require(flag(row,"enabled")&&a>=quantity,409,"OUT_OF_STOCK");
        db.update("INSERT INTO li_p0_inventory_hold VALUES(?,?,?,?,?,'HELD',0,?,?)",db.id(),trade,item,sku,quantity,clock.instant(),clock.instant());
        db.cas(db.update("UPDATE li_p0_sku_control SET inventory_version=inventory_version+1 WHERE sku_id=? AND inventory_version=?",sku,version));
        db.update("UPDATE li_goods_sku SET quantity=? WHERE id=?",a-quantity,sku);projector.summary(id(row,"goods_id"),id(row,"store_id"));event(sku,trade,"RESERVE",a,a-quantity,h,h+quantity,version+1);
    }
    public void requireTradeHeld(String trade){
        Stage1Exception.require(db.count("SELECT COUNT(*) FROM li_order_item i JOIN li_order o ON o.sn=i.order_sn JOIN li_p0_trade_state t ON t.trade_id=? JOIN li_trade n ON n.id=t.trade_id AND n.sn=o.trade_sn LEFT JOIN li_p0_inventory_hold h ON h.order_item_id=i.id AND h.trade_id=t.trade_id WHERE h.id IS NULL OR h.status<>'HELD' OR h.quantity<>i.num",trade)==0,409,"HOLD_INCOMPLETE");
        Stage1Exception.require(db.count("SELECT COUNT(*) FROM li_p0_inventory_hold WHERE trade_id=? AND status='HELD'",trade)>0,409,"HOLD_INCOMPLETE");
    }
    public void settle(String trade,boolean paid){
        var holds=db.rows("SELECT * FROM li_p0_inventory_hold WHERE trade_id=? ORDER BY sku_id,id",trade);
        for(var candidate:holds){String sku=id(candidate,"sku_id");var row=lockedSku(sku);var hold=db.required("SELECT * FROM li_p0_inventory_hold WHERE id=? FOR UPDATE",candidate.get("id"));
            if(!"HELD".equals(id(hold,"status"))){Stage1Exception.require((paid?"CAPTURED":"RELEASED").equals(id(hold,"status")),409,"HOLD_TERMINAL_CONFLICT");continue;}
            long q=number(hold,"quantity"),a=number(row,"quantity"),h=held(sku),version=number(row,"inventory_version"),after=paid?a:a+q;
            Stage1Exception.require(h>=q&&after+h-q<=9_999_999,409,"INVENTORY_INVARIANT");
            db.cas(db.update("UPDATE li_p0_inventory_hold SET status=?,version=version+1,updated_at=? WHERE id=? AND status='HELD'",paid?"CAPTURED":"RELEASED",clock.instant(),hold.get("id")));
            db.cas(db.update("UPDATE li_p0_sku_control SET inventory_version=inventory_version+1 WHERE sku_id=? AND inventory_version=?",sku,version));
            if(!paid){db.update("UPDATE li_goods_sku SET quantity=? WHERE id=?",after,sku);projector.summary(id(row,"goods_id"),id(row,"store_id"));}
            event(sku,trade,paid?"CAPTURE":"RELEASE",a,after,h,h-q,version+1);
        }
    }
    private void event(String sku,String trade,String type,long beforeA,long afterA,long beforeH,long afterH,long version){db.update("INSERT INTO li_p0_inventory_event VALUES(?,?,?,?,?,?,?,?,?,?)",db.id(),sku,trade,type,beforeA,afterA,beforeH,afterH,version,clock.instant());db.update("INSERT INTO li_p0_s2_task(id,run_id,task_type,target_key,next_attempt_at,created_at) VALUES(?,?,'INVENTORY_PROJECTION',?,?,?) ON DUPLICATE KEY UPDATE generation=generation+1,status=IF(status='DONE','PENDING',status),next_attempt_at=LEAST(next_attempt_at,VALUES(next_attempt_at))",db.id(),settings.getRunId(),sku,clock.instant(),clock.instant());}
}
