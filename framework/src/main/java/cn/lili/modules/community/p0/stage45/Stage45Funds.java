package cn.lili.modules.community.p0.stage45;

import cn.lili.modules.community.p0.stage2.*;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** One current return/refund batch per original child payment; all callers take the common order lock. */
@Service @ConditionalOnProperty(name="p0.stage45.enabled",havingValue="true")
public class Stage45Funds {
    final Stage45Data d;final Stage45FundsPort channel;private final cn.lili.modules.community.p0.channel.ChannelVault vault;
    public Stage45Funds(Stage45Data d,Stage45FundsPort channel,cn.lili.modules.community.p0.channel.ChannelVault vault){this.d=d;this.channel=channel;this.vault=vault;}
    public JsonObject original(Map<String,Object> order,boolean normal){
        var snapshot=d.facts(order,normal);var paid=d.eligibility.trustedPaid(id(order,"trade_id"));
        var payment=d.db.required("SELECT request_json FROM li_p0_payment WHERE trade_id=?",order.get("trade_id"));var request=object(payment.get("request_json"));JsonObject child=null;
        for(var raw:paid.getAsJsonArray("subOrders"))if(raw.getAsJsonObject().get("orderId").getAsString().equals(id(order,"id")))child=raw.getAsJsonObject();
        Stage1Exception.require(child!=null,409,"ORIGINAL_PAYMENT_MISSING");
        var result=object(map("mode",request.get("mode"),"applicationId",request.get("applicationId"),"profitSharing",new Fields(child).bool("profitSharing",false),"currency","CNY","orderId",id(order,"id"),"storeId",id(order,"store_id"),"transactionId",child.get("transactionId"),"subNo",child.get("subNo"),"subMchid",child.get("subMchid"),"platformMerchantId",request.get("platformMerchantId"),"payerMemberId",request.get("payerMemberId"),"paidAt",child.get("paidAt"),"paidCent",child.get("amountCent"),"snapshotHash",order.get("content_hash")));
        var historical=snapshot.getAsJsonObject("funds").getAsJsonObject("channel");Stage1Exception.require(historical!=null&&historical.has("provider_mchid"),409,"CHANNEL_SNAPSHOT_MISSING");
        // The existing historical channel relationship is the source, never a client-supplied receiver.
        if(historical.has("receiverAccountRef")&&historical.has("receiverAccountHash")){
            String receiver=new Fields(vault.get(historical.get("receiverAccountRef").getAsString())).text("account",1,64,true);
            Stage1Exception.require(digest(receiver.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(historical.get("receiverAccountHash").getAsString()),409,"RECEIVER_SNAPSHOT_CONFLICT");result.addProperty("receiverMchid",receiver);result.add("receiverAccountHash",historical.get("receiverAccountHash"));
        }
        return result;
    }
    public long actual(String order,String kind){
        return d.db.count("SELECT COALESCE(SUM(CAST(JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.succeededCent')) AS UNSIGNED)),0) FROM li_p0_s45_task WHERE order_id=? AND kind=? AND result_json IS NOT NULL",order,kind);
    }
    public long refunded(String order,String field){
        Stage1Exception.require(Set.of("goods_cent","fee_cent","late_cent").contains(field),500,"INTERNAL_FIELD_INVALID");
        return d.db.count("SELECT COALESCE(SUM(b."+field+"),0) FROM li_p0_s45_batch b JOIN li_p0_s45_task t ON t.batch_id=b.id AND t.kind='REFUND' WHERE b.order_id=? AND t.state='SUCCEEDED'",order);
    }
    public String finishSource(String order){
        if(d.db.count("SELECT COUNT(*) FROM li_p0_s45_task WHERE order_id=? AND kind='FINISH' AND state='SUCCEEDED'",order)>0)return "OWN_ORIGINAL_FINISH_REQUEST";
        if(d.db.count("SELECT COUNT(*) FROM li_p0_s45_task WHERE order_id=? AND kind='SHARE' AND applied=1 AND JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.reason'))='ORIGINAL_ALREADY_UNFROZEN' AND JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.originalFinish.state'))='SUCCEEDED'",order)>0)return "ORIGINAL_PROVIDER_UNFREEZE_EVIDENCE";
        return null;
    }
    private void createTask(Map<String,Object> order,String key,String kind,String batch,long amount,JsonObject origin,String parent){
        if(d.db.count("SELECT COUNT(*) FROM li_p0_s45_task WHERE business_key=?",key)>0)return;
        if(Set.of("SHARE","FINISH").contains(kind))Stage1Exception.require(new Fields(origin).bool("profitSharing",false),409,"ORIGINAL_SHARING_NOT_PROVEN");
        if(kind.equals("SHARE"))Stage1Exception.require(origin.has("receiverMchid")&&origin.has("receiverAccountHash"),409,"HISTORICAL_RECEIVER_MISSING");
        String task=d.db.id(),no="S45"+kind.charAt(0)+task;var request=origin.deepCopy();request.addProperty("kind",kind);request.addProperty("requestNo",no);request.addProperty("amountCent",amount);request.addProperty("finish",false);if(parent!=null)request.addProperty("originalShareRequest",parent);
        d.db.update("INSERT INTO li_p0_s45_task(id,business_key,order_id,batch_id,kind,request_no,request_json,request_hash,next_attempt_at,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,?,?)",task,key,order.get("id"),batch,kind,no,write(request),hash(request),d.clock.instant(),d.clock.instant(),d.clock.instant());
    }
    private boolean unresolved(String order,Set<String> kinds){
        for(var row:d.db.rows("SELECT kind,state,result_json,applied FROM li_p0_s45_task WHERE order_id=?",order))if(kinds.contains(id(row,"kind"))&&!Set.of("SUCCEEDED","ABANDONED").contains(id(row,"state"))){boolean partial="SHARE".equals(id(row,"kind"))&&"FAILED".equals(id(row,"state"))&&flag(row,"applied")&&row.get("result_json")!=null&&"PARTIAL_FINAL".equals(new Fields(row.get("result_json")).text("detailState",1,32,true))&&!new Fields(row.get("result_json")).bool("retryAllowed",false);if(!partial)return true;}
        return false;
    }
    /** Transactions only. Network calls (including provider capacity reads) are handled outside this method. */
    public void plan(Map<String,Object> order){
        d.locked(order,()->{
            d.gate.channel();String orderId=id(order,"id");
            var ledger=d.db.required("SELECT * FROM li_p0_s45_order WHERE order_id=?",orderId);
            if(ledger.get("current_batch_id")!=null){advance(order,id(ledger,"current_batch_id"));return null;}
            boolean late=d.db.count("SELECT COUNT(*) FROM li_p0_s45_obligation WHERE order_id=? AND kind='LATE_PAYMENT' AND state='READY'",orderId)>0;
            var origin=original(order,!late);
            // A sent/unknown sharing or finish request must be queried before choosing a refund route.
            if(unresolved(orderId,Set.of("SHARE","FINISH")))return null;
            autoFee(order);
            var ready=d.db.rows("SELECT x.* FROM li_p0_s45_obligation x LEFT JOIN li_p0_s45_aftersale a ON a.id=x.after_sale_id WHERE x.order_id=? AND x.state='READY' AND (a.id IS NULL OR a.dispute='NONE') ORDER BY x.id FOR UPDATE",orderId);
            if(!ready.isEmpty()){
                boolean exceptional="LATE_PAYMENT".equals(id(ready.getFirst(),"kind"));
                var members=ready.stream().filter(r->exceptional=="LATE_PAYMENT".equals(id(r,"kind"))).toList();
                long goods=members.stream().filter(r->"GOODS".equals(id(r,"kind"))).mapToLong(r->number(r,"amount_cent")).sum(),fee=members.stream().filter(r->"FEE".equals(id(r,"kind"))).mapToLong(r->number(r,"amount_cent")).sum(),lateAmount=members.stream().filter(r->"LATE_PAYMENT".equals(id(r,"kind"))).mapToLong(r->number(r,"amount_cent")).sum();
                var funds=d.facts(order,!exceptional).getAsJsonObject("funds");long target=exceptional?0:Stage45Money.target(number(order,"goods_cent"),refunded(orderId,"goods_cent"),goods,funds.get("rateBps").getAsLong());
                long required=Stage45Money.requiredReturn(actual(orderId,"SHARE"),actual(orderId,"RETURN"),target);
                String batch=d.db.id();
                d.db.update("INSERT INTO li_p0_s45_batch(id,order_id,payment_key,goods_cent,fee_cent,late_cent,target_commission,required_return,snapshot_hash,state,created_at) VALUES(?,?,?,?,?,?,?,?,?,'ACTIVE',?)",batch,orderId,hash(map("mode",origin.get("mode"),"transactionId",origin.get("transactionId"),"subMchid",origin.get("subMchid"),"platformMerchantId",origin.get("platformMerchantId"))),goods,fee,lateAmount,target,required,order.get("content_hash"),d.clock.instant());
                for(var member:members)d.db.cas(d.db.update("UPDATE li_p0_s45_obligation SET state='CURRENT',batch_id=? WHERE id=? AND state='READY' AND batch_id IS NULL",batch,member.get("id")));
                d.db.cas(d.db.update("UPDATE li_p0_s45_order SET current_batch_id=?,version=version+1,updated_at=? WHERE order_id=? AND current_batch_id IS NULL",batch,d.clock.instant(),orderId));
                advance(order,batch);return null;
            }
            if(d.active(orderId)>0)return null;
            var state=d.orders.state(order);boolean completed=state!=null&&"COMPLETED".equals(id(state,"phase")),ended=flag(ledger,"fulfillment_ended");
            if(!completed&&!ended)return null;
            long target=Stage45Money.commission(number(order,"goods_cent")-refunded(orderId,"goods_cent"),d.facts(order,true).getAsJsonObject("funds").get("rateBps").getAsLong()),net=actual(orderId,"SHARE")-actual(orderId,"RETURN");
            boolean finished=finishSource(orderId)!=null;
            if(finished){if(net<target)d.issue(orderId,"ORDER:"+orderId,"COMMISSION_GAP_AFTER_FINISH",map("targetCent",target,"actualCent",net));else if(net==target)d.db.update("UPDATE li_p0_s45_exception SET state='RESOLVED',updated_at=? WHERE order_id=? AND state='OPEN' AND code IN ('COMMISSION_GAP_AFTER_FINISH','COMMISSION_GAP')",d.clock.instant(),orderId);return null;}
            if(completed&&target>0&&net<target){
                if(d.db.count("SELECT COUNT(*) FROM li_p0_s45_task WHERE order_id=? AND kind='SHARE'",orderId)==0)createTask(order,"SHARE:"+orderId,"SHARE",null,target-net,origin,null);
                else d.issue(orderId,"ORDER:"+orderId,"COMMISSION_GAP",map("targetCent",target,"actualCent",net));
                return null;
            }
            if(net>target){d.issue(orderId,"ORDER:"+orderId,"COMMISSION_OVER_TARGET",map("targetCent",target,"actualCent",net));return null;}
            createTask(order,"FINISH:"+orderId,"FINISH",null,0,origin,null);return null;
        });
    }
    private void advance(Map<String,Object> order,String batch){
        var b=d.db.required("SELECT * FROM li_p0_s45_batch WHERE id=? FOR UPDATE",batch);String orderId=id(order,"id");
        if("SETTLED".equals(id(b,"state")))return;
        var refund=d.db.one("SELECT * FROM li_p0_s45_task WHERE batch_id=? AND kind='REFUND'",batch);
        if(refund!=null){
            if("SUCCEEDED".equals(id(refund,"state"))&&flag(refund,"applied")){
                d.db.update("UPDATE li_p0_s45_batch SET state='SETTLED',settled_at=? WHERE id=?",d.clock.instant(),batch);
                d.db.cas(d.db.update("UPDATE li_p0_s45_order SET current_batch_id=NULL,version=version+1,updated_at=? WHERE order_id=? AND current_batch_id=?",d.clock.instant(),orderId,batch));
                autoFee(order);
            }return;
        }
        boolean late=number(b,"late_cent")>0;var origin=original(order,!late);long need=number(b,"required_return");
        if(need>0){
            var returned=d.db.one("SELECT * FROM li_p0_s45_task WHERE batch_id=? AND kind='RETURN'",batch);
            if(returned==null){
                var share=d.db.required("SELECT * FROM li_p0_s45_task WHERE order_id=? AND kind='SHARE' AND (state='SUCCEEDED' OR (state='FAILED' AND applied=1 AND JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.detailState'))='PARTIAL_FINAL')) AND CAST(JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.succeededCent')) AS UNSIGNED)>0",orderId);
                Stage1Exception.require(actual(orderId,"SHARE")-actual(orderId,"RETURN")>=need,409,"RETURN_FULL_AMOUNT_UNAVAILABLE");
                createTask(order,"RETURN:"+batch,"RETURN",batch,need,origin,id(share,"request_no"));return;
            }
            if(!"SUCCEEDED".equals(id(returned,"state"))||new Fields(returned.get("result_json")).integer("succeededCent",0,Long.MAX_VALUE,true)<need)return;
        }
        createTask(order,"REFUND:"+batch,"REFUND",batch,number(b,"goods_cent")+number(b,"fee_cent")+number(b,"late_cent"),origin,null);
    }
    private void autoFee(Map<String,Object> order){
        String orderId=id(order,"id");long g=number(order,"goods_cent"),f=number(order,"freight_cent");
        if(f<=0||g<=0||d.sent(order)||d.delivered(order)||refunded(orderId,"goods_cent")!=g)return;
        long occupied=d.db.count("SELECT COALESCE(SUM(amount_cent),0) FROM li_p0_s45_obligation WHERE order_id=? AND kind='FEE'",orderId);
        if(occupied>=f)return;
        d.db.update("INSERT IGNORE INTO li_p0_s45_obligation(id,source_key,order_id,kind,amount_cent,basis_json,created_at) VALUES(?,?,?,'FEE',?,?,?)",d.db.id(),"AUTO_FEE:"+orderId,orderId,f-occupied,write(map("originalFeeCent",f,"goodsRefundedCent",g,"snapshotHash",order.get("content_hash"),"source","ALL_GOODS_REFUNDED_UNSENT")),d.clock.instant());
        d.event(order,"ORDER",orderId,"S45_FEE_READY","aftersale.read",true,map("amountCent",f-occupied));
    }
    private void checkCapacity(Map<String,Object> order,Map<String,Object> task,int newRequest){
        var request=object(task.get("request_json"));String kind=id(task,"kind");
        if(!Set.of("RETURN","REFUND").contains(kind))return;
        String parent=request.has("originalShareRequest")?request.get("originalShareRequest").getAsString():null;
        var cap=channel.capacity(request,kind,parent);
        Stage1Exception.require(channel.trusted(cap)&&cap.get("complete").getAsBoolean()&&request.get("transactionId").getAsString().equals(cap.get("transactionId").getAsString()),409,"CHANNEL_CAPACITY_UNKNOWN");
        int futureFee=0;
        if(kind.equals("REFUND")&&task.get("batch_id")!=null){
            var b=d.db.required("SELECT * FROM li_p0_s45_batch WHERE id=?",task.get("batch_id"));
            if(number(b,"late_cent")==0&&!d.sent(order)&&!d.delivered(order)&&number(order,"freight_cent")>0&&number(b,"fee_cent")==0&&refunded(id(order,"id"),"goods_cent")+number(b,"goods_cent")==number(order,"goods_cent")&&d.db.count("SELECT COALESCE(SUM(amount_cent),0) FROM li_p0_s45_obligation WHERE order_id=? AND kind='FEE'",order.get("id"))<number(order,"freight_cent"))futureFee=1;
        }
        Stage45Money.capacity(cap.get("used").getAsLong(),0,cap.get("external").getAsLong(),newRequest,futureFee);
        Instant paid=Instant.parse(request.get("paidAt").getAsString());long days=kind.equals("RETURN")?180:365;
        Stage1Exception.require(d.clock.instant().isBefore(paid.plus(Duration.ofDays(days))),409,"CHANNEL_PERIOD_EXPIRED");
    }
    /** Called outside business transaction; a durable MAYBE_SENT task precedes every outbound request. */
    public boolean executeOne(){
        d.gate.channel();
        var candidates=d.db.rows("SELECT id,order_id FROM li_p0_s45_task WHERE applied=0 AND next_attempt_at<=? AND state NOT IN ('FAILED','ABANDONED') AND (lease_until IS NULL OR lease_until<=?) ORDER BY next_attempt_at,id LIMIT 10",d.clock.instant(),d.clock.instant());
        for(var candidate:candidates){
            var order=d.orders.row(id(candidate,"order_id"));String token=UUID.randomUUID().toString();
            var claimed=d.locked(order,()->{
                var task=d.db.required("SELECT * FROM li_p0_s45_task WHERE id=? FOR UPDATE",candidate.get("id"));
                if(flag(task,"applied")||"FAILED".equals(id(task,"state"))||instant(task.get("next_attempt_at")).isAfter(d.clock.instant())||task.get("lease_until")!=null&&instant(task.get("lease_until")).isAfter(d.clock.instant()))return null;
                if("PENDING".equals(id(task,"state"))&&Set.of("SHARE","FINISH").contains(id(task,"kind"))&&(d.active(id(order,"id"))>0||d.db.count("SELECT COUNT(*) FROM li_p0_s45_obligation WHERE order_id=? AND state<>'SUCCEEDED'",order.get("id"))>0))return null;
                d.db.update("UPDATE li_p0_s45_task SET lease_token=?,lease_until=?,attempts=attempts+1,version=version+1,updated_at=? WHERE id=?",token,d.clock.instant().plusSeconds(45),d.clock.instant(),task.get("id"));return task;
            });
            if(claimed==null)continue;
            try{
                var request=object(claimed.get("request_json"));Stage1Exception.require(hash(request).equals(id(claimed,"request_hash")),409,"CHANNEL_REQUEST_CHANGED");
                JsonObject result;
                if("SUCCEEDED".equals(id(claimed,"state")))result=object(claimed.get("result_json"));
                else {
                    // Even a fresh persisted task is queried first, closing the crash-after-send/before-save window.
                    result=channel.query(request);
                    Stage1Exception.require(channel.trusted(result)&&hash(request).equals(hash(result.get("request"))),409,"CHANNEL_RESULT_UNTRUSTED");
                    boolean retry="RETRY".equals(id(claimed,"state"))&&result.has("retryAllowed")&&result.get("retryAllowed").getAsBoolean();
                    if("NOT_FOUND".equals(result.get("state").getAsString())||retry){
                        checkCapacity(order,claimed,"NOT_FOUND".equals(result.get("state").getAsString())?1:0);
                        boolean send=Boolean.TRUE.equals(d.locked(order,()->{
                            var current=d.db.required("SELECT * FROM li_p0_s45_task WHERE id=? FOR UPDATE",claimed.get("id"));
                            if(!token.equals(id(current,"lease_token"))||instant(current.get("lease_until")).isBefore(d.clock.instant()))return false;
                            if(Set.of("SHARE","FINISH").contains(id(current,"kind"))&&(d.active(id(order,"id"))>0||d.db.count("SELECT COUNT(*) FROM li_p0_s45_obligation WHERE order_id=? AND state<>'SUCCEEDED'",order.get("id"))>0))return false;
                            if("REFUND".equals(id(current,"kind"))){
                                var last=d.db.one("SELECT updated_at FROM li_p0_s45_task WHERE order_id=? AND kind='REFUND' AND id<>? AND state<>'PENDING' ORDER BY updated_at DESC LIMIT 1",order.get("id"),current.get("id"));
                                if(last!=null&&instant(last.get("updated_at")).plusSeconds(60).isAfter(d.clock.instant())){d.db.update("UPDATE li_p0_s45_task SET next_attempt_at=? WHERE id=?",instant(last.get("updated_at")).plusSeconds(60),current.get("id"));return false;}
                            }
                            d.db.update("UPDATE li_p0_s45_task SET state='MAYBE_SENT',version=version+1,updated_at=? WHERE id=?",d.clock.instant(),current.get("id"));return true;
                        }));
                        if(!send){releaseLease(claimed,token,null);return true;}
                        result=channel.send(request);
                    }
                }
                accept(order,claimed,token,result);
            }catch(RuntimeException failure){
                d.locked(order,()->{
                    var task=d.db.required("SELECT * FROM li_p0_s45_task WHERE id=? FOR UPDATE",claimed.get("id"));
                    if(token.equals(id(task,"lease_token"))){String code=failure instanceof Stage1Exception e?e.code():"CHANNEL_RESULT_UNKNOWN";if(code.startsWith("CHANNEL_CAPACITY")||code.equals("CHANNEL_PERIOD_EXPIRED"))d.issue(id(order,"id"),"TASK:"+id(task,"id"),code,map("taskId",id(task,"id")));
                        releaseLease(task,token,code);
                    }return null;
                });
            }
            return true;
        }return false;
    }
    private void releaseLease(Map<String,Object> task,String token,String code){
        d.db.update("UPDATE li_p0_s45_task SET lease_token=NULL,lease_until=NULL,next_attempt_at=GREATEST(next_attempt_at,?),last_error=?,updated_at=? WHERE id=? AND lease_token=?",d.clock.instant().plusSeconds(5),code,d.clock.instant(),task.get("id"),token);
    }
    private void accept(Map<String,Object> order,Map<String,Object> task,String token,JsonObject result){
        var request=object(task.get("request_json"));
        Stage1Exception.require(channel.trusted(result)&&request.get("mode").getAsString().equals(result.get("mode").getAsString())&&hash(request).equals(hash(result.get("request"))),409,"CHANNEL_RESULT_UNTRUSTED");
        var f=new Fields(result);String state=f.choice("state",Set.of("NOT_FOUND","UNKNOWN","PROCESSING","FAILED","SUCCEEDED"),true);long succeeded=f.integer("succeededCent",0,request.get("amountCent").getAsLong(),true);
        if("SHARE".equals(id(task,"kind"))){var details=Stage45ShareDetails.require(request,result);Stage1Exception.require(!state.equals("SUCCEEDED")||details.allSuccessful(),409,"CHANNEL_DETAILS_NOT_SUCCESSFUL");Stage1Exception.require(!"PARTIAL_FINAL".equals(f.text("detailState",1,32,true))||details.settled()&&!details.allSuccessful()&&state.equals("FAILED"),409,"CHANNEL_DETAILS_NOT_SUCCESSFUL");}
        if(state.equals("SUCCEEDED"))Stage1Exception.require(succeeded==request.get("amountCent").getAsLong()&&"SUCCESS".equals(f.text("detailState",1,32,true)),409,"CHANNEL_DETAILS_NOT_SUCCESSFUL");
        d.locked(order,()->{
            d.db.update("INSERT IGNORE INTO li_p0_s45_channel_evidence VALUES(?,?,'QUERY_OR_SEND',?,?,?)",d.db.id(),task.get("id"),write(result),hash(result),d.clock.instant());
            var current=d.db.required("SELECT * FROM li_p0_s45_task WHERE id=? FOR UPDATE",task.get("id"));
            if(!token.equals(id(current,"lease_token")))return null;
            if("SHARE".equals(id(current,"kind"))&&current.get("result_json")!=null&&!Stage45ShareDetails.monotonic(object(current.get("result_json")),result)){releaseLease(current,token,"STALE_CHANNEL_OBSERVATION");return null;}
            if("SUCCEEDED".equals(id(current,"state"))){if(!state.equals("SUCCEEDED")){releaseLease(current,token,"STALE_CHANNEL_OBSERVATION");return null;}}
            else {d.db.update("UPDATE li_p0_s45_task SET state=?,result_json=?,version=version+1,updated_at=? WHERE id=?",state.equals("NOT_FOUND")?"UNKNOWN":state,write(result),d.clock.instant(),task.get("id"));
                if(!state.equals(id(current,"state"))&&!Set.of("SUCCEEDED","NOT_FOUND").contains(state))d.event(order,"FUNDS",id(order,"id"),"S45_FUNDS_UPDATE","funds.read",false,map("kind",task.get("kind"),"state",state));
            }
            if(state.equals("FAILED"))d.issue(id(order,"id"),"ORIGINAL_ALREADY_UNFROZEN".equals(f.text("reason",0,64,false))?"ORDER:"+id(order,"id"):"TASK:"+id(task,"id"),"ORIGINAL_ALREADY_UNFROZEN".equals(f.text("reason",0,64,false))?"COMMISSION_GAP_AFTER_FINISH":"CHANNEL_FAILED",map("taskId",id(task,"id"),"reason",f.text("reason",0,64,false)));
            releaseLease(task,token,null);return null;
        });
        // Each local consequence is independently idempotent, after the provider fact has committed.
        if(state.equals("SUCCEEDED")||"SHARE".equals(id(task,"kind"))&&"PARTIAL_FINAL".equals(f.text("detailState",1,32,true)))apply(id(task,"id"));
    }
    public void apply(String taskId){
        var candidate=d.db.required("SELECT * FROM li_p0_s45_task WHERE id=?",taskId);Stage1Exception.require(("SUCCEEDED".equals(id(candidate,"state"))||"SHARE".equals(id(candidate,"kind"))&&"FAILED".equals(id(candidate,"state"))&&"PARTIAL_FINAL".equals(new Fields(candidate.get("result_json")).text("detailState",1,32,true)))&&channel.trusted(object(candidate.get("result_json"))),409,"CHANNEL_SUCCESS_NOT_PROVEN");var order=d.orders.row(id(candidate,"order_id"));
        if("REFUND".equals(id(candidate,"kind"))){
            var finalResult=object(candidate.get("result_json"));Instant successAt=finalResult.has("succeededAt")&&!finalResult.get("succeededAt").isJsonNull()?Instant.parse(finalResult.get("succeededAt").getAsString()):null;
            for(var member:d.db.rows("SELECT * FROM li_p0_s45_obligation WHERE batch_id=? ORDER BY id",candidate.get("batch_id")))d.locked(order,()->{
                var current=d.db.required("SELECT * FROM li_p0_s45_obligation WHERE id=? FOR UPDATE",member.get("id"));if("SUCCEEDED".equals(id(current,"state")))return null;
                d.db.update("UPDATE li_p0_s45_obligation SET state='SUCCEEDED',succeeded_at=? WHERE id=?",d.clock.instant(),member.get("id"));
                if("GOODS".equals(id(member,"kind"))&&member.get("after_sale_id")!=null){
                    String sale=id(member,"after_sale_id");var as=d.db.required("SELECT * FROM li_p0_s45_aftersale WHERE id=? FOR UPDATE",sale);
                    d.db.update("UPDATE li_p0_s45_aftersale SET state='COMPLETE',refunded_at=?,version=version+1,updated_at=? WHERE id=?",successAt,d.clock.instant(),sale);
                    d.db.update("UPDATE li_after_sale SET service_status='COMPLETE',actual_refund_price=?,refund_time=?,update_time=? WHERE id=?",money(number(member,"amount_cent")),successAt,d.clock.instant(),sale);
                    d.db.update("UPDATE li_p0_s45_share SET state='CONSUMED' WHERE after_sale_id=? AND state='HELD'",sale);
                    if(!flag(as,"delivered")&&!d.sent(order)&&!d.delivered(order))d.db.update("INSERT IGNORE INTO li_p0_s45_inventory_return(after_sale_id,order_id,order_item_id,quantity,created_at) VALUES(?,?,?,?,?)",sale,order.get("id"),as.get("item_id"),as.get("quantity"),d.clock.instant());
                    d.event(order,"AFTERSALE",sale,"S45_REFUND_SUCCESS","aftersale.read",true,map("afterSaleId",sale,"amountCent",member.get("amount_cent"),"obligationId",id(member,"id"),"kind","GOODS"));
                }else d.event(order,member.get("after_sale_id")==null?"ORDER":"AFTERSALE",member.get("after_sale_id")==null?id(order,"id"):id(member,"after_sale_id"),"S45_REFUND_SUCCESS","aftersale.read",true,map("amountCent",member.get("amount_cent"),"kind",member.get("kind"),"obligationId",id(member,"id")));
                d.db.update("INSERT IGNORE INTO li_p0_s45_effect VALUES(?,?,?,'OBLIGATION_APPLIED',?)","OBLIGATION:"+id(member,"id"),order.get("id"),member.get("id"),d.clock.instant());d.bump(id(order,"id"));return null;
            });
        }
        d.locked(order,()->{
            var task=d.db.required("SELECT * FROM li_p0_s45_task WHERE id=? FOR UPDATE",taskId);if(flag(task,"applied")||!("SUCCEEDED".equals(id(task,"state"))||"SHARE".equals(id(task,"kind"))&&"FAILED".equals(id(task,"state"))&&"PARTIAL_FINAL".equals(new Fields(task.get("result_json")).text("detailState",1,32,true))))return null;
            if("REFUND".equals(id(task,"kind"))&&d.db.count("SELECT COUNT(*) FROM li_p0_s45_obligation WHERE batch_id=? AND state<>'SUCCEEDED'",task.get("batch_id"))>0)return null;
            d.db.update("UPDATE li_p0_s45_task SET applied=1,version=version+1,updated_at=? WHERE id=?",d.clock.instant(),taskId);
            if(!d.delivered(order)&&d.db.count("SELECT COALESCE(SUM(num),0) FROM li_order_item WHERE order_sn=? AND delete_flag=0",order.get("sn"))==d.db.count("SELECT COALESCE(SUM(quantity),0) FROM li_p0_s45_aftersale WHERE order_id=? AND state='COMPLETE' AND delivered=0",order.get("id"))){
                d.db.update("UPDATE li_p0_s45_order SET fulfillment_ended=1 WHERE order_id=?",order.get("id"));d.db.update("UPDATE li_p0_s3_credential SET status='DISABLED' WHERE order_id=? AND status='ACTIVE'",order.get("id"));
            }
            d.event(order,"FUNDS",id(order,"id"),"S45_FUNDS_UPDATE","funds.read",false,map("kind",task.get("kind")));
            if("SUCCEEDED".equals(id(task,"state")))d.db.update("UPDATE li_p0_s45_exception SET state='RESOLVED',updated_at=? WHERE source_key=?",d.clock.instant(),"TASK:"+taskId);
            return null;
        });
    }
    public Object recover(String taskId,Object body){
        var f=new Fields(body).only("operationId","expectedVersion","reason","retry");String reason=f.text("reason",2,500,true);long expected=f.version();boolean retry=f.bool("retry",false);
        var task=d.db.required("SELECT * FROM li_p0_s45_task WHERE id=?",taskId);var order=d.order(id(task,"order_id"),"funds.recover");
        return d.command(order,"funds.recover",taskId,body,()->{
            var current=d.db.required("SELECT * FROM li_p0_s45_task WHERE id=? FOR UPDATE",taskId);d.version(current,expected);
            Stage1Exception.require(!flag(current,"applied"),409,"TASK_ALREADY_APPLIED");
            if(retry){Stage1Exception.require("FAILED".equals(id(current,"state"))&&current.get("result_json")!=null&&new Fields(current.get("result_json")).bool("retryAllowed",false),409,"CHANNEL_RETRY_NOT_ALLOWED");}
            d.db.update("UPDATE li_p0_s45_task SET state=IF(?,'RETRY',IF(state='FAILED','UNKNOWN',state)),version=version+1,next_attempt_at=?,lease_token=NULL,lease_until=NULL WHERE id=?",retry,d.clock.instant(),taskId);
            d.audit(order,"TASK",taskId,"funds.recover",reason,map("state",current.get("state")),map("retry",retry,"requestNo",current.get("request_no")));return map("taskId",taskId,"requestNo",current.get("request_no"),"state","QUEUED_FOR_ORIGINAL_QUERY","version",expected+1);
        });
    }
    public Object lateRefund(String orderId,Object body){
        var f=new Fields(body).only("operationId","reason");String reason=f.text("reason",2,500,true);var order=d.order(orderId,"funds.recover");
        return d.command(order,"funds.lateRefund",orderId,body,()->{
            var paid=original(order,false);var state=d.db.required("SELECT status,protection,close_status FROM li_p0_trade_state WHERE trade_id=?",order.get("trade_id"));
            Stage1Exception.require(!"PAID".equals(id(state,"status"))&&state.get("protection")!=null,409,"LATE_REFUND_NOT_APPLICABLE");
            Stage1Exception.require(actual(orderId,"SHARE")==0,409,"LATE_PAYMENT_FUNDS_CONFLICT");
            long amount=paid.get("paidCent").getAsLong();d.db.update("INSERT IGNORE INTO li_p0_s45_obligation(id,source_key,order_id,kind,amount_cent,basis_json,created_at) VALUES(?,?,?,'LATE_PAYMENT',?,?,?)",d.db.id(),"LATE:"+orderId,orderId,amount,write(map("reason",reason,"original",paid)),d.clock.instant());
            d.audit(order,"ORDER",orderId,"late-refund",reason,null,map("amountCent",amount));return map("orderId",orderId,"amountCent",amount,"state","READY","originalFulfillmentUnchanged",true);
        });
    }
}
