package cn.lili.modules.community.p0.stage45;

import cn.lili.modules.community.p0.support.*;
import com.google.gson.JsonObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service @ConditionalOnProperty(name="p0.stage45.enabled",havingValue="true")
public class Stage45FundViews {
    final Stage45Data d;final Stage45Funds funds;
    public Stage45FundViews(Stage45Data d,Stage45Funds funds){this.d=d;this.funds=funds;}
    public Map<String,Object> summary(Map<String,Object> order,boolean detail){
        String orderId=id(order,"id"),reason=null;boolean trusted=true;
        try{funds.original(order,false);}catch(Stage1Exception e){if(e.status()>=500)throw e;trusted=false;reason=e.code();}
        long goodsRefund=funds.refunded(orderId,"goods_cent"),feeRefund=funds.refunded(orderId,"fee_cent"),late=funds.refunded(orderId,"late_cent"),shared=funds.actual(orderId,"SHARE"),returned=funds.actual(orderId,"RETURN");
        Long theoretical=null;try{var snapshot=d.facts(order,false).getAsJsonObject("funds");theoretical=Stage45Money.commission(number(order,"goods_cent")-goodsRefund,snapshot.get("rateBps").getAsLong());}catch(Stage1Exception e){if(e.status()>=500)throw e;reason=e.code();}
        var ledger=d.db.one("SELECT * FROM li_p0_s45_order WHERE order_id=?",orderId);var state=d.orders.state(order);
        var payment=d.db.required("SELECT status,payment_status,close_status,protection FROM li_p0_trade_state WHERE trade_id=?",order.get("trade_id"));
        long pending=d.db.count("SELECT COALESCE(SUM(amount_cent),0) FROM li_p0_s45_obligation WHERE order_id=? AND state<>'SUCCEEDED'",orderId),exceptions=d.db.count("SELECT COUNT(*) FROM li_p0_s45_exception WHERE order_id=? AND state='OPEN'",orderId);
        Long net=trusted?number(order,"amount_cent")-goodsRefund-feeRefund-late-shared+returned:null;
        String finishSource=funds.finishSource(orderId);boolean finish=finishSource!=null;
        long unfinished=d.db.count("SELECT COUNT(*) FROM li_p0_s45_task WHERE order_id=? AND applied=0 AND state<>'ABANDONED'",orderId);String progress=exceptions>0||payment.get("protection")!=null||reason!=null&&"SUCCEEDED".equals(payment.get("payment_status"))?"FUNDS_EXCEPTION":pending>0||unfinished>0?"FUNDS_PENDING":finish?"FUNDS_FINISHED":goodsRefund+feeRefund+late>0?"FUNDS_REFUNDED":"FUNDS_WAITING";
        var view=map("orderId",orderId,"orderSn",id(order,"sn"),"tradeId",id(order,"trade_id"),"tradeSn",id(order,"trade_sn"),"storeId",id(order,"store_id"),"storeName",order.get("store_name"),"mode","MOCK","currency","CNY",
            "goodsCent",order.get("goods_cent"),"freightCent",order.get("freight_cent"),"orderAmountCent",order.get("amount_cent"),"paidCent",trusted?order.get("amount_cent"):null,"paymentState",payment.get("payment_status"),"tradeState",payment.get("status"),"protection",payment.get("protection"),
            "goodsRefundCent",trusted?goodsRefund:null,"feeRefundCent",trusted?feeRefund:null,"lateRefundCent",trusted?late:null,"sharedCent",trusted?shared:null,"returnedCent",trusted?returned:null,"actualCommissionCent",trusted?shared-returned:null,"targetCommissionCent",theoretical,
            "netCent",net,"feeCent",null,"feeState","EVIDENCE_PENDING","pendingObligationCent",pending,"activeAfterSales",d.active(orderId),"exceptionCount",exceptions,"fundProgress",progress,"finished",finish,"finishSource",finishSource,"fulfillmentEnded",ledger!=null&&flag(ledger,"fulfillment_ended"),"fulfillmentPhase",state==null?null:state.get("phase"),"blockReason",reason,"version",ledger==null?0:ledger.get("version"),"createdAt",instant(order.get("create_time")),"basis","本订单可信资金事实净额；不代表钱包、提现或银行到账");
        if(d.context.current().domain()==cn.lili.common.security.session.SessionDomain.MANAGER){JsonObject paid=null;try{paid=d.eligibility.trustedPaid(id(order,"trade_id"));}catch(Stage1Exception e){if(e.status()>=500)throw e;}view.put("tradePaidCent",paid==null?null:paid.get("amountCent"));view.put("tradeChildCount",paid==null?null:paid.getAsJsonArray("subOrders").size());}
        if(detail){
            view.put("snapshot",historical(order));view.put("tasks",d.db.rows("SELECT * FROM li_p0_s45_task WHERE order_id=? ORDER BY id",orderId).stream().map(this::task).toList());
            view.put("batches",d.db.rows("SELECT id,goods_cent,fee_cent,late_cent,target_commission,required_return,state,created_at,settled_at FROM li_p0_s45_batch WHERE order_id=? ORDER BY id",orderId).stream().map(r->map("id",id(r,"id"),"goodsCent",r.get("goods_cent"),"feeCent",r.get("fee_cent"),"lateCent",r.get("late_cent"),"targetCommissionCent",r.get("target_commission"),"requiredReturnCent",r.get("required_return"),"state",r.get("state"),"createdAt",instant(r.get("created_at")),"settledAt",instant(r.get("settled_at")))).toList());
            view.put("obligations",d.db.rows("SELECT * FROM li_p0_s45_obligation WHERE order_id=? ORDER BY id",orderId).stream().map(r->map("id",id(r,"id"),"afterSaleId",id(r,"after_sale_id"),"kind",r.get("kind"),"amountCent",r.get("amount_cent"),"quantity",r.get("quantity"),"applicationAt",r.get("after_sale_id")==null?null:instant(d.db.required("SELECT created_at FROM li_p0_s45_aftersale WHERE id=?",r.get("after_sale_id")).get("created_at")),"approvedAt",r.get("after_sale_id")==null?null:instant(d.db.required("SELECT approved_at FROM li_p0_s45_aftersale WHERE id=?",r.get("after_sale_id")).get("approved_at")),"state",r.get("state"),"batchId",id(r,"batch_id"),"createdAt",instant(r.get("created_at")),"succeededAt",instant(r.get("succeeded_at")))).toList());
            view.put("exceptions",d.db.rows("SELECT id,code,state,created_at,updated_at FROM li_p0_s45_exception WHERE order_id=? ORDER BY id",orderId).stream().map(this::exception).toList());
        }return view;
    }
    private Object historical(Map<String,Object> order){
        var snapshot=object(order.get("payload_json"));if(!snapshot.has("funds")||!snapshot.get("funds").isJsonObject())return null;var f=snapshot.getAsJsonObject("funds");var c=f.has("channel")&&f.get("channel").isJsonObject()?f.getAsJsonObject("channel"):null;
        return map("snapshotHash",order.get("content_hash"),"ruleId",f.get("ruleId"),"ruleRevision",f.get("revision"),"agreementId",f.get("agreementId"),"rateBps",f.get("rateBps"),"originalCommissionCent",f.get("commissionCent"),"subMchid",c!=null&&c.has("sub_mchid")&&!c.get("sub_mchid").isJsonNull()?mask(c.get("sub_mchid").getAsString()):null,"platformMerchantId",c!=null&&c.has("provider_mchid")&&!c.get("provider_mchid").isJsonNull()?mask(c.get("provider_mchid").getAsString()):null,"receiverEvidence",c!=null&&c.has("receiverAccountHash")?"HISTORICAL_SNAPSHOT":"MISSING","feesExcludedFromCommission",true);
    }
    private static String mask(String s){return s==null?null:s.length()<5?"****":s.substring(0,2)+"****"+s.substring(s.length()-2);}
    public static String explanation(String code){
        if(code==null)return null;return switch(code){
            case "CHANNEL_CAPACITY_EXHAUSTED"->"原交易或原分账单的渠道次数容量不足，未退义务仍保留。";
            case "CHANNEL_CAPACITY_UNKNOWN"->"缺少完整渠道次数证据，暂不发送新资金请求。";
            case "CHANNEL_PERIOD_EXPIRED"->"原交易已超出本渠道动作适用期限，需核实合法承接方式。";
            case "COMMISSION_GAP_AFTER_FINISH","ORIGINAL_ALREADY_UNFROZEN"->"原交易已完结或解冻，仍有佣金缺口；禁止盲目补分。";
            case "COMMISSION_GAP"->"原规则佣金与当前实分净额未收敛，需核对原分账及退款。";
            case "COMMISSION_OVER_TARGET"->"当前实分净额高于退款后目标，需核对必要回退。";
            case "FAILURE","CHANNEL_FAILED"->"渠道已明确失败，原义务保留；仅按原请求允许的方式恢复。";
            case "INSUFFICIENT"->"原出资主体可用资金不足，义务保留，不自动垫付或改为线下。";
            case "CHANNEL_RESULT_UNKNOWN","ORIGINAL_FINISH_UNKNOWN"->"原渠道结果仍待核实，先查询原请求，不新建单号。";
            case "ORIGINAL_SHARING_NOT_PROVEN"->"原付款缺少分账标识证据，不能补造标识后执行。";
            case "HISTORICAL_RECEIVER_MISSING"->"原订单缺少可信佣金接收关系，不能使用当前配置补造。";
            default->"原交易或本地处理条件待核实；原任务和义务继续保留。";
        };
    }
    private Map<String,Object> exception(Map<String,Object> row){
        String id=id(row,"id");var observations=d.db.rows("SELECT first_observed_at,last_observed_at,observations FROM li_p0_s45_exception_observation WHERE exception_id=? ORDER BY last_observed_at DESC",id);
        return map("id",id,"code",row.get("code"),"explanation",explanation(id(row,"code")),"state",row.get("state"),"createdAt",instant(row.get("created_at")),"updatedAt",instant(row.get("updated_at")),"observationCount",observations.stream().mapToLong(r->number(r,"observations")).sum(),"observations",observations.stream().map(r->map("firstAt",instant(r.get("first_observed_at")),"lastAt",instant(r.get("last_observed_at")),"count",r.get("observations"))).toList());
    }

