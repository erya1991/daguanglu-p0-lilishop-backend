package cn.lili.modules.community.p0.stage3;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.common.security.session.http.SessionHttpFilter;
import cn.lili.modules.community.p0.fulfillment.FulfillmentService;
import cn.lili.modules.community.p0.stage2.*;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service @ConditionalOnProperty(name="p0.stage3.enabled",havingValue="true")
public class Stage3Verification {
    private final Stage1Db db;private final Stage1Context context;private final Stage3Authorization auth;
    private final Stage3Orders orders;private final Stage3Eligibility eligibility;private final Stage3Gate gate;
    private final StringRedisTemplate redis;private final Stage2Settings resources;private final Clock clock;

    public Stage3Verification(Stage1Db db,Stage1Context context,Stage3Authorization auth,Stage3Orders orders,Stage3Eligibility eligibility,Stage3Gate gate,StringRedisTemplate redis,Stage2Settings resources,@Qualifier("stage1Clock")Clock clock){
        this.db=db;this.context=context;this.auth=auth;this.orders=orders;this.eligibility=eligibility;this.gate=gate;this.redis=redis;this.resources=resources;this.clock=clock;
    }
    public void issueLocked(Map<String,Object> row,Map<String,Object> state){
        if(orders.aftersaleBlock(id(row,"id"))!=null)return;
        if(!Stage3Policy.redeemable(orders.method(row),id(state,"phase"))||db.one("SELECT order_id FROM li_p0_s3_credential WHERE order_id=?",row.get("id"))!=null)return;
        gate.requireCredentials();eligibility.require(id(row,"trade_id"));Stage1Exception.require(orders.pointAvailable(row),409,"POINT_UNAVAILABLE");
        var point=object(row.get("payload_json")).getAsJsonObject("order").getAsJsonObject("point");var crypto=gate.crypto();String token=crypto.credential();
        db.update("INSERT INTO li_p0_s3_credential(order_id,store_id,point_id,token_hash,token_cipher,created_at) VALUES(?,?,?,?,?,?)",row.get("id"),row.get("store_id"),point.get("id").getAsString(),crypto.index(token),crypto.encrypt(token,id(row,"id")),clock.instant());
    }
    public Object credential(String order){
        context.require(SessionDomain.RESIDENT);var row=orders.authorized(order,"order.read");var c=db.one("SELECT * FROM li_p0_s3_credential WHERE order_id=?",order);var state=orders.state(row);
        if(c!=null&&"USED".equals(id(c,"status")))return map("status","USED","usedAt",instant(c.get("used_at")),"orderId",order);
        if(orders.aftersaleBlock(order)!=null)return map("status","UNAVAILABLE","reason",orders.aftersaleBlock(order),"orderId",order);
        if(state==null||!Stage3Policy.redeemable(orders.method(row),id(state,"phase"))||c==null)return map("status","UNAVAILABLE","reason","CREDENTIAL_NOT_READY","orderId",order);
        var eligible=eligibility.inspect(id(row,"trade_id"));if(!eligible.eligible()||!orders.pointAvailable(row))return map("status","UNAVAILABLE","reason",eligible.eligible()?"POINT_UNAVAILABLE":eligible.reason(),"orderId",order);
        gate.requireCredentials();Stage1Exception.require("ACTIVE".equals(id(c,"status")),409,"CREDENTIAL_UNAVAILABLE");
        String token=gate.crypto().decrypt(id(c,"token_cipher"),order);
        try{var bits=new QRCodeWriter().encode("DGLS3:"+token,BarcodeFormat.QR_CODE,280,280);var out=new ByteArrayOutputStream();MatrixToImageWriter.writeToStream(bits,"PNG",out);
            return map("status","ACTIVE","orderId",order,"manualCode",token,"qrData","data:image/png;base64,"+Base64.getEncoder().encodeToString(out.toByteArray()),"point",object(row.get("payload_json")).getAsJsonObject("order").get("point"));
        }catch(Exception e){throw new Stage1Exception(503,"CREDENTIAL_IMAGE_UNAVAILABLE");}
    }
    public Object lookup(Object body){gate.requireCredentials();var actor=context.require(SessionDomain.STORE);return limited(actor,body,()->lookup(body,actor));}
    private Object lookup(Object body,Stage1Context.Actor actor){
        var fields=new Fields(body).only("token","pointId");
        String point=fields.id("pointId",true);String token=fields.text("token",1,64,true);if(token.startsWith("DGLS3:"))token=token.substring(6);
        var crypto=gate.crypto();String index;try{index=crypto.index(token);}catch(Stage1Exception invalid){throw unavailable();}
        try {
            var credential=db.one("SELECT * FROM li_p0_s3_credential WHERE token_hash=? AND store_id=?",index,actor.store());if(credential==null)throw unavailable();
            String order=id(credential,"order_id");var row=orders.row(order);auth.order(actor,row,"verification.lookup");
            Stage1Exception.require(point.equals(id(credential,"point_id")),404,"CREDENTIAL_UNAVAILABLE");
            if("USED".equals(id(credential,"status"))){var v=db.required("SELECT id,completed_at,operator_id,point_snapshot FROM li_p0_s3_verification WHERE order_id=?",order);return map("status","USED","orderId",order,"verificationId",id(v,"id"),"completedAt",instant(v.get("completed_at")),"operatorId",id(v,"operator_id"));}
            orders.requireNoAftersale(id(row,"id"));eligibility.require(id(row,"trade_id"));Stage1Exception.require(orders.pointAvailable(row),404,"CREDENTIAL_UNAVAILABLE");var state=orders.state(row);
            Stage1Exception.require(state!=null&&"ACTIVE".equals(id(credential,"status"))&&Stage3Policy.redeemable(orders.method(row),id(state,"phase")),404,"CREDENTIAL_UNAVAILABLE");
            var payload=object(map("orderId",order,"storeId",actor.store(),"pointId",point,"version",number(state,"version"),"credentialHash",index,"session",sessionFingerprint(actor),"expiresAt",clock.instant().plusSeconds(120)));
            String encoded=Base64.getUrlEncoder().withoutPadding().encodeToString(write(payload).getBytes(StandardCharsets.UTF_8));
            var original=object(row.get("payload_json")).getAsJsonObject("order");
            return map("status","READY","orderId",order,"storeName",original.get("storeName"),"items",orders.currentItems(row),"point",original.get("point"),"phase",state.get("phase"),"version",state.get("version"),"confirmationContext",encoded+"."+crypto.mac("confirmation:"+encoded),"expiresAt",payload.get("expiresAt"));
        }catch(Stage1Exception e){if(e.status()<500){throw unavailable();}throw e;}
    }
    public Object confirm(Object body){gate.requireCredentials();var actor=context.require(SessionDomain.STORE);return limited(actor,body,()->confirm(body,actor));}
    private Object confirm(Object body,Stage1Context.Actor actor){
        var fields=new Fields(body).only("operationId","expectedVersion","confirmationContext");String op=Stage2Transactions.operation(fields);long expected=fields.version();
        String raw=fields.text("confirmationContext",1,4096,true);String[] parts=raw.split("\\.",-1);var crypto=gate.crypto();
        Stage1Exception.require(parts.length==2&&crypto.authentic("confirmation:"+parts[0],parts[1]),404,"CREDENTIAL_UNAVAILABLE");
        JsonObject confirmation;try{confirmation=object(new String(Base64.getUrlDecoder().decode(parts[0]),StandardCharsets.UTF_8));}catch(RuntimeException e){throw unavailable();}
        Stage1Exception.require(actor.store().equals(confirmation.get("storeId").getAsString())&&sessionFingerprint(actor).equals(confirmation.get("session").getAsString()),404,"CREDENTIAL_UNAVAILABLE");
        String order=confirmation.get("orderId").getAsString(),point=confirmation.get("pointId").getAsString(),index=confirmation.get("credentialHash").getAsString();
        var row=orders.authorized(order,"verification.confirm");
        try{return orders.locked(row,()->{
            context.recheck(actor);auth.order(actor,orders.row(order),"verification.confirm");
            var prior=orders.prior(actor,op,"verify",body,order);if(prior!=null)return prior;
            Stage1Exception.require(clock.instant().isBefore(Instant.parse(confirmation.get("expiresAt").getAsString())),409,"CONFIRMATION_EXPIRED");
            orders.requireNoAftersale(id(row,"id"));eligibility.require(id(row,"trade_id"));Stage1Exception.require(orders.pointAvailable(row),404,"CREDENTIAL_UNAVAILABLE");var state=orders.state(row);
            Stage1Exception.require(state!=null&&number(state,"version")==expected&&expected==confirmation.get("version").getAsLong(),409,"VERSION_CONFLICT");
            var credential=db.required("SELECT * FROM li_p0_s3_credential WHERE order_id=? AND token_hash=? AND store_id=? FOR UPDATE",order,index,actor.store());
            Stage1Exception.require("ACTIVE".equals(id(credential,"status"))&&point.equals(id(credential,"point_id"))&&orders.pointAvailable(row),404,"CREDENTIAL_UNAVAILABLE");
            String next=Stage3Policy.next(orders.method(row),id(state,"phase"),"verify");
            db.cas(db.update("UPDATE li_p0_s3_credential SET status='USED',used_at=? WHERE order_id=? AND status='ACTIVE'",clock.instant(),order));
            String verification=db.id();var originalPoint=object(row.get("payload_json")).getAsJsonObject("order").get("point");
            db.update("INSERT INTO li_p0_s3_verification VALUES(?,?,?,?,?,?,?,?,?,?)",verification,order,actor.store(),point,index,actor.id(),actor.clerk(),op,write(originalPoint),clock.instant());
            orders.transition(row,state,next,"COMPLETED",actor,map("verificationId",verification,"pointId",point));
            var result=map("orderId",order,"phase","COMPLETED","version",expected+1,"verificationId",verification,"completedAt",clock.instant());
            orders.saveOperation(actor,op,"verify",body,order,result);context.recheck(actor);return result;
        });}catch(Stage1Exception e){throw e;}
    }
    public Object points(){
        var actor=context.require(SessionDomain.STORE);Stage1Exception.require(auth.storePermissions(actor.user()).contains("verification.lookup"),403,"FORBIDDEN");
        return db.rows("SELECT a.id,a.address_name,p.type,p.enabled FROM li_store_address a JOIN li_p0_point_control p ON p.point_id=a.id AND p.store_id=a.store_id WHERE a.store_id=? AND a.delete_flag=0 AND p.enabled=1 ORDER BY a.id",actor.store()).stream().map(r->map("id",id(r,"id"),"name",r.get("address_name"),"type",r.get("type"))).toList();
    }
    private List<String> rateKeys(Stage1Context.Actor actor){
        String ip="unknown";var attributes=RequestContextHolder.getRequestAttributes();if(attributes instanceof ServletRequestAttributes a)ip=a.getRequest().getRemoteAddr();
        String prefix="s3:"+resources.getRunId()+":verification:failed:";var crypto=gate.crypto();return List.of(prefix+crypto.mac("actor:"+actor.id()+":"+actor.store()),prefix+crypto.mac("store:"+actor.store()),prefix+crypto.mac("ip:"+ip));
    }
    private Object limited(Stage1Context.Actor actor,Object body,java.util.function.Supplier<Object> action){
        var ticket=new Stage3AttemptLimiter(redis).begin(rateKeys(actor));
        try{Object result=action.get();ticket.succeeded();return result;}
        catch(Stage1Exception error){if(error.status()<500){String ref=gate.crypto().mac("rejected:"+hash(body)).substring(0,16);
            try{db.update("INSERT INTO li_p0_s3_verification_audit(id,store_id,actor_id,credential_ref,result,created_at) VALUES(?,?,?,?,'REJECTED',?)",db.id(),actor.store(),actor.id(),ref,clock.instant());}catch(RuntimeException unavailable){throw new Stage1Exception(503,"VERIFICATION_AUDIT_UNAVAILABLE");}}
            else ticket.succeeded();throw error;}
        catch(RuntimeException unavailable){ticket.succeeded();throw new Stage1Exception(503,"VERIFICATION_LIMIT_UNAVAILABLE");}
    }
    private static String sessionFingerprint(Stage1Context.Actor actor){
        var auth=SecurityContextHolder.getContext().getAuthentication();if(auth==null||!(auth.getDetails() instanceof SessionHttpFilter.Principal p))throw new Stage1Exception(401,"AUTH_REQUIRED");
        return hash(map("sid",p.session().sid(),"generation",p.session().generation(),"actor",actor.id(),"store",actor.store(),"clerk",actor.clerk(),"application",actor.identity().application(),"binding",actor.identity().bindingId(),"bindingVersion",actor.identity().bindingVersion()));
    }
    private static Stage1Exception unavailable(){return new Stage1Exception(404,"CREDENTIAL_UNAVAILABLE");}
}
