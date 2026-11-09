package cn.lili.modules.community.p0.stage45;
import cn.lili.modules.community.p0.stage2.*;import cn.lili.modules.community.p0.support.*;import com.google.gson.*;import java.time.*;import java.util.*;import static cn.lili.modules.community.p0.support.Stage1Json.*;
/** Official platform combine query -> common paid evidence. Explicit construction only; no runtime REAL bean.
 * Source: https://pay.wechatpay.cn/doc/v3/partner/4012761049 */
public final class Stage45RealPaidSource {
 private final Stage45WechatEvidence verifier;
 public Stage45RealPaidSource(Stage45WechatEvidence verifier){this.verifier=verifier;}
 public JsonObject normalize(JsonObject request,JsonObject proof){
  var f=new Fields(request);Stage1Exception.require("REAL".equals(f.text("mode",1,8,true)),409,"SC23_MODE_MISMATCH");String combine=f.text("combineNo",1,32,true);
  Stage1Exception.require(combine.matches("[A-Za-z0-9_*-]{1,32}"),409,"REAL_ORIGINAL_REQUEST_MISSING");
  Stage1Exception.require(new Fields(proof).integer("httpStatus",200,200,true)==200,409,"REAL_SOURCE_NOT_SUCCESSFUL");var response=verifier.verify(proof,"GET","/v3/combine-transactions/out-trade-no/"+combine);var p=new Fields(response);
  Stage1Exception.require(p.text("combine_out_trade_no",1,32,true).equals(combine)&&p.text("combine_appid",1,32,true).equals(f.text("applicationId",1,32,true))&&p.text("combine_mchid",1,32,true).equals(f.text("platformMerchantId",1,32,true)),409,"SC23_IDENTITY_MISMATCH");
  String payer=new Fields(p.child("combine_payer_info",true)).text("openid",1,128,true);Stage1Exception.require(digest(payer.getBytes(java.nio.charset.StandardCharsets.UTF_8)).equals(f.text("payerOpenIdHash",64,64,true)),409,"SC23_IDENTITY_MISMATCH");
  var expected=new HashMap<String,JsonObject>();for(var raw:f.list("subOrders",1,200,true)){var s=raw.getAsJsonObject();Stage1Exception.require(expected.put(s.get("subNo").getAsString(),s)==null,409,"SC23_ORDER_SET_MISMATCH");}
  var paid=new JsonArray();Instant last=Instant.MIN;long total=0;
  for(var raw:p.list("sub_orders",1,200,true)){var s=new Fields(raw);var original=expected.remove(s.text("out_trade_no",1,32,true));Stage1Exception.require(original!=null,409,"SC23_ORDER_SET_MISMATCH");var o=new Fields(original);
   Stage1Exception.require(s.text("mchid",1,32,true).equals(o.text("merchantId",1,32,true))&&s.text("sub_mchid",1,32,true).equals(o.text("subMchid",1,32,true))&&Set.of("SUCCESS","REFUND").contains(s.text("trade_state",1,20,true)),409,"SC23_IDENTITY_MISMATCH");
   var amount=new Fields(s.child("amount",true));long cent=amount.integer("payer_amount",1,Stage2Money.MAX_SAFE_CENT,true);Stage1Exception.require(cent==amount.integer("total_amount",1,Stage2Money.MAX_SAFE_CENT,true)&&cent==o.integer("amountCent",1,Stage2Money.MAX_SAFE_CENT,true)&&"CNY".equals(amount.text("currency",3,3,true))&&"CNY".equals(amount.text("payer_currency",3,3,true)),409,"SC23_AMOUNT_MISMATCH");
   Instant at=Instant.parse(s.text("success_time",1,64,true));if(at.isAfter(last))last=at;total=Stage2Money.add(total,cent);var child=original.deepCopy();child.addProperty("transactionId",s.text("transaction_id",1,100,true));child.addProperty("currency","CNY");child.addProperty("state","SUCCEEDED");child.addProperty("paidAt",at.toString());paid.add(child);
  }
  Stage1Exception.require(expected.isEmpty()&&total==f.integer("amountCent",1,Stage2Money.MAX_SAFE_CENT,true),409,"SC23_ORDER_SET_MISMATCH");
  return object(map("mode","REAL","state","SUCCEEDED","request",request,"paid",map("combineNo",combine,"currency","CNY","amountCent",total,"paidAt",last,"subOrders",paid),"source",proof));
 }
 public boolean trusted(JsonObject request,JsonObject envelope){try{return hash(normalize(request,envelope.getAsJsonObject("source"))).equals(hash(envelope));}catch(RuntimeException e){return false;}}
}
