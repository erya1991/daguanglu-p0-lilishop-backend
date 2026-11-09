package cn.lili.modules.community.p0.stage45;
import cn.lili.modules.community.p0.support.*;import com.google.gson.*;import java.nio.charset.StandardCharsets;import java.security.*;import java.time.*;import java.util.*;import static cn.lili.modules.community.p0.support.Stage1Json.*;
/** Offline verification of a previously captured official response. No credentials or network are loaded here. */
public final class Stage45WechatEvidence {
 private final Map<String,PublicKey> keys;
 public Stage45WechatEvidence(Map<String,PublicKey> keys){this.keys=Map.copyOf(keys);}
 private static String raw(JsonObject value,String key,int max){var v=value.get(key);Stage1Exception.require(v!=null&&v.isJsonPrimitive()&&v.getAsJsonPrimitive().isString()&&!v.getAsString().isEmpty()&&v.getAsString().length()<=max,409,"REAL_SIGNATURE_INVALID");return v.getAsString();}
 public JsonObject verify(JsonObject proof,String method,String uri){
  try{var f=new Fields(proof);Stage1Exception.require(method.equals(f.text("method",3,4,true))&&uri.equals(f.text("uri",1,1000,true)),409,"REAL_SOURCE_REQUEST_MISMATCH");var h=new Fields(f.child("headers",true));String serial=h.text("Wechatpay-Serial",1,128,true),timestamp=h.text("Wechatpay-Timestamp",1,20,true),nonce=h.text("Wechatpay-Nonce",1,256,true),signature=h.text("Wechatpay-Signature",1,2048,true),body=raw(proof,"body",2000000);
   Instant observed=Instant.parse(f.text("observedAt",1,64,true)),signed=Instant.ofEpochSecond(Long.parseLong(timestamp));Stage1Exception.require(Math.abs(Duration.between(observed,signed).getSeconds())<=300&&keys.containsKey(serial),409,"REAL_SIGNATURE_INVALID");
   var verify=Signature.getInstance("SHA256withRSA");verify.initVerify(keys.get(serial));verify.update((timestamp+"\n"+nonce+"\n"+body+"\n").getBytes(StandardCharsets.UTF_8));Stage1Exception.require(verify.verify(Base64.getDecoder().decode(signature)),409,"REAL_SIGNATURE_INVALID");return object(body);
  }catch(Stage1Exception e){throw e;}catch(Exception e){throw new Stage1Exception(409,"REAL_SIGNATURE_INVALID");}
 }
}