    public Map<String,Object> task(Map<String,Object> r){
        var request=object(r.get("request_json"));var result=r.get("result_json")==null?null:object(r.get("result_json"));
        return map("id",id(r,"id"),"orderId",id(r,"order_id"),"batchId",id(r,"batch_id"),"kind",r.get("kind"),"requestNo",r.get("request_no"),"amountCent",request.get("amountCent"),"state",r.get("state"),"applied",flag(r,"applied"),"version",r.get("version"),"attempts",r.get("attempts"),"reason",explanation(r.get("last_error")!=null?id(r,"last_error"):result!=null&&result.has("reason")&&!result.get("reason").isJsonNull()?result.get("reason").getAsString():null),"succeededCent",result==null?null:result.get("succeededCent"),"detailState",result==null?null:result.get("detailState"),"acceptedAt",result==null?null:result.get("acceptedAt"),"succeededAt",result==null?null:result.get("succeededAt"),"observedAt",result==null?null:result.get("observedAt"),"createdAt",instant(r.get("created_at")),"updatedAt",instant(r.get("updated_at")),"canRecover",!flag(r,"applied")&&d.auth.permissions(d.context.current().user()).contains("funds.recover"),"retryAllowed",result!=null&&new Fields(result).bool("retryAllowed",false),"receiverDetails",result!=null&&result.has("receivers")?result.getAsJsonArray("receivers").asList().stream().map(raw->{var receiver=raw.getAsJsonObject();return map("detailId",receiver.get("detailId"),"account",mask(receiver.get("account").getAsString()),"amountCent",receiver.get("amountCent"),"state",receiver.get("state"),"succeededAt",receiver.get("succeededAt"));}).toList():List.of());
    }
    @Transactional(readOnly=true)
    public Object detail(String id){return summary(d.order(id,"funds.read"),true);}
    @Transactional(readOnly=true)
    public Object taskDetail(String id){var task=d.db.required("SELECT * FROM li_p0_s45_task WHERE id=?",id);var order=d.order(id(task,"order_id"),"funds.read");return map("task",task(task),"order",summary(order,true),"history",d.db.rows("SELECT action,reason,created_at FROM li_p0_s45_audit WHERE subject_type='TASK' AND subject_id=? ORDER BY id",id).stream().map(r->map("action",r.get("action"),"reason",r.get("reason"),"at",instant(r.get("created_at")))).toList());}
    @Transactional(readOnly=true)
    public Object list(Map<String,String> query,boolean exceptionsOnly){
        var actor=d.context.current();Stage1Exception.require(d.auth.permissions(actor.user()).contains("funds.read"),403,"FORBIDDEN");var args=new ArrayList<Object>();String where=d.auth.scope(actor,"o",args);
        String kind=query.get("kind"),state=query.get("state"),q=query.get("q");
        boolean taskList=kind!=null&&!kind.isBlank()&&!kind.equals("PAYMENT");
        if(taskList){Stage1Exception.require(Set.of("REFUND","RETURN","SHARE","FINISH").contains(kind),422,"QUERY_INVALID");where+=" AND t.kind=?";args.add(kind);}
        String fundState=query.getOrDefault("fundState","");
        if(!fundState.isBlank()){Stage1Exception.require(!taskList&&Set.of("PENDING","EXCEPTION","REFUNDED","FINISHED").contains(fundState),422,"QUERY_INVALID");where+=switch(fundState){
            case "PENDING"->" AND (EXISTS(SELECT 1 FROM li_p0_s45_obligation x WHERE x.order_id=o.id AND x.state<>'SUCCEEDED') OR EXISTS(SELECT 1 FROM li_p0_s45_task x WHERE x.order_id=o.id AND x.applied=0 AND x.state<>'ABANDONED'))";
            case "EXCEPTION"->" AND (ts.protection IS NOT NULL OR EXISTS(SELECT 1 FROM li_p0_s45_exception x WHERE x.order_id=o.id AND x.state='OPEN'))";
            case "REFUNDED"->" AND EXISTS(SELECT 1 FROM li_p0_s45_task x WHERE x.order_id=o.id AND x.kind='REFUND' AND x.state='SUCCEEDED')";
            default->" AND EXISTS(SELECT 1 FROM li_p0_s45_task x WHERE x.order_id=o.id AND ((x.kind='FINISH' AND x.state='SUCCEEDED') OR (x.kind='SHARE' AND x.applied=1 AND JSON_UNQUOTE(JSON_EXTRACT(x.result_json,'$.originalFinish.state'))='SUCCEEDED')))";
        };}
        if(exceptionsOnly)where+=" AND EXISTS(SELECT 1 FROM li_p0_s45_exception x WHERE x.order_id=o.id AND x.state='OPEN')";
        if(state!=null&&!state.isBlank()){Stage1Exception.require(state.matches("[A-Z_]{1,24}"),422,"QUERY_INVALID");where+=taskList?" AND t.state=?":" AND ts.payment_status=?";args.add(state);}
        if(q!=null&&!q.isBlank()){Stage1Exception.require(q.length()<=80,422,"QUERY_INVALID");String like="%"+q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";where+=" AND (o.sn LIKE ? OR o.trade_sn LIKE ? OR o.store_name LIKE ?)";args.add(like);args.add(like);args.add(like);}
        for(String key:List.of("from","to"))if(query.containsKey(key)&&!query.get(key).isBlank()){try{var at=java.time.Instant.parse(query.get(key));where+=" AND o.create_time"+(key.equals("from")?">=":"<")+"?";args.add(at);}catch(java.time.DateTimeException e){throw new Stage1Exception(422,"QUERY_INVALID");}}
        int page=Stage45Data.page(query,"pageNumber",1,1000000),size=Stage45Data.page(query,"pageSize",20,50);
        String from=" FROM li_order o JOIN li_p0_order_snapshot s ON s.order_id=o.id JOIN li_p0_trade_state ts ON ts.trade_id=s.trade_id "+(taskList?"JOIN li_p0_s45_task t ON t.order_id=o.id ":"")+"WHERE o.delete_flag=0 AND "+where;
        long total=d.db.count("SELECT COUNT(*)"+from,args.toArray());args.add(size);args.add((long)(page-1)*size);
        List<?> records=taskList?d.db.rows("SELECT t.*,o.sn,o.store_name"+from+" ORDER BY t.id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(r->{var v=task(r);v.put("orderSn",r.get("sn"));v.put("storeName",r.get("store_name"));return v;}).toList():d.db.rows("SELECT o.id"+from+" ORDER BY o.id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(r->summary(d.orders.row(id(r,"id")),false)).toList();
        d.context.recheck(actor);return Stage45Data.page(records,total,page,size);
    }
}
