package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Independent channel database and transactions. It never reads or writes local Trade/stock. */
@Component @ConditionalOnProperty(name="p0.stage2.enabled",havingValue="true")
public class PersistentPaymentMock implements CombinePaymentPort {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final Stage2Settings settings;private final Clock clock;
    public static final Set<String> SCENARIOS=Set.of("SUCCESS","FAILURE","UNKNOWN","LOST_NOTIFY","WRONG_AMOUNT","WRONG_MODE","WRONG_SUBS","PARTIAL","DELAYED_SUCCESS","CLOSE_UNKNOWN","LATE_SUCCESS");
    public PersistentPaymentMock(Stage2Settings settings,@Qualifier("stage1Clock")Clock clock){
        this.settings=settings;this.clock=clock;
        if(settings.isUatResourceMode())cn.lili.modules.community.p0.uat.UatResourceScope.registered(settings);
        Stage1Exception.require("TEST".equals(settings.getEnvironment())&&"MOCK".equals(settings.getMode())&&settings.getMockJdbcUrl()!=null&&settings.getMockJdbcUrl().startsWith(settings.isUatResourceMode()?"jdbc:mysql://127.0.0.1:19706/dgl_p0_uat_mock?":settings.isStage45ResourceMode()?"jdbc:mysql://127.0.0.1:19606/dgl_p0_s45_mock?":settings.isStage3ResourceMode()?"jdbc:mysql://127.0.0.1:19506/dgl_p0_s3_mock?":"jdbc:mysql://127.0.0.1:19406/dgl_p0_s2_mock?")&&(settings.isUatResourceMode()?"uat_mock":settings.isStage45ResourceMode()?"s45_mock":settings.isStage3ResourceMode()?"s3_mock":"s2_mock").equals(settings.getMockUsername()),503,"MOCK_RESOURCE_NOT_REGISTERED");
        var source=new DriverManagerDataSource(settings.getMockJdbcUrl(),settings.getMockUsername(),settings.getMockPassword());jdbc=new JdbcTemplate(source);tx=new TransactionTemplate(new DataSourceTransactionManager(source));
        var identity=jdbc.queryForMap("SELECT @@server_uuid uuid,DATABASE() db");Stage1Exception.require(settings.getMysqlUuid().equals(String.valueOf(identity.get("uuid")))&&(settings.isUatResourceMode()?"dgl_p0_uat_mock":settings.isStage45ResourceMode()?"dgl_p0_s45_mock":settings.isStage3ResourceMode()?"dgl_p0_s3_mock":"dgl_p0_s2_mock").equals(identity.get("db")),503,"MOCK_RESOURCE_NOT_REGISTERED");
    }
    private JsonObject signed(JsonObject request,String state,String event){
        var result=object(map("eventId",event,"mode","MOCK","state",state,"request",request,"observedAt",clock.instant()));result.addProperty("signature",sign(result));return result;
    }
    public String sign(JsonObject value){
        try{var copy=value.deepCopy();copy.remove("signature");var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(settings.getMockControlKey().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(hash(copy).getBytes(StandardCharsets.US_ASCII)));}catch(Exception unavailable){throw new Stage1Exception(503,"MOCK_AUTH_UNAVAILABLE");}
    }
    public boolean trusted(JsonObject result){return result.has("signature")&&MessageDigest.isEqual(sign(result).getBytes(StandardCharsets.US_ASCII),result.get("signature").getAsString().getBytes(StandardCharsets.US_ASCII));}
    private Map<String,Object> one(String combine){var rows=jdbc.queryForList("SELECT * FROM li_p0_mock_payment WHERE combine_no=? FOR UPDATE",combine);return rows.isEmpty()?null:rows.getFirst();}
    private int update(String sql,Object... args){return jdbc.update(sql,Arrays.stream(args).map(v->v instanceof Instant i?java.sql.Timestamp.from(i):v).toArray());}
    private void requireRequest(JsonObject request){
        var fields=new Fields(request);fields.choice("mode",Set.of("MOCK"),true);fields.choice("currency",Set.of("CNY"),true);
        fields.text("combineNo",1,64,true);fields.id("tradeId",true);fields.id("payerMemberId",true);fields.text("platformMerchantId",1,64,true);fields.text("applicationId",1,128,true);
        try{Instant.parse(fields.text("expiresAt",1,64,true));}catch(java.time.format.DateTimeParseException invalid){throw Stage1Exception.field("expiresAt","INSTANT_REQUIRED");}
        long total=fields.integer("amountCent",1,Stage2Money.MAX_SAFE_CENT,true);
        var subs=request.getAsJsonArray("subOrders");Stage1Exception.require(!subs.isEmpty()&&subs.size()<=50,422,"MOCK_SUBORDER_LIMIT");long sum=0;var ids=new HashSet<String>();
        for(var e:subs){var sub=new Fields(e);Stage1Exception.require(ids.add(sub.id("orderId",true)),422,"MOCK_SUBORDER_INVALID");sub.id("storeId",true);sub.text("subNo",1,64,true);sub.text("subMchid",1,64,true);sum=Stage2Money.add(sum,sub.integer("amountCent",1,Stage2Money.MAX_SAFE_CENT,true));}
        Stage1Exception.require(sum==total,422,"MOCK_AMOUNT_INVALID");
    }
    @Override public JsonObject prepare(JsonObject request){
        requireRequest(request);String combine=request.get("combineNo").getAsString();Instant expires=Instant.parse(request.get("expiresAt").getAsString());
        return tx.execute(status->{
            // INSERT serializes first creation too; a second caller can only observe this immutable identity.
            int inserted=update("INSERT IGNORE INTO li_p0_mock_payment(combine_no,request_hash,request_json,state,created_at,expires_at) VALUES(?,?,?,'UNPAID',?,?)",combine,hash(request),write(request),clock.instant(),expires);
            var row=one(combine);Stage1Exception.require(hash(request).equals(id(row,"request_hash")),409,"MOCK_IDENTITY_CONFLICT");
            // The minimum applies to new preparation, not an already valid original preparation.
            if(inserted==1&&clock.instant().plusSeconds(settings.getMockMinimumValiditySeconds()).isAfter(expires))return change(row,request,"CLOSED");
            return current(row,request);
        });
    }
    @Override public JsonObject launch(JsonObject request){return tx.execute(status->{
        var row=one(request.get("combineNo").getAsString());if(row==null)return signed(request,"NOT_FOUND","missing:"+request.get("combineNo").getAsString());
        Stage1Exception.require(hash(request).equals(id(row,"request_hash")),409,"MOCK_IDENTITY_CONFLICT");
        if(!"UNPAID".equals(id(row,"state")))return current(row,request);
        if(!clock.instant().isBefore(instant(row.get("expires_at"))))return change(row,request,"CLOSED");
        String scenario=id(row,"scenario");if(Set.of("UNKNOWN","DELAYED_SUCCESS").contains(scenario))return signed(request,"UNKNOWN","unknown:"+id(row,"combine_no")+":"+UUID.randomUUID());
        if(scenario.equals("FAILURE"))return signed(request,"UNPAID","declined:"+id(row,"combine_no")+":"+UUID.randomUUID());
        var result=change(row,request,"SUCCEEDED");
        if(scenario.equals("WRONG_AMOUNT")){result.getAsJsonObject("request").addProperty("amountCent",request.get("amountCent").getAsLong()+1);result.addProperty("signature",sign(result));}
        if(scenario.equals("WRONG_MODE")){result.addProperty("mode","REAL");result.addProperty("signature",sign(result));}
        if(scenario.equals("WRONG_SUBS")){result.getAsJsonObject("request").getAsJsonArray("subOrders").remove(0);result.addProperty("signature",sign(result));}
        if(scenario.equals("PARTIAL")){result.addProperty("state","PARTIAL");result.addProperty("signature",sign(result));}
        update("UPDATE li_p0_mock_payment SET result_json=? WHERE combine_no=?",write(result),id(row,"combine_no"));
        if(!scenario.equals("LOST_NOTIFY"))enqueue(result,id(row,"combine_no"));
        return result;
    });}
    @Override public JsonObject query(JsonObject request){return tx.execute(status->{
        var row=one(request.get("combineNo").getAsString());if(row==null){
            // This is a final, signed protocol proof only after the immutable request deadline.
            // Every future prepare/launch with that same request is refused by the channel itself.
            String state=clock.instant().isBefore(Instant.parse(request.get("expiresAt").getAsString()))?"NOT_FOUND":"EXPIRED_ABSENT";
            return signed(request,state,"missing:"+request.get("combineNo").getAsString()+":"+state+":"+UUID.randomUUID());
        }
        Stage1Exception.require(hash(request).equals(id(row,"request_hash")),409,"MOCK_IDENTITY_CONFLICT");
        if("UNPAID".equals(id(row,"state"))&&!clock.instant().isBefore(instant(row.get("expires_at"))))return change(row,request,"CLOSED");
        if("UNPAID".equals(id(row,"state"))&&Set.of("UNKNOWN","DELAYED_SUCCESS").contains(id(row,"scenario")))return signed(request,"UNKNOWN","query-unknown:"+id(row,"combine_no")+":"+UUID.randomUUID());
        return current(row,request);
    });}
    @Override public JsonObject close(JsonObject request){return tx.execute(status->{
        var row=one(request.get("combineNo").getAsString());if(row==null)return query(request);
        Stage1Exception.require(hash(request).equals(id(row,"request_hash")),409,"MOCK_IDENTITY_CONFLICT");
        if("SUCCEEDED".equals(id(row,"state")))return current(row,request);
        if("CLOSE_UNKNOWN".equals(id(row,"scenario")))return signed(request,"UNKNOWN","close-unknown:"+id(row,"combine_no")+":"+UUID.randomUUID());
        return change(row,request,"CLOSED");
    });}
    private JsonObject current(Map<String,Object> row,JsonObject request){return row.get("result_json")==null?signed(request,id(row,"state"),"query:"+id(row,"combine_no")+":"+number(row,"version")+":"+UUID.randomUUID()):object(row.get("result_json"));}
    private JsonObject change(Map<String,Object> row,JsonObject request,String state){
        String event="mock:"+id(row,"combine_no")+":"+(number(row,"version")+1);var result=signed(request,state,event);
        if("SUCCEEDED".equals(state)){
            JsonObject paid;
            if("SUCCEEDED".equals(id(row,"state"))){
                // Correction changes the evidence envelope, never the channel transaction or paid time.
                var previous=row.get("result_json")==null?null:object(row.get("result_json"));
                Stage1Exception.require(previous!=null&&previous.has("paid"),409,"LEGACY_PAID_FACTS_MISSING");
                paid=Stage2PaymentFacts.require(object(map("mode","MOCK","state","SUCCEEDED","request",request,"paid",previous.get("paid"))),request).deepCopy();
            }else paid=Stage2PaymentFacts.firstMockPayment(request,clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS));
            result.add("paid",paid);result.add("paid",Stage2PaymentFacts.require(result,request));result.addProperty("signature",sign(result));
        }
        update("UPDATE li_p0_mock_payment SET state=?,result_json=?,version=version+1 WHERE combine_no=?",state,write(result),id(row,"combine_no"));return result;
    }
    private void enqueue(JsonObject result,String combine){update("INSERT IGNORE INTO li_p0_mock_delivery(event_id,combine_no,payload_json,due_at) VALUES(?,?,?,?)",result.get("eventId").getAsString(),combine,write(result),clock.instant());}
    public Object scenario(Object body,String key){
        Stage1Exception.require(key!=null&&MessageDigest.isEqual(settings.getMockControlKey().getBytes(StandardCharsets.US_ASCII),key.getBytes(StandardCharsets.US_ASCII)),403,"MOCK_CONTROL_DENIED");
        var f=new Fields(body).only("combineNo","scenario","releaseDelayed","repeatNotifications","correctEvidence");String combine=f.text("combineNo",1,64,true),scenario=f.choice("scenario",SCENARIOS,true);
        return tx.execute(status->{var row=one(combine);Stage1Exception.require(row!=null,404,"MOCK_PAYMENT_NOT_FOUND");update("UPDATE li_p0_mock_payment SET scenario=? WHERE combine_no=?",scenario,combine);
            if(f.bool("releaseDelayed",false)){Stage1Exception.require("UNPAID".equals(id(row,"state"))||scenario.equals("LATE_SUCCESS"),409,"MOCK_PAYMENT_TERMINAL");var result=change(row,object(row.get("request_json")),"SUCCEEDED");enqueue(result,combine);row=one(combine);}
            if(f.bool("correctEvidence",false)){Stage1Exception.require("SUCCEEDED".equals(id(row,"state")),409,"MOCK_PAYMENT_NOT_PAID");var result=change(row,object(row.get("request_json")),"SUCCEEDED");enqueue(result,combine);row=one(combine);}
            if(f.bool("repeatNotifications",false)&&row.get("result_json")!=null){var result=object(row.get("result_json"));for(int i=0;i<10;i++){var copy=result.deepCopy();copy.addProperty("eventId",result.get("eventId").getAsString()+":repeat:"+i);copy.addProperty("signature",sign(copy));enqueue(copy,combine);}}
            return map("combineNo",combine,"scenario",scenario,"mode","MOCK");});
    }
    public List<JsonObject> deliveries(){return jdbc.queryForList("SELECT payload_json FROM li_p0_mock_delivery WHERE status='PENDING' AND due_at<=? ORDER BY due_at,event_id LIMIT 100",java.sql.Timestamp.from(clock.instant())).stream().map(r->object(r.get("payload_json"))).toList();}
    public List<JsonObject> deliveries(Collection<String> combines){
        if(combines.isEmpty())return List.of();
        var args=new ArrayList<Object>();args.add(java.sql.Timestamp.from(clock.instant()));args.addAll(combines);
        return jdbc.queryForList("SELECT payload_json FROM li_p0_mock_delivery WHERE status='PENDING' AND due_at<=? AND combine_no IN ("+String.join(",",Collections.nCopies(combines.size(),"?"))+") ORDER BY due_at,event_id LIMIT 100",args.toArray()).stream().map(r->object(r.get("payload_json"))).toList();
    }
    public void delivered(String event){update("UPDATE li_p0_mock_delivery SET status='DONE',attempts=attempts+1 WHERE event_id=?",event);}
}
