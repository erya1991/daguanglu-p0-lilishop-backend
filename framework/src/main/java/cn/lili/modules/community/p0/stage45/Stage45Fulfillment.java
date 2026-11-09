package cn.lili.modules.community.p0.stage45;

import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Read/lock hook for the existing Stage3 pipeline; no dependency back to Stage3 services. */
@Service @ConditionalOnProperty(name="p0.stage45.enabled",havingValue="true")
public class Stage45Fulfillment {
    private final Stage1Db db;
    public Stage45Fulfillment(Stage1Db db){this.db=db;}
    public String block(String order) {
        if(db.count("SELECT COUNT(*) FROM li_p0_s45_order WHERE order_id=? AND fulfillment_ended=1",order)>0)return "FULFILLMENT_ENDED_REFUNDED";
        if(db.count("SELECT COUNT(*) FROM li_p0_s45_aftersale WHERE order_id=? AND (state IN ('APPLY','WAIT_RETURN','WAIT_RECEIVE','WAIT_FUNDS') OR dispute IN ('OPEN','NEED_EVIDENCE'))",order)>0)return "ACTIVE_AFTERSALE";
        return null;
    }
    public void require(String order){String reason=block(order);Stage1Exception.require(reason==null,409,reason==null?"FULFILLMENT_BLOCKED":reason);}
    public JsonArray items(Map<String,Object> order) {
        var original=object(order.get("payload_json")).getAsJsonObject("order").getAsJsonArray("items");var result=new JsonArray();
        for(var element:original){
            var item=element.getAsJsonObject().deepCopy();
            var nativeItem=db.required("SELECT id,num FROM li_order_item WHERE order_sn=? AND sku_id=? AND delete_flag=0",order.get("sn"),item.get("skuId").getAsString());
            long refunded=db.count("SELECT COALESCE(SUM(quantity),0) FROM li_p0_s45_aftersale WHERE item_id=? AND delivered=0 AND state='COMPLETE'",nativeItem.get("id"));
            long originalQuantity=number(nativeItem,"num");
            Stage1Exception.require(refunded<=originalQuantity,409,"FULFILLMENT_QUANTITY_CONFLICT");
            item.addProperty("orderItemId",id(nativeItem,"id"));item.addProperty("originalQuantity",originalQuantity);item.addProperty("refundedQuantity",refunded);item.addProperty("quantity",originalQuantity-refunded);
            if(originalQuantity>refunded)result.add(item);
        }
        return result;
    }
}
