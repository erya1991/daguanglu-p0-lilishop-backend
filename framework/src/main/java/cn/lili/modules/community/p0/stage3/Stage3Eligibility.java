package cn.lili.modules.community.p0.stage3;

import cn.lili.modules.community.p0.stage2.*;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Read-only SC23 checks; no cache of permanent eligibility and no inventory/payment repairs. */
@Service @ConditionalOnProperty(name="p0.stage3.enabled",havingValue="true")
public class Stage3Eligibility {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private org.springframework.beans.factory.ObjectProvider<cn.lili.modules.community.p0.stage45.Stage45PaidEvidence> stage45;
    private final Stage1Db db;private final PersistentPaymentMock channel;private final Stage2Settings settings;
    public Stage3Eligibility(Stage1Db db,PersistentPaymentMock channel,Stage2Settings settings){this.db=db;this.channel=channel;this.settings=settings;}
    public record Result(boolean eligible,String reason,JsonObject paid) { @Override public String toString(){return "Stage3Eligibility["+eligible+","+reason+"]";} }
    public Result inspect(String trade){try{var paid=trustedPaid(trade);requireConverged(trade,paid);return new Result(true,null,paid);}catch(Stage1Exception e){if(e.status()>=500)throw e;return new Result(false,e.code(),null);}catch(IllegalArgumentException|NullPointerException e){return new Result(false,"SC23_EVIDENCE_INCOMPLETE",null);}}
    public JsonObject require(String trade){var paid=trustedPaid(trade);requireConverged(trade,paid);return paid;}
    /** D5: full trustworthy paid facts, independently of local inventory/order convergence. */
    public JsonObject trustedPaid(String trade){
        var state=db.required("SELECT * FROM li_p0_trade_state WHERE trade_id=?",trade);
        var evidence=stage45==null?null:stage45.getIfAvailable();String mode=id(state,"mode");
        check(settings.getMode().equals(mode)&&("MOCK".equals(mode)||"REAL".equals(mode)&&evidence!=null),"SC23_MODE_MISMATCH");
        check(db.count("SELECT COUNT(*) FROM li_p0_s2_member_scope WHERE member_id=? AND run_id=? AND permission='TRANSACT'",state.get("member_id"),settings.getRunId())==1,"SC23_SCOPE_MISMATCH");
        var pay=db.required("SELECT * FROM li_p0_payment WHERE trade_id=?",trade);
        check("SUCCEEDED".equals(id(pay,"status"))&&pay.get("result_json")!=null,"SC23_PAYMENT_NOT_COMPLETE");
        var request=object(pay.get("request_json"));var result=object(pay.get("result_json"));
        check((evidence==null?channel.trusted(result):evidence.trusted(mode,request,result))&&hash(request).equals(id(pay,"request_hash"))&&hash(request).equals(hash(result.get("request"))),"SC23_SOURCE_UNTRUSTED");
        check(trade.equals(request.get("tradeId").getAsString())&&id(state,"member_id").equals(request.get("payerMemberId").getAsString())&&id(pay,"combine_no").equals(request.get("combineNo").getAsString())&&mode.equals(request.get("mode").getAsString()),"SC23_IDENTITY_MISMATCH");
        var paid=evidence==null?Stage2PaymentFacts.require(result,request):evidence.require(mode,request,result);check(paid.get("amountCent").getAsLong()==number(state,"amount_cent"),"SC23_AMOUNT_MISMATCH");
        var snapshots=db.rows("SELECT * FROM li_p0_order_snapshot WHERE trade_id=? ORDER BY store_id",trade);
        Map<String,JsonObject> expected=new HashMap<>();for(var raw:request.getAsJsonArray("subOrders")){var sub=raw.getAsJsonObject();expected.put(sub.get("orderId").getAsString(),sub);}
        check(!snapshots.isEmpty()&&expected.size()==snapshots.size(),"SC23_ORDER_SET_MISMATCH");
        Set<String> stores=new HashSet<>();
        for(var snapshot:snapshots){var internal=object(snapshot.get("payload_json"));var order=internal.getAsJsonObject("order");String orderId=id(snapshot,"order_id"),store=id(snapshot,"store_id");var sub=expected.remove(orderId);
            check(sub!=null&&stores.add(store)&&hash(internal).equals(id(snapshot,"content_hash"))&&orderId.equals(order.get("orderId").getAsString())&&store.equals(order.get("storeId").getAsString())&&id(state,"member_id").equals(id(snapshot,"member_id")),"SC23_SNAPSHOT_MISMATCH");
            var channel=internal.getAsJsonObject("funds").getAsJsonObject("channel");
            check(store.equals(sub.get("storeId").getAsString())&&("S2S"+orderId).equals(sub.get("subNo").getAsString())&&channel.get("sub_mchid").getAsString().equals(sub.get("subMchid").getAsString())
                &&channel.get("provider_mchid").getAsString().equals(request.get("platformMerchantId").getAsString())&&channel.get("payment_appid").getAsString().equals(request.get("applicationId").getAsString())
                &&number(snapshot,"amount_cent")==sub.get("amountCent").getAsLong()&&number(snapshot,"amount_cent")==order.get("amountCent").getAsLong()
                &&number(snapshot,"goods_cent")==order.get("goodsCent").getAsLong()&&number(snapshot,"freight_cent")==order.get("freightCent").getAsLong(),"SC23_SNAPSHOT_MISMATCH");
        }check(expected.isEmpty(),"SC23_ORDER_SET_MISMATCH");return paid;
    }
    private void requireConverged(String trade,JsonObject paid){
        var state=db.required("SELECT * FROM li_p0_trade_state WHERE trade_id=?",trade);
        check("PAID".equals(id(state,"status"))&&"SUCCEEDED".equals(id(state,"payment_status"))&&"NONE".equals(id(state,"close_status"))&&state.get("protection")==null,"SC23_LOCAL_NOT_READY");
        var nativeTrade=db.required("SELECT * FROM li_trade WHERE id=?",trade);check("PAID".equals(id(nativeTrade,"pay_status"))&&id(state,"member_id").equals(id(nativeTrade,"member_id")),"SC23_NATIVE_TRADE_MISMATCH");
        var orders=db.rows("SELECT * FROM li_order WHERE trade_sn=? AND delete_flag=0 ORDER BY id",nativeTrade.get("sn"));
        Map<String,JsonObject> paidByOrder=new HashMap<>();for(var raw:paid.getAsJsonArray("subOrders")){var sub=raw.getAsJsonObject();paidByOrder.put(sub.get("orderId").getAsString(),sub);}
        check(orders.size()==paidByOrder.size(),"SC23_ORDER_SET_MISMATCH");Set<String> itemIds=new HashSet<>();Map<String,Long> captured=new HashMap<>();
        for(var order:orders){String orderId=id(order,"id");var child=paidByOrder.remove(orderId);check(child!=null,"SC23_ORDER_SET_MISMATCH");
            var snap=db.required("SELECT * FROM li_p0_order_snapshot WHERE order_id=?",orderId);var original=object(snap.get("payload_json")).getAsJsonObject("order");
            check(id(order,"store_id").equals(id(snap,"store_id"))&&id(order,"member_id").equals(id(state,"member_id"))&&"PAID".equals(id(order,"pay_status"))
                &&Instant.parse(child.get("paidAt").getAsString()).equals(instant(order.get("payment_time")))&&cents(order.get("flow_price"))==number(snap,"amount_cent")
                &&original.get("method").getAsString().equals(id(order,"delivery_method"))&&original.get("orderSn").getAsString().equals(id(order,"sn"))
                &&Objects.equals(nullable(original.get("remark")),id(order,"remark")),"SC23_NATIVE_ORDER_MISMATCH");
            requireOrderSnapshot(order,original);
            var items=db.rows("SELECT * FROM li_order_item WHERE order_sn=? AND delete_flag=0 ORDER BY id",order.get("sn"));Map<String,JsonObject> lines=new HashMap<>();for(var raw:original.getAsJsonArray("items")){var line=raw.getAsJsonObject();check(lines.put(line.get("skuId").getAsString(),line)==null,"SC23_ITEM_MISMATCH");}
            check(items.size()==lines.size(),"SC23_ITEM_MISMATCH");
            for(var item:items){var line=lines.remove(id(item,"sku_id"));check(line!=null&&id(item,"trade_sn").equals(id(nativeTrade,"sn"))&&id(item,"goods_id").equals(line.get("goodsId").getAsString())
                    &&number(item,"num")==line.get("quantity").getAsLong()&&cents(item.get("unit_price"))==line.get("unitPriceCent").getAsLong()&&cents(item.get("sub_total"))==Math.multiplyExact(line.get("quantity").getAsLong(),line.get("unitPriceCent").getAsLong()),"SC23_ITEM_MISMATCH");
                requireItemSnapshot(item,line);
                itemIds.add(id(item,"id"));var hold=db.required("SELECT * FROM li_p0_inventory_hold WHERE order_item_id=?",item.get("id"));
                check(trade.equals(id(hold,"trade_id"))&&id(item,"sku_id").equals(id(hold,"sku_id"))&&number(item,"num")==number(hold,"quantity")&&"CAPTURED".equals(id(hold,"status")),"SC23_INVENTORY_NOT_CAPTURED");
                captured.merge(id(hold,"sku_id"),number(hold,"quantity"),Math::addExact);
            }
        }check(paidByOrder.isEmpty()&&db.count("SELECT COUNT(*) FROM li_p0_inventory_hold WHERE trade_id=?",trade)==itemIds.size(),"SC23_ITEM_MISMATCH");
        for(var event:db.rows("SELECT * FROM li_p0_inventory_event WHERE trade_id=? AND event_type IN ('CAPTURE','RELEASE') ORDER BY id",trade)){
            check("CAPTURE".equals(id(event,"event_type"))&&number(event,"before_a")==number(event,"after_a"),"SC23_INVENTORY_NOT_CAPTURED");
            String sku=id(event,"sku_id");Long expected=captured.remove(sku);check(expected!=null&&expected==number(event,"before_h")-number(event,"after_h"),"SC23_INVENTORY_NOT_CAPTURED");
        }check(captured.isEmpty(),"SC23_INVENTORY_NOT_CAPTURED");
    }
    static void requireOrderSnapshot(Map<String,Object> order,JsonObject original){
        var contact=original.getAsJsonObject("contact");
        check(contact.get("name").getAsString().equals(id(order,"consignee_name"))&&contact.get("mobile").getAsString().equals(id(order,"consignee_mobile"))
            &&original.get("storeName").getAsString().equals(id(order,"store_name"))&&original.get("goodsCent").getAsLong()==cents(order.get("goods_price"))&&original.get("freightCent").getAsLong()==cents(order.get("freight_price")),"SC23_NATIVE_ORDER_MISMATCH");
        if(original.get("address").isJsonNull())check(order.get("consignee_address_path")==null&&order.get("consignee_address_id_path")==null&&order.get("consignee_detail")==null,"SC23_NATIVE_ORDER_MISMATCH");
        else {var address=original.getAsJsonObject("address");String regionIds=address.get("provinceCode").getAsString()+","+address.get("cityCode").getAsString()+","+address.get("districtCode").getAsString();
            check(address.get("region").getAsString().equals(id(order,"consignee_address_path"))&&regionIds.equals(id(order,"consignee_address_id_path"))&&address.get("detail").getAsString().equals(id(order,"consignee_detail")),"SC23_NATIVE_ORDER_MISMATCH");}
    }
    static void requireItemSnapshot(Map<String,Object> item,JsonObject line){
        check(line.get("name").getAsString().equals(id(item,"goods_name"))&&Objects.equals(nullable(line.get("specifications")),id(item,"specs"))&&cents(item.get("flow_price"))==Math.multiplyExact(line.get("quantity").getAsLong(),line.get("unitPriceCent").getAsLong()),"SC23_ITEM_MISMATCH");
    }
    public static String nullable(JsonElement value){return value==null||value.isJsonNull()?null:value.getAsString();}
    private static void check(boolean value,String reason){Stage1Exception.require(value,409,reason);}
}
