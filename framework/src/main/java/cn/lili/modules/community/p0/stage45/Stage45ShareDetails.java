package cn.lili.modules.community.p0.stage45;
import cn.lili.modules.community.p0.support.*;import com.google.gson.*;import java.util.*;import static cn.lili.modules.community.p0.support.Stage1Json.*;
/** A total FINISHED result is not an amount. Only matching SUCCESS receiver details contribute. */
public final class Stage45ShareDetails {
 private Stage45ShareDetails(){}
 public record Totals(long succeededCent,boolean settled,boolean allSuccessful){}
 public static Totals require(JsonObject request,JsonObject receipt){
  long total=new Fields(request).integer("amountCent",1,cn.lili.modules.community.p0.stage2.Stage2Money.MAX_SAFE_CENT,true);var expected=new HashMap<String,Long>();
  if(request.has("receivers"))for(var raw:new Fields(request).list("receivers",1,50,true)){var r=new Fields(raw);String account=r.text("account",1,64,true);Stage1Exception.require(expected.put(account,r.integer("amountCent",1,total,true))==null,409,"CHANNEL_RECEIVER_CONFLICT");}
  else expected.put(new Fields(request).text("receiverMchid",1,64,true),total);
  Stage1Exception.require(expected.values().stream().mapToLong(Long::longValue).sum()==total,409,"CHANNEL_RECEIVER_CONFLICT");
  var seen=new HashSet<String>();long successful=0;boolean settled=true,all=true;
  for(var raw:new Fields(receipt).list("receivers",1,100,true)){var r=new Fields(raw);String account=r.text("account",1,64,true),detail=r.text("detailId",1,100,true),state=r.choice("state",Set.of("SUCCESS","CLOSED","PENDING","UNKNOWN"),true);long amount=r.integer("amountCent",1,total,true);
   Stage1Exception.require(seen.add(detail)&&expected.containsKey(account)&&amount<=expected.get(account),409,"CHANNEL_RECEIVER_CONFLICT");expected.put(account,expected.get(account)-amount);
   if(state.equals("SUCCESS"))successful=Math.addExact(successful,amount);else all=false;if(!Set.of("SUCCESS","CLOSED").contains(state))settled=false;
  }
  Stage1Exception.require(expected.values().stream().allMatch(v->v==0)&&new Fields(receipt).integer("succeededCent",0,total,true)==successful,409,"CHANNEL_DETAILS_AMOUNT_CONFLICT");
  return new Totals(successful,settled,all&&"FINISHED".equals(new Fields(receipt).text("providerState",1,24,true)));
 }
 public static boolean monotonic(JsonObject before,JsonObject after){
  if(before==null)return true;var old=new HashMap<String,JsonObject>();if(before.has("receivers"))for(var raw:before.getAsJsonArray("receivers")){var r=raw.getAsJsonObject();if("SUCCESS".equals(r.get("state").getAsString()))old.put(r.get("detailId").getAsString(),r);}
  if(after.has("receivers"))for(var raw:after.getAsJsonArray("receivers")){var r=raw.getAsJsonObject();var prior=old.get(r.get("detailId").getAsString());if(prior!=null){if(!"SUCCESS".equals(r.get("state").getAsString())||!r.get("amountCent").equals(prior.get("amountCent"))||!r.get("account").equals(prior.get("account")))return false;old.remove(r.get("detailId").getAsString());}}
  return old.isEmpty();
 }
}
