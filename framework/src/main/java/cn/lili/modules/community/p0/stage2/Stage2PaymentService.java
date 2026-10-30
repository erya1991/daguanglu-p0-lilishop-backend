package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service @ConditionalOnProperty(name="p0.stage2.enabled",havingValue="true")
public class Stage2PaymentService {
    private final Stage1Db db;private final Stage2Transactions tx;private final Stage2TradeService trades;private final Stage2QuoteService quotes;private final Stage2Inventory inventory;
    private final PersistentPaymentMock channel;private final Stage2Settings settings;private final Clock clock;
    public Stage2PaymentService(Stage1Db db,Stage2Transactions tx,Stage2TradeService trades,Stage2QuoteService quotes,Stage2Inventory inventory,PersistentPaymentMock channel,Stage2Settings settings,@Qualifier("stage1Clock")Clock clock){this.db=db;this.tx=tx;this.trades=trades;this.quotes=quotes;this.inventory=inventory;this.channel=channel;this.settings=settings;this.clock=clock;}
    private Map<String,Object> payment(String trade){return db.one("SELECT * FROM li_p0_payment WHERE trade_id=?",trade);}
    private Map<String,Object> historicalRow(String trade){return db.required("SELECT * FROM li_p0_trade_state WHERE trade_id=? AND mode='MOCK'",trade);}
    private String member(Map<String,Object> row){return id(row,"member_id");}
    public Object prepare(String trade,Object body){
        var f=new Fields(body).only("operationId");String operation=Stage2Transactions.operation(f),member=tx.member();trades.row(trade,member);
        JsonObject request=tx.locked(member,trades.stores(trade),()->{
            var row=db.required("SELECT * FROM li_p0_trade_state WHERE trade_id=? AND member_id=? FOR UPDATE",trade,member);
            var old=payment(trade);
            if(old!=null&&!Set.of("UNPAID","CONFIRMED_FAILED").contains(id(old,"status")))return null;
            Instant now=clock.instant(),deadline=instant(row.get("pay_deadline"));
            Stage1Exception.require("NONE".equals(id(row,"close_status"))&&"WAIT_PAY".equals(id(row,"status"))&&now.isBefore(deadline)&&(old!=null||!now.plusSeconds(settings.getMockMinimumValiditySeconds()).isAfter(deadline)),409,"PAYMENT_NOT_ALLOWED");
            inventory.requireTradeHeld(trade);
            try{recheck(trade,member);}catch(Stage1Exception changed){
                if(changed.status()>=500)throw changed;
                db.update("UPDATE li_p0_trade_state SET close_status='REQUESTED',status='CLOSE_CONFIRMING',close_reason='RECONFIRM_REQUIRED',close_details=?,protection='RECONFIRM_REQUIRED',version=version+1 WHERE trade_id=?",write(map("causeCode",changed.code(),"details",changed.details())),trade);tx.task("PAYMENT",trade);return null;
            }
            if(old!=null)return null;
            var command=request(trade,row);
            db.update("INSERT INTO li_p0_payment(trade_id,combine_no,request_hash,request_json,status,dispatch_status,created_at,updated_at) VALUES(?,?,?,?,'UNKNOWN','MAYBE_SENT',?,?)",trade,command.get("combineNo").getAsString(),hash(command),write(command),clock.instant(),clock.instant());
            db.update("INSERT INTO li_p0_payment_operation(id,trade_id,operation_type,status,operation_id,request_hash,created_at,updated_at) VALUES(?,?,'PREPARE','MAYBE_SENT',?,?,?,?)",db.id(),trade,operation,hash(command),clock.instant(),clock.instant());
            db.update("UPDATE li_p0_trade_state SET status='PAY_CONFIRMING',payment_status='UNKNOWN',unknown_since=COALESCE(unknown_since,?),query_step=0,next_query_at=?,version=version+1 WHERE trade_id=?",clock.instant(),clock.instant(),trade);tx.task("PAYMENT",trade);
            return command;
        });
        if(request!=null){try{accept(trade,channel.prepare(request));}catch(RuntimeException unknown){markUnknown(trade,"PREPARE_UNKNOWN");}}
        else recover(trade);
        if(payment(trade)!=null&&"UNKNOWN".equals(id(payment(trade),"status")))recover(trade);
        return view(trade,member);
    }
    /** Authenticated resident launches only the server-selected Mock scenario; no outcome parameter. */
    public Object launch(String trade,Object body){new Fields(body).only("operationId");Stage2Transactions.operation(new Fields(body));String member=tx.member();trades.row(trade,member);
        JsonObject command=tx.locked(member,trades.stores(trade),()->{var row=historicalRow(trade);var pay=payment(trade);Stage1Exception.require(pay!=null&&"UNPAID".equals(id(pay,"status"))&&"WAIT_PAY".equals(id(row,"status"))&&row.get("protection")==null&&"NONE".equals(id(row,"close_status"))&&clock.instant().isBefore(instant(row.get("pay_deadline"))),409,"PAYMENT_NOT_ALLOWED");inventory.requireTradeHeld(trade);try{recheck(trade,member);}catch(Stage1Exception changed){if(changed.status()>=500)throw changed;db.update("UPDATE li_p0_trade_state SET close_status='REQUESTED',status='CLOSE_CONFIRMING',close_reason='RECONFIRM_REQUIRED',close_details=?,protection='RECONFIRM_REQUIRED',next_query_at=? WHERE trade_id=?",write(map("causeCode",changed.code(),"details",changed.details())),clock.instant(),trade);tx.task("PAYMENT",trade);return null;}
            var request=object(pay.get("request_json"));String op=Stage2Transactions.operation(new Fields(body));
            db.update("INSERT INTO li_p0_payment_operation(id,trade_id,operation_type,status,operation_id,request_hash,created_at,updated_at) VALUES(?,?,'LAUNCH','MAYBE_SENT',?,?,?,?) ON DUPLICATE KEY UPDATE updated_at=VALUES(updated_at)",db.id(),trade,op,hash(request),clock.instant(),clock.instant());
            markUnknownLocked(trade,"LAUNCH_PENDING");return request;});
        if(command==null){recover(trade);return view(trade,member);}
        try{accept(trade,channel.launch(command));}catch(RuntimeException unknown){markUnknown(trade,"LAUNCH_UNKNOWN");}
        if("UNKNOWN".equals(id(payment(trade),"status")))recover(trade);return view(trade,member);
    }
    public Object refresh(String trade){String member=tx.member();trades.row(trade,member);recover(trade);return view(trade,member);}
    public Object cancel(String trade,Object body){new Fields(body).only("operationId");Stage2Transactions.operation(new Fields(body));String member=tx.member();trades.row(trade,member);
        tx.locked(member,trades.stores(trade),()->{var row=historicalRow(trade);if(Set.of("PAID","CLOSED").contains(id(row,"status"))||"SUCCEEDED".equals(id(row,"payment_status")))return null;db.update("UPDATE li_p0_trade_state SET next_query_at=IF(close_status='NONE',?,next_query_at),close_status='REQUESTED',status='CLOSE_CONFIRMING',version=version+1 WHERE trade_id=?",clock.instant(),trade);tx.task("PAYMENT",trade);return null;});recover(trade);return view(trade,member);
    }
    private Object view(String trade,String member){return map("trade",trades.summary(trade,member),"launchReady",Optional.ofNullable(payment(trade)).map(r->"UNPAID".equals(id(r,"status"))).orElse(false));}
    private void recheck(String trade,String member){
        var state=historicalRow(trade);var intent=db.required("SELECT payload_json FROM li_p0_checkout_intent WHERE member_id=? AND intent_id=?",member,state.get("intent_id"));String quoteId=new Fields(intent.get("payload_json")).id("quoteId",true);var quote=quotes.stored(quoteId,member);var original=object(quote.get("payload_json"));var input=original.getAsJsonObject("input").deepCopy();
        var snapshots=new HashMap<String,JsonObject>();for(var row:db.rows("SELECT store_id,payload_json FROM li_p0_order_snapshot WHERE trade_id=?",trade))snapshots.put(id(row,"store_id"),object(row.get("payload_json")).getAsJsonObject("order"));
        for(var raw:input.getAsJsonArray("stores")){var store=raw.getAsJsonObject();var snapshot=snapshots.get(store.get("storeId").getAsString());if(!snapshot.get("address").isJsonNull()){store.remove("addressId");store.remove("addressVersion");store.add("addressSnapshot",snapshot.get("address").deepCopy());}}
        var current=quotes.build(member,input,true);if(!hash(current.get("commercial")).equals(id(quote,"content_hash")))throw new Stage1Exception(409,"RECONFIRM_REQUIRED",map("changes",Stage2QuoteService.changes(original.getAsJsonArray("commercial"),object(current).getAsJsonArray("commercial"))));
    }
    private JsonObject request(String trade,Map<String,Object> state){
        var suborders=new ArrayList<Object>();String platform=null,app=null;long total=0;
        for(var row:db.rows("SELECT * FROM li_p0_order_snapshot WHERE trade_id=? ORDER BY store_id",trade)){var snapshot=object(row.get("payload_json"));var relation=snapshot.getAsJsonObject("funds").getAsJsonObject("channel");String thisPlatform=relation.get("provider_mchid").getAsString(),thisApp=relation.get("payment_appid").getAsString();if(platform!=null)Stage1Exception.require(platform.equals(thisPlatform)&&app.equals(thisApp),409,"CHANNEL_SET_CONFLICT");platform=thisPlatform;app=thisApp;
            long amount=number(row,"amount_cent");total=Stage2Money.add(total,amount);suborders.add(map("orderId",id(row,"order_id"),"storeId",id(row,"store_id"),"subNo","S2S"+id(row,"order_id"),"subMchid",relation.get("sub_mchid"),"amountCent",amount));}
        Stage1Exception.require(total==number(state,"amount_cent"),409,"AMOUNT_INVARIANT");
        return object(map("mode","MOCK","combineNo","S2C"+trade,"tradeId",trade,"payerMemberId",member(state),"platformMerchantId",platform,"applicationId",app,"currency","CNY","amountCent",total,"subOrders",suborders,"expiresAt",instant(state.get("pay_deadline"))));
    }
    /** Trusted worker/notification path. Current shopping eligibility never erases historic payment facts. */
    public void recover(String trade){
        var state=historicalRow(trade);String member=member(state);var stores=trades.stores(trade);var pay=payment(trade);
        if(pay!=null&&"SUCCEEDED".equals(id(pay,"status"))){settlePaid(trade);return;}
        if(pay!=null&&"CLOSED".equals(id(pay,"status"))){closeLocal(trade,true);return;}
        if(Set.of("PAID","CLOSED").contains(id(state,"status"))&&state.get("protection")==null)return;
        if(!clock.instant().isBefore(instant(state.get("pay_deadline")))&&"NONE".equals(id(state,"close_status")))tx.historical(member,stores,()->{db.update("UPDATE li_p0_trade_state SET close_status='REQUESTED',status='CLOSE_CONFIRMING',version=version+1 WHERE trade_id=?",trade);return null;});
        state=historicalRow(trade);
        if(pay==null){if(!"NONE".equals(id(state,"close_status")))closeLocal(trade,false);return;}
        String lease=claimQuery(trade);if(lease==null)return;
        try{
            var request=object(pay.get("request_json"));var result=channel.query(request);accept(trade,result);
            var current=historicalRow(trade);
            if(!"NONE".equals(id(current,"close_status"))&&"UNPAID".equals(result.get("state").getAsString()))accept(trade,channel.close(request));
        }catch(RuntimeException unknown){markUnknown(trade,"QUERY_UNKNOWN");}
        finally{tx.historical(member,stores,()->{if(db.count("SELECT COUNT(*) FROM li_p0_payment_operation WHERE trade_id=? AND operation_type='QUERY' AND lease_owner=?",trade,lease)==0)return null;var current=historicalRow(trade);if("UNKNOWN".equals(id(current,"payment_status"))){long step=number(current,"query_step");long[] intervals={5,15,30,60};long delay=step<4?intervals[(int)step]:300;db.update("UPDATE li_p0_trade_state SET query_step=query_step+1,next_query_at=? WHERE trade_id=?",clock.instant().plusSeconds(delay),trade);}db.update("UPDATE li_p0_payment_operation SET status='IDLE',lease_owner=NULL,lease_until=NULL,updated_at=? WHERE trade_id=? AND operation_type='QUERY' AND lease_owner=?",clock.instant(),trade,lease);return null;});}
    }
    private String claimQuery(String trade){var row=historicalRow(trade);String owner=UUID.randomUUID().toString();return tx.historical(member(row),trades.stores(trade),()->{
        var current=historicalRow(trade);var existing=db.one("SELECT * FROM li_p0_payment_operation WHERE trade_id=? AND operation_type='QUERY' FOR UPDATE",trade);
        if(existing!=null&&existing.get("lease_until")!=null&&clock.instant().isBefore(instant(existing.get("lease_until"))))return null;
        if(current.get("next_query_at")!=null&&clock.instant().isBefore(instant(current.get("next_query_at"))))return null;
        if(existing==null)db.update("INSERT INTO li_p0_payment_operation(id,trade_id,operation_type,status,operation_id,request_hash,lease_owner,lease_until,created_at,updated_at) VALUES(?,?,'QUERY','RUNNING','query','',?,?,?,?)",db.id(),trade,owner,clock.instant().plusSeconds(20),clock.instant(),clock.instant());
        else db.update("UPDATE li_p0_payment_operation SET status='RUNNING',lease_owner=?,lease_until=?,updated_at=? WHERE id=?",owner,clock.instant().plusSeconds(20),clock.instant(),existing.get("id"));return owner;
    });}
    public void notification(Object body){var result=object(body);Stage1Exception.require(channel.trusted(result),403,"NOTIFICATION_REJECTED");var request=result.getAsJsonObject("request");var pay=db.required("SELECT trade_id FROM li_p0_payment WHERE combine_no=?",request.get("combineNo").getAsString());accept(id(pay,"trade_id"),result);}
    public void accept(String trade,JsonObject result){
        Stage1Exception.require(channel.trusted(result),403,"NOTIFICATION_REJECTED");var state=historicalRow(trade);String member=member(state);var stores=trades.stores(trade);
        String outcome=result.get("state").getAsString();
        boolean valid=tx.historical(member,stores,()->{
            var pay=payment(trade);Stage1Exception.require(pay!=null,409,"PAYMENT_RELATION_MISSING");
            boolean matches="MOCK".equals(result.get("mode").getAsString())&&hash(result.get("request")).equals(id(pay,"request_hash"))&&Set.of("UNPAID","UNKNOWN","NOT_FOUND","EXPIRED_ABSENT","SUCCEEDED","CLOSED","PARTIAL").contains(outcome);
            if(matches&&"SUCCEEDED".equals(outcome)){
                var paid=Stage2PaymentFacts.verifiedOrNull(result,object(pay.get("request_json")));
                matches=paid!=null;
                if(matches&&"SUCCEEDED".equals(id(pay,"status"))){
                    var previous=Stage2PaymentFacts.verifiedOrNull(pay.get("result_json"),object(pay.get("request_json")));
                    matches=previous!=null&&hash(previous).equals(hash(paid));
                }
            }
            String event=result.get("eventId").getAsString();Stage1Exception.require(event.length()<=100,422,"EVENT_ID_INVALID");
            var old=db.one("SELECT payload_hash FROM li_p0_payment_evidence WHERE event_id=?",event);if(old!=null)Stage1Exception.require(hash(result).equals(id(old,"payload_hash")),409,"EVENT_CONTENT_CONFLICT");else db.update("INSERT INTO li_p0_payment_evidence VALUES(?,?,?,?,?,?)",event,trade,hash(result),write(result),matches?"VERIFIED":"REJECTED",clock.instant());
            if(!matches){db.update("UPDATE li_p0_trade_state SET protection=COALESCE(protection,'PAYMENT_EVIDENCE_MISMATCH') WHERE trade_id=?",trade);markUnknownLocked(trade,"PAYMENT_EVIDENCE_MISMATCH");return false;}
            if("SUCCEEDED".equals(outcome)){
                db.update("UPDATE li_p0_payment SET result_json=IF(status='SUCCEEDED',result_json,?),status='SUCCEEDED',dispatch_status='CONFIRMED',version=version+1,updated_at=? WHERE trade_id=?",write(result),clock.instant(),trade);
                db.update("UPDATE li_p0_trade_state SET payment_status='SUCCEEDED',status=IF(status='PAID','PAID','PAY_CONFIRMING'),verified_at=?,version=version+1 WHERE trade_id=?",clock.instant(),trade);tx.task("PAYMENT",trade);return true;
            }
            if("SUCCEEDED".equals(id(pay,"status")))return false;
            if(Set.of("CLOSED","EXPIRED_ABSENT").contains(outcome)){
                if(outcome.equals("EXPIRED_ABSENT"))Stage1Exception.require(!clock.instant().isBefore(Instant.parse(result.getAsJsonObject("request").get("expiresAt").getAsString())),409,"ABSENCE_NOT_FINAL");
                db.update("UPDATE li_p0_payment SET status='CLOSED',result_json=?,version=version+1,updated_at=? WHERE trade_id=?",write(result),clock.instant(),trade);return true;
            }
            if("CLOSED".equals(id(pay,"status")))return false;
            if(outcome.equals("UNPAID")){
                db.update("UPDATE li_p0_payment SET status='UNPAID',dispatch_status='CONFIRMED',version=version+1,updated_at=? WHERE trade_id=?",clock.instant(),trade);
                db.update("UPDATE li_p0_trade_state SET payment_status='UNPAID',status=IF(close_status='NONE','WAIT_PAY','CLOSE_CONFIRMING'),unknown_since=IF(close_status='NONE',NULL,unknown_since),attention_at=IF(close_status='NONE',NULL,attention_at),query_step=IF(close_status='NONE',0,query_step),next_query_at=?,verified_at=?,version=version+1 WHERE trade_id=?",clock.instant().plusSeconds(5),clock.instant(),trade);return true;
            }
            markUnknownLocked(trade,outcome.equals("PARTIAL")?"PARTIAL_CHANNEL_EVIDENCE":"PAYMENT_UNKNOWN");return true;
        });
        if(valid&&outcome.equals("SUCCEEDED"))settlePaid(trade);
        if(valid&&Set.of("CLOSED","EXPIRED_ABSENT").contains(outcome))closeLocal(trade,true);
    }
    private void settlePaid(String trade){var row=historicalRow(trade);String member=member(row);try{tx.historical(member,trades.stores(trade),()->{
        var current=historicalRow(trade);if("PAID".equals(id(current,"status"))&&current.get("protection")==null)return null;
        Stage1Exception.require("SUCCEEDED".equals(id(payment(trade),"status")),409,"PAYMENT_NOT_CONFIRMED");
        if(db.count("SELECT COUNT(*) FROM li_p0_inventory_hold WHERE trade_id=? AND status='RELEASED'",trade)>0){db.update("UPDATE li_p0_trade_state SET protection='LATE_PAYMENT_AFTER_RELEASE',status='PAY_CONFIRMING' WHERE trade_id=?",trade);return null;}
        var pay=payment(trade);var paid=Stage2PaymentFacts.require(object(pay.get("result_json")),object(pay.get("request_json")));
        inventory.settle(trade,true);
        for(var raw:paid.getAsJsonArray("subOrders")){var sub=raw.getAsJsonObject();db.cas(db.update("UPDATE li_order SET pay_status='PAID',order_status='PAID',payment_method='P0_COMBINE_MOCK',payment_time=? WHERE id=? AND trade_sn=(SELECT sn FROM li_trade WHERE id=?)",Instant.parse(sub.get("paidAt").getAsString()),sub.get("orderId").getAsString(),trade));}
        db.update("UPDATE li_trade SET pay_status='PAID',payment_method='P0_COMBINE_MOCK' WHERE id=?",trade);
        db.update("UPDATE li_p0_trade_state SET status='PAID',payment_status='SUCCEEDED',protection=NULL,verified_at=?,version=version+1 WHERE trade_id=?",clock.instant(),trade);return null;
    });}catch(RuntimeException pending){tx.historical(member,trades.stores(trade),()->{db.update("UPDATE li_p0_trade_state SET status='PAY_CONFIRMING',protection='LOCAL_SETTLEMENT_PENDING' WHERE trade_id=?",trade);tx.task("PAYMENT",trade);return null;});}}
    private void closeLocal(String trade,boolean channelProof){var row=historicalRow(trade);tx.historical(member(row),trades.stores(trade),()->{
        var current=historicalRow(trade);var pay=payment(trade);if(pay!=null&&"SUCCEEDED".equals(id(pay,"status")))return null;
        if("CLOSED".equals(id(current,"status")))return null;
        Stage1Exception.require(channelProof&&pay!=null&&"CLOSED".equals(id(pay,"status"))||pay==null&&db.count("SELECT COUNT(*) FROM li_p0_payment_operation WHERE trade_id=?",trade)==0,409,"CLOSE_PROOF_REQUIRED");
        inventory.settle(trade,false);db.update("UPDATE li_order SET order_status='CANCELLED' WHERE trade_sn=(SELECT sn FROM li_trade WHERE id=?)",trade);
        db.update("UPDATE li_p0_trade_state SET status='CLOSED',close_status='CONFIRMED',payment_status='CLOSED',protection=NULL,verified_at=?,version=version+1 WHERE trade_id=?",clock.instant(),trade);return null;
    });}
    private void markUnknown(String trade,String reason){var row=historicalRow(trade);tx.historical(member(row),trades.stores(trade),()->{markUnknownLocked(trade,reason);return null;});}
    private void markUnknownLocked(String trade,String reason){var row=historicalRow(trade);var pay=payment(trade);if(pay!=null&&Set.of("SUCCEEDED","CLOSED").contains(id(pay,"status")))return;
        boolean first=row.get("unknown_since")==null;
        db.update("UPDATE li_p0_payment SET status='UNKNOWN',updated_at=? WHERE trade_id=? AND status NOT IN ('SUCCEEDED','CLOSED')",clock.instant(),trade);
        db.update("UPDATE li_p0_trade_state SET payment_status='UNKNOWN',status=IF(close_status='NONE','PAY_CONFIRMING','CLOSE_CONFIRMING'),unknown_since=COALESCE(unknown_since,?),query_step=IF(?,0,query_step),next_query_at=IF(?, ?,COALESCE(next_query_at,?)),verified_at=?,protection=IF(?='PARTIAL_CHANNEL_EVIDENCE',?,protection),version=version+1 WHERE trade_id=?",clock.instant(),first,first,clock.instant(),clock.instant(),clock.instant(),reason,reason,trade);tx.task("PAYMENT",trade);
    }
}
