package cn.lili.modules.community.p0.stage45;
import cn.lili.modules.community.p0.support.*;import com.google.gson.*;import java.math.BigDecimal;import java.time.*;import java.util.*;import static cn.lili.modules.community.p0.support.Stage1Json.*;
/** Read-only internal checks. Findings preserve evidence; they never repair monetary facts. */
public final class Stage45Consistency {
 private Stage45Consistency(){}
 public static final Map<String,String> LABELS=Map.of(
 "PARENT_CHILD_CONSERVATION","原合单、子单或商品费用合计不守恒",
 "MISSING_PAYMENT","原付款证据缺失或尚未可信收敛",
 "DUPLICATE_PAYMENT","同一渠道子交易被多个订单引用",
 "REFUND_LIMIT","成功商品、费用或异常付款退款超出原额度",
 "SHARE_OR_RETURN_LIMIT","实分超出原佣金或实退超出实分",
 "ORPHAN_RECORD","资金记录缺少原订单、批次或义务关联",
 "STORE_SUBTRANSACTION_MISMATCH","Store、子订单或原渠道子交易关联不一致",
 "SUMMARY_NOT_CONVERGED","原生资金摘要与当前可信事实未收敛");
 private static void add(List<Map<String,Object>> out,String id,String code,boolean pending){out.add(map("orderId",id,"code",code,"severity",pending?"PENDING":"DIFFERENCE","explanation",LABELS.get(code)));}
 public static List<Map<String,Object>> inspect(JsonObject input){
  var f=new Fields(input);String id=f.id("orderId",true);var out=new ArrayList<Map<String,Object>>();
  for(var pair:Map.of("parentMismatch","PARENT_CHILD_CONSERVATION","missingPayment","MISSING_PAYMENT","duplicatePayment","DUPLICATE_PAYMENT","orphan","ORPHAN_RECORD","identityMismatch","STORE_SUBTRANSACTION_MISMATCH").entrySet())if(f.bool(pair.getKey(),false))add(out,id,pair.getValue(),pair.getKey().equals("missingPayment")&&f.bool("paymentPending",false));
  if(!f.bool("missingPayment",false)){
   long paid=f.integer("paidCent",0,Long.MAX_VALUE,true),g=f.integer("goodsCent",0,Long.MAX_VALUE,true),fee=f.integer("freightCent",0,Long.MAX_VALUE,true),gr=f.integer("goodsRefundCent",0,Long.MAX_VALUE,true),fr=f.integer("feeRefundCent",0,Long.MAX_VALUE,true),late=f.integer("lateRefundCent",0,Long.MAX_VALUE,true),share=f.integer("sharedCent",0,Long.MAX_VALUE,true),back=f.integer("returnedCent",0,Long.MAX_VALUE,true),c0=f.integer("originalCommissionCent",0,Long.MAX_VALUE,true);
   if(gr>g||fr>fee||BigDecimal.valueOf(gr).add(BigDecimal.valueOf(fr)).add(BigDecimal.valueOf(late)).compareTo(BigDecimal.valueOf(paid))>0)add(out,id,"REFUND_LIMIT",false);
   if(share>c0||back>share)add(out,id,"SHARE_OR_RETURN_LIMIT",false);
   boolean pending=f.bool("projectionPending",false);if(pending||f.integer("projectedGrossCent",Long.MIN_VALUE,Long.MAX_VALUE,true)!=paid-gr-fr-late||f.integer("projectedCommissionCent",Long.MIN_VALUE,Long.MAX_VALUE,true)!=share-back||f.integer("projectedNetCent",Long.MIN_VALUE,Long.MAX_VALUE,true)!=paid-gr-fr-late-share+back)add(out,id,"SUMMARY_NOT_CONVERGED",pending);
  }return out;
 }
 private static long cent(Object value){return new BigDecimal(String.valueOf(value)).movePointRight(2).longValueExact();}
 public static Map<String,Object> scan(Stage45Data d,Stage45Funds funds,String account,LocalDate date,Instant cutoff){
  Instant start=date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),end=date.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
  var checkpoints=new ArrayList<JsonObject>();var seen=new HashMap<String,JsonObject>();var proofCache=new HashMap<String,JsonObject>();var findings=new ArrayList<Map<String,Object>>();int skippedUnpaid=0;
  for(var raw:d.db.rows("SELECT s.order_id FROM li_p0_order_snapshot s JOIN li_order o ON o.id=s.order_id WHERE o.create_time<=? AND ((o.create_time>=? AND o.create_time<?) OR EXISTS(SELECT 1 FROM li_p0_s45_task t WHERE t.order_id=s.order_id AND t.created_at>=? AND t.created_at<? AND t.created_at<=?)) ORDER BY s.order_id",cutoff,start,end,start,end,cutoff)){
   String id=id(raw,"order_id");var order=d.orders.row(id);var snapshot=object(order.get("payload_json"));var sf=snapshot.getAsJsonObject("funds");if(sf==null||!sf.has("channel")||sf.get("channel").isJsonNull()||!sf.getAsJsonObject("channel").has("provider_mchid")){add(findings,id,"ORPHAN_RECORD",true);continue;}if(!account.equals(new Fields(sf.get("channel")).text("provider_mchid",1,64,true)))continue;
   var local=d.db.one("SELECT t.payment_status,p.status FROM li_p0_trade_state t LEFT JOIN li_p0_payment p ON p.trade_id=t.trade_id WHERE t.trade_id=?",order.get("trade_id"));if(local==null){add(findings,id,"ORPHAN_RECORD",false);continue;}
   if(!"SUCCEEDED".equals(local.get("payment_status"))&&!"SUCCEEDED".equals(local.get("status"))){skippedUnpaid++;continue;}
   var c=object(map("orderId",id,"paidCent",number(order,"amount_cent"),"goodsCent",number(order,"goods_cent"),"freightCent",number(order,"freight_cent"),"originalCommissionCent",sf.get("commissionCent"),"parentMismatch",false,"missingPayment",false,"duplicatePayment",false,"orphan",false,"identityMismatch",false));
   c.add("snapshotHash",object(map("v",order.get("content_hash"))).get("v"));JsonObject origin=null,paid=null;String trade=id(order,"trade_id");
   try{paid=proofCache.get(trade);if(paid==null){paid=d.eligibility.trustedPaid(trade);proofCache.put(trade,paid);}origin=funds.original(order,false);}catch(Stage1Exception e){if(e.status()>=500)throw e;c.addProperty("missingPayment",true);c.addProperty("paymentPending",!"SUCCEEDED".equals(local.get("payment_status")));}
   c.addProperty("paymentProofHash",paid==null?null:hash(paid));
   long children=d.db.count("SELECT COALESCE(SUM(amount_cent),0) FROM li_p0_order_snapshot WHERE trade_id=?",trade);var nativeTrade=d.db.one("SELECT flow_price FROM li_trade WHERE id=?",trade);long nativeTotal=nativeTrade==null?-1:cent(nativeTrade.get("flow_price"));if(nativeTrade==null)c.addProperty("orphan",true);
   c.addProperty("parentMismatch",children!=nativeTotal||paid!=null&&paid.get("amountCent").getAsLong()!=children||number(order,"goods_cent")+number(order,"freight_cent")!=number(order,"amount_cent")||cent(order.get("flow_price"))!=number(order,"amount_cent"));
   if(origin!=null){String transaction=origin.get("transactionId").getAsString();var old=seen.putIfAbsent(transaction,c);if(old!=null&&!old.get("orderId").getAsString().equals(id)){old.addProperty("duplicatePayment",true);c.addProperty("duplicatePayment",true);}}
   long gr=0,fr=0,late=0,share=0,back=0;Instant latestFact=paid==null?Instant.MIN:Instant.parse(paid.get("paidAt").getAsString());
   for(var t:d.db.rows("SELECT * FROM li_p0_s45_task WHERE order_id=? AND created_at<=? ORDER BY id",id,cutoff)){
    var request=object(t.get("request_json"));if(!id.equals(new Fields(request).text("orderId",1,19,true))||!id(order,"store_id").equals(new Fields(request).text("storeId",1,19,true))||origin!=null&&!origin.get("transactionId").equals(request.get("transactionId")))c.addProperty("identityMismatch",true);
    var e=d.db.one("SELECT result_json,observed_at FROM li_p0_s45_channel_evidence WHERE task_id=? AND observed_at<=? AND source='QUERY_OR_SEND' ORDER BY observed_at DESC,id DESC LIMIT 1",t.get("id"),cutoff);
    if(e==null)continue;var result=object(e.get("result_json"));if(!funds.channel.trusted(result)||!hash(request).equals(hash(result.get("request")))){c.addProperty("identityMismatch",true);continue;}Instant observed=instant(e.get("observed_at"));if(observed.isAfter(latestFact))latestFact=observed;long amount=new Fields(result).integer("succeededCent",0,Long.MAX_VALUE,true);
    switch(id(t,"kind")){case "SHARE"->share=Math.addExact(share,amount);case "RETURN"->back=Math.addExact(back,amount);case "REFUND"->{if(!"SUCCEEDED".equals(new Fields(result).text("state",1,24,true)))continue;var batch=t.get("batch_id")==null?null:d.db.one("SELECT * FROM li_p0_s45_batch WHERE id=? AND order_id=?",t.get("batch_id"),id);if(batch==null){c.addProperty("orphan",true);continue;}gr=Math.addExact(gr,number(batch,"goods_cent"));fr=Math.addExact(fr,number(batch,"fee_cent"));late=Math.addExact(late,number(batch,"late_cent"));}default->{}}
   }
   if(d.db.count("SELECT COUNT(*) FROM li_p0_s45_obligation x LEFT JOIN li_p0_s45_aftersale a ON a.id=x.after_sale_id WHERE x.order_id=? AND x.after_sale_id IS NOT NULL AND (a.id IS NULL OR a.order_id<>x.order_id)",id)>0)c.addProperty("orphan",true);
   var flow=d.db.required("SELECT COUNT(*) AS n,COALESCE(SUM(final_price),0) AS gross,COALESCE(SUM(commission_price),0) AS commission,COALESCE(SUM(bill_price),0) AS net,MAX(p0_projected_at) AS last_at,SUM(IF(p0_projected_at IS NULL OR p0_projected_at>?,1,0)) AS unavailable FROM li_store_flow WHERE order_sn=? AND p0_source_key IS NOT NULL",cutoff,order.get("sn"));
   boolean projectionPending=number(flow,"n")==0||flow.get("unavailable")!=null&&number(flow,"unavailable")>0||flow.get("last_at")==null||instant(flow.get("last_at")).isBefore(latestFact);
   c.addProperty("goodsRefundCent",gr);c.addProperty("feeRefundCent",fr);c.addProperty("lateRefundCent",late);c.addProperty("sharedCent",share);c.addProperty("returnedCent",back);c.addProperty("projectionPending",projectionPending);c.addProperty("projectedGrossCent",cent(flow.get("gross")));c.addProperty("projectedCommissionCent",cent(flow.get("commission")));c.addProperty("projectedNetCent",cent(flow.get("net")));checkpoints.add(c);
  }
  for(var c:checkpoints)findings.addAll(inspect(c));
  for(var orphan:d.db.rows("SELECT t.id,t.order_id FROM li_p0_s45_task t LEFT JOIN li_order o ON o.id=t.order_id LEFT JOIN li_p0_order_snapshot s ON s.order_id=t.order_id WHERE (o.id IS NULL OR s.order_id IS NULL) AND JSON_UNQUOTE(JSON_EXTRACT(t.request_json,'$.platformMerchantId'))=? AND t.created_at>=? AND t.created_at<? AND t.created_at<=?",account,start,end,cutoff))add(findings,id(orphan,"order_id"),"ORPHAN_RECORD",false);
  return map("scope","ORIGINAL_ORDERS_AND_FUNDS_REQUESTS_IN_BUSINESS_DAY","cutoff",cutoff,"checkedOrders",checkpoints.size(),"checkpointHash",hash(checkpoints),"checkpoints",checkpoints,"skippedUnpaidOrders",skippedUnpaid,"differences",findings.stream().filter(f->f.get("severity").equals("DIFFERENCE")).count(),"pending",findings.stream().filter(f->f.get("severity").equals("PENDING")).count(),"checks",findings,"projectionRule","缺投影时点、晚于截至时点或落后资金证据均记待证；不填零通过");
 }
}
