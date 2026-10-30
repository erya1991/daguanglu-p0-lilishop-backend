package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.support.*;
import cn.lili.modules.order.order.entity.dos.*;
import cn.lili.modules.order.order.mapper.*;
import cn.lili.modules.order.trade.entity.dos.OrderLog;
import cn.lili.modules.order.trade.mapper.OrderLogMapper;
import com.google.gson.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Native Trade/Order/Item persistence with an atomic P0 transaction, no legacy render/events. */
@Service @ConditionalOnProperty(name="p0.stage2.enabled",havingValue="true")
public class Stage2TradeService {
    private final Stage1Db db;private final Stage2Transactions tx;private final Stage2QuoteService quotes;private final Stage2Cart cart;private final Stage2Inventory inventory;
    private final TradeMapper trades;private final OrderMapper orders;private final OrderItemMapper items;private final OrderLogMapper logs;private final Clock clock;
    public Stage2TradeService(Stage1Db db,Stage2Transactions tx,Stage2QuoteService quotes,Stage2Cart cart,Stage2Inventory inventory,TradeMapper trades,OrderMapper orders,OrderItemMapper items,OrderLogMapper logs,@Qualifier("stage1Clock")Clock clock){this.db=db;this.tx=tx;this.quotes=quotes;this.cart=cart;this.inventory=inventory;this.trades=trades;this.orders=orders;this.items=items;this.logs=logs;this.clock=clock;}
    public Object create(Object body){
        String member=tx.member();var f=new Fields(body).only("intentId","quoteId","confirmationHash");String intent=f.text("intentId",8,64,true);Stage1Exception.require(intent.matches("[A-Za-z0-9_-]+"),422,"INTENT_ID_INVALID");f.id("quoteId",true);String digest=f.text("confirmationHash",64,64,true);Stage1Exception.require(digest.matches("[a-f0-9]{64}"),422,"CONFIRMATION_INVALID");String payloadHash=hash(body);
        tx.locked(member,List.of(),()->{
            var old=db.one("SELECT * FROM li_p0_checkout_intent WHERE member_id=? AND intent_id=? FOR UPDATE",member,intent);
            if(old!=null)Stage1Exception.require(payloadHash.equals(id(old,"payload_hash")),409,"IDEMPOTENCY_CONFLICT");
            else {db.update("INSERT INTO li_p0_checkout_intent(member_id,intent_id,payload_hash,payload_json,status,created_at,updated_at) VALUES(?,?,?,?,'ACCEPTED',?,?)",member,intent,payloadHash,write(body),clock.instant(),clock.instant());tx.task("INTENT",member+":"+intent);}
            return null;
        });
        return resume(member,intent);
    }
    public Object resume(String member,String intent){
        var current=intentRow(member,intent);if(Set.of("SUCCEEDED","FAILED").contains(id(current,"status")))return intentView(current);
        var command=new Fields(current.get("payload_json"));
        try{var quote=quotes.stored(command.id("quoteId",true),member);var stored=object(quote.get("payload_json"));var stores=stored.getAsJsonArray("orders").asList().stream().map(v->v.getAsJsonObject().get("storeId").getAsString()).toList();
            tx.locked(member,stores,()->{
                var locked=db.required("SELECT * FROM li_p0_checkout_intent WHERE member_id=? AND intent_id=? FOR UPDATE",member,intent);
                if(Set.of("SUCCEEDED","FAILED").contains(id(locked,"status")))return null;
                Stage1Exception.require(clock.instant().isBefore(instant(quote.get("expires_at"))),409,"QUOTE_EXPIRED");
                Stage1Exception.require(id(quote,"content_hash").equals(command.text("confirmationHash",64,64,true)),409,"CONFIRMATION_INVALID");
                var fresh=quotes.build(member,stored.get("input"),false);
                Stage1Exception.require(hash(fresh.get("commercial")).equals(id(quote,"content_hash")),409,"RECONFIRM_REQUIRED");
                persist(member,intent,fresh);
                return null;
            });
        } catch(Stage1Exception rejected){
            if(rejected.status()>=500)throw rejected;
            tx.historical(member,List.of(),()->{var row=db.required("SELECT * FROM li_p0_checkout_intent WHERE member_id=? AND intent_id=? FOR UPDATE",member,intent);if(row.get("trade_id")==null)db.update("UPDATE li_p0_checkout_intent SET status='FAILED',failure_code=?,updated_at=? WHERE member_id=? AND intent_id=?",rejected.code(),clock.instant(),member,intent);return null;});
        } catch(DataAccessException unknown){
            // A lost commit acknowledgment is resolved from the durable original intent; never rebuild.
            tx.historical(member,List.of(),()->{db.update("UPDATE li_p0_checkout_intent SET status='VERIFYING',updated_at=? WHERE member_id=? AND intent_id=? AND trade_id IS NULL AND status<>'FAILED'",clock.instant(),member,intent);tx.task("INTENT",member+":"+intent);return null;});
        }
        return intentView(intentRow(member,intent));
    }
    private void persist(String member,String intent,Map<String,Object> payload){
        var value=object(payload);String tradeId=db.id(),sn="S2T"+tradeId;Instant created=clock.instant();long total=value.get("amountCent").getAsLong(),goodsTotal=0,freightTotal=0;
        for(var e:value.getAsJsonArray("orders")){var order=e.getAsJsonObject();goodsTotal=Stage2Money.add(goodsTotal,order.get("goodsCent").getAsLong());freightTotal=Stage2Money.add(freightTotal,order.get("freightCent").getAsLong());}
        Stage1Exception.require(total>0&&total==Stage2Money.add(goodsTotal,freightTotal),409,"AMOUNT_INVARIANT");
        var trade=new Trade();trade.setId(tradeId);trade.setSn(sn);trade.setMemberId(member);trade.setPayStatus("UNPAID");trade.setFlowPrice(money(total).doubleValue());trade.setGoodsPrice(money(goodsTotal).doubleValue());trade.setFreightPrice(money(freightTotal).doubleValue());trade.setDiscountPrice(0D);trade.setDeleteFlag(false);trade.setCreateTime(Date.from(created));db.cas(trades.insert(trade));
        db.update("INSERT INTO li_p0_trade_state(trade_id,member_id,intent_id,mode,status,payment_status,amount_cent,created_at,pay_deadline) VALUES(?,?,?,'MOCK','WAIT_PAY','NOT_STARTED',?,?,?)",tradeId,member,intent,total,created,created.plusSeconds(900));
        var sortedOrders=new ArrayList<JsonObject>();value.getAsJsonArray("internalOrders").forEach(v->sortedOrders.add(v.getAsJsonObject()));sortedOrders.sort(Comparator.comparingLong(o->o.getAsJsonObject("order").get("storeId").getAsLong()));
        for(var internal:sortedOrders){
            var o=internal.getAsJsonObject("order");var funds=internal.getAsJsonObject("funds");String orderId=db.id(),orderSn="S2O"+orderId,store=o.get("storeId").getAsString();
            var order=new Order();order.setId(orderId);order.setSn(orderSn);order.setTradeSn(sn);order.setStoreId(store);order.setStoreName(o.get("storeName").getAsString());order.setMemberId(member);order.setOrderStatus("UNPAID");order.setPayStatus("UNPAID");order.setDeliverStatus("UNDELIVERED");order.setOrderType("NORMAL");order.setFlowPrice(money(o.get("amountCent").getAsLong()).doubleValue());order.setGoodsPrice(money(o.get("goodsCent").getAsLong()).doubleValue());order.setFreightPrice(money(o.get("freightCent").getAsLong()).doubleValue());order.setDiscountPrice(0D);order.setDeliveryMethod(o.get("method").getAsString());order.setRemark(o.get("remark").isJsonNull()?null:o.get("remark").getAsString());
            var contact=o.getAsJsonObject("contact");order.setConsigneeName(contact.get("name").getAsString());order.setConsigneeMobile(contact.get("mobile").getAsString());
            if(!o.get("address").isJsonNull()){var address=o.getAsJsonObject("address");order.setConsigneeAddressPath(address.get("region").getAsString());order.setConsigneeAddressIdPath(address.get("provinceCode").getAsString()+","+address.get("cityCode").getAsString()+","+address.get("districtCode").getAsString());order.setConsigneeDetail(address.get("detail").getAsString());}
            order.setDeleteFlag(false);order.setCreateTime(Date.from(created));db.cas(orders.insert(order));
            o.addProperty("orderId",orderId);o.addProperty("orderSn",orderSn);
            db.update("INSERT INTO li_p0_order_snapshot VALUES(?,?,?,?,?,?,?,?,?,?,?)",orderId,tradeId,member,store,write(internal),hash(internal),o.get("goodsCent").getAsLong(),o.get("freightCent").getAsLong(),o.get("amountCent").getAsLong(),funds.get("commissionCent").getAsLong(),funds.get("merchantCent").getAsLong());
            var lines=new ArrayList<JsonObject>();o.getAsJsonArray("items").forEach(v->lines.add(v.getAsJsonObject()));lines.sort(Comparator.comparingLong(l->l.get("skuId").getAsLong()));
            for(var line:lines){String itemId=db.id(),sku=line.get("skuId").getAsString();long quantity=line.get("quantity").getAsLong();
                // The hold exists before the native Item reference, within this same all-or-none transaction.
                inventory.reserve(tradeId,itemId,sku,quantity);
                var item=new OrderItem();item.setId(itemId);item.setSn("S2I"+itemId);item.setTradeSn(sn);item.setOrderSn(orderSn);item.setGoodsId(line.get("goodsId").getAsString());item.setSkuId(sku);item.setNum(Math.toIntExact(quantity));item.setGoodsName(line.get("name").getAsString());item.setUnitPrice(money(line.get("unitPriceCent").getAsLong()).doubleValue());item.setSubTotal(money(Stage2Money.item(line.get("unitPriceCent").getAsLong(),quantity)).doubleValue());item.setFlowPrice(item.getSubTotal());item.setSpecs(line.get("specifications").isJsonNull()?null:line.get("specifications").getAsString());item.setDeleteFlag(false);item.setCreateTime(Date.from(created));db.cas(items.insert(item));
                if(!line.get("cartId").isJsonNull()){String cartId=line.get("cartId").getAsString();long version=line.get("cartVersion").getAsLong();var original=cart.line(cartId,member,version);Stage1Exception.require(sku.equals(id(original,"sku_id"))&&number(original,"quantity")>=quantity,409,"CART_VERSION_CONFLICT");db.update("INSERT INTO li_p0_cart_consumption(cart_id,cart_version,member_id,quantity,intent_id,trade_id,cleanup_version,created_at) VALUES(?,?,?,?,?,?,?,?)",cartId,version,member,quantity,intent,tradeId,version+1,created);db.cas(db.update("UPDATE li_p0_cart_item SET quantity=quantity-?,version=version+1,updated_at=? WHERE id=? AND version=?",quantity,created,cartId,version));}
            }
            var log=new OrderLog(orderSn,member,"MEMBER","住户","Stage2 created; awaiting payment");log.setId(db.id());log.setCreateTime(Date.from(created));db.cas(logs.insert(log));
        }
        db.cas(db.update("UPDATE li_p0_checkout_intent SET status='SUCCEEDED',trade_id=?,updated_at=? WHERE member_id=? AND intent_id=? AND trade_id IS NULL AND status IN ('ACCEPTED','VERIFYING')",tradeId,created,member,intent));tx.task("CART_CLEAN",tradeId);tx.task("PAYMENT",tradeId);
    }
    public Object intent(String key){return intentView(intentRow(tx.member(),key));}
    private Map<String,Object> intentRow(String member,String key){return db.required("SELECT * FROM li_p0_checkout_intent WHERE member_id=? AND intent_id=?",member,key);}
    private Object intentView(Map<String,Object> row){return map("intentId",id(row,"intent_id"),"status",row.get("status"),"tradeId",id(row,"trade_id"),"reasonCode",row.get("failure_code"),"trade",row.get("trade_id")==null?null:summary(id(row,"trade_id"),id(row,"member_id")),"serverTime",clock.instant());}
    public Object summary(String trade){return summary(trade,tx.member());}
    public Map<String,Object> row(String trade,String member){return db.required("SELECT * FROM li_p0_trade_state WHERE trade_id=? AND member_id=? AND mode='MOCK'",trade,member);}
    public List<String> stores(String trade){return db.rows("SELECT store_id FROM li_p0_order_snapshot WHERE trade_id=? ORDER BY store_id",trade).stream().map(r->id(r,"store_id")).toList();}
    public Object summary(String trade,String member){
        var row=row(trade,member);String status=id(row,"status"),payment=id(row,"payment_status"),close=id(row,"close_status");var actions=new ArrayList<String>(List.of("REFRESH"));
        var pay=db.one("SELECT request_json,result_json FROM li_p0_payment WHERE trade_id=?",trade);
        var paid="SUCCEEDED".equals(payment)&&pay!=null?Stage2PaymentFacts.verifiedOrNull(pay.get("result_json"),object(pay.get("request_json"))):null;
        // Historical test rows remain untouched; incomplete evidence cannot render ordinary success.
        Object protection=row.get("protection");if("SUCCEEDED".equals(payment)&&paid==null&&protection==null)protection="PAYMENT_EVIDENCE_INCOMPLETE";
        if(row.get("protection")==null&&status.equals("WAIT_PAY")&&Set.of("NOT_STARTED","UNPAID","CONFIRMED_FAILED").contains(payment)&&close.equals("NONE")&&clock.instant().isBefore(instant(row.get("pay_deadline"))))actions.add("PAY");
        if(!Set.of("PAID","CLOSED").contains(status)&&!"SUCCEEDED".equals(payment)&&close.equals("NONE"))actions.add("CANCEL");
        return map("tradeId",trade,"intentId",row.get("intent_id"),"mode","MOCK","status",status,"paymentStatus",payment,"closeStatus",close,"protection",protection,"closeReason",row.get("close_reason"),"closeDetails",row.get("close_details")==null?null:object(row.get("close_details")),"attention",row.get("attention_at")!=null,"amountCent",row.get("amount_cent"),"paidAt",paid==null?null:paid.get("paidAt"),"paidAmountCent",paid==null?null:paid.get("amountCent"),"createdAt",instant(row.get("created_at")),"payDeadline",instant(row.get("pay_deadline")),"lastVerifiedAt",instant(row.get("verified_at")),"allowedActions",actions,"orders",db.rows("SELECT payload_json FROM li_p0_order_snapshot WHERE trade_id=? ORDER BY store_id",trade).stream().map(r->object(r.get("payload_json")).get("order")).toList(),"serverTime",clock.instant());
    }
    public Object pending(int page,int size){String member=tx.member();Stage1Exception.require(page>=1&&size>=1&&size<=100,422,"PAGE_INVALID");var cards=new ArrayList<Map<String,Object>>();
        for(var r:db.rows("SELECT * FROM li_p0_checkout_intent WHERE member_id=? AND trade_id IS NULL AND status IN ('ACCEPTED','VERIFYING')",member))cards.add(map("key","intent:"+id(r,"intent_id"),"type","INTENT","intentId",id(r,"intent_id"),"status",r.get("status"),"createdAt",instant(r.get("created_at")),"priority",0));
        for(var r:db.rows("SELECT * FROM li_p0_trade_state WHERE member_id=? AND (status NOT IN ('PAID','CLOSED') OR protection IS NOT NULL)",member))cards.add(map("key","trade:"+id(r,"trade_id"),"type","TRADE","tradeId",id(r,"trade_id"),"intentId",id(r,"intent_id"),"status",r.get("status"),"createdAt",instant(r.get("created_at")),"priority",r.get("protection")!=null||!"WAIT_PAY".equals(id(r,"status"))?0:1,"summary",summary(id(r,"trade_id"),member)));
        cards.sort(Comparator.<Map<String,Object>>comparingInt(r->((Number)r.get("priority")).intValue()).thenComparing(r->(Instant)r.get("createdAt"),Comparator.reverseOrder()).thenComparing(r->(String)r.get("key")));
        return map("records",cards.stream().skip((long)(page-1)*size).limit(size).toList(),"total",cards.size(),"page",page,"pageSize",size,"serverTime",clock.instant());
    }
}
