package cn.lili.modules.community.p0.stage45;

import cn.lili.modules.community.p0.stage2.*;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Separate persistent provider ledger. No local order, inventory, obligation or task access. */
@Component @ConditionalOnProperty(name="p0.stage45.enabled",havingValue="true")
public class Stage45MockFunds implements Stage45FundsPort {
    private final JdbcTemplate jdbc;private final TransactionTemplate tx;private final Stage2Settings settings;private final Clock clock;
    public Stage45MockFunds(Stage2Settings settings,@Qualifier("stage1Clock")Clock clock){
        this.settings=settings;this.clock=clock;
        Stage1Exception.require(settings.isStage45ResourceMode()&&"TEST".equals(settings.getEnvironment())&&"MOCK".equals(settings.getMode())&&settings.getMockJdbcUrl().startsWith("jdbc:mysql://127.0.0.1:19606/dgl_p0_s45_mock?")&&"s45_mock".equals(settings.getMockUsername()),503,"FUNDS_MOCK_RESOURCE_INVALID");
        var ds=new DriverManagerDataSource(settings.getMockJdbcUrl(),settings.getMockUsername(),settings.getMockPassword());jdbc=new JdbcTemplate(ds);tx=new TransactionTemplate(new DataSourceTransactionManager(ds));
        var identity=jdbc.queryForMap("SELECT @@server_uuid uuid,DATABASE() db");
        Stage1Exception.require(settings.getMysqlUuid().equals(String.valueOf(identity.get("uuid")))&&"dgl_p0_s45_mock".equals(identity.get("db")),503,"FUNDS_MOCK_RESOURCE_INVALID");
    }
    private Map<String,Object> one(String sql,Object...args){var rows=jdbc.queryForList(sql,args);Stage1Exception.require(rows.size()<=1,409,"MOCK_RELATION_CONFLICT");return rows.isEmpty()?null:rows.getFirst();}
    private int update(String sql,Object...args){return jdbc.update(sql,Arrays.stream(args).map(v->v instanceof Instant i?java.sql.Timestamp.from(i):v).toArray());}
    private long count(String sql,Object...args){return jdbc.queryForObject(sql,Long.class,args);}
    private String signature(JsonObject value){
        try{var copy=value.deepCopy();copy.remove("signature");var mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(settings.getMockControlKey().getBytes(StandardCharsets.UTF_8),"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(hash(copy).getBytes(StandardCharsets.US_ASCII)));}
        catch(Exception e){throw new Stage1Exception(503,"MOCK_AUTH_UNAVAILABLE");}
    }
    private JsonObject signed(JsonObject value){value.addProperty("signature",signature(value));return value;}
    public boolean trusted(JsonObject result){return result!=null&&result.has("signature")&&MessageDigest.isEqual(signature(result).getBytes(StandardCharsets.US_ASCII),result.get("signature").getAsString().getBytes(StandardCharsets.US_ASCII));}
    private void observe(String no,JsonObject result){String raw=write(result);update("INSERT IGNORE INTO li_p0_mock_funds_observation(request_no,result_hash,result_json,observed_at) VALUES(?,SHA2(CAST(CAST(? AS JSON) AS CHAR),256),?,?)",no,raw,raw,Instant.parse(result.get("observedAt").getAsString()));}
    private JsonObject original(JsonObject request){
        var f=new Fields(request);f.choice("mode",Set.of("MOCK"),true);f.choice("currency",Set.of("CNY"),true);
        String transaction=f.text("transactionId",1,100,true);f.text("subMchid",1,64,true);f.text("platformMerchantId",1,64,true);f.text("payerMemberId",1,19,true);
        var row=one("SELECT request_json,result_json FROM li_p0_mock_payment WHERE state='SUCCEEDED' AND JSON_SEARCH(result_json,'one',?,'!','$.paid.subOrders[*].transactionId') IS NOT NULL",transaction);
        Stage1Exception.require(row!=null,409,"MOCK_ORIGINAL_PAYMENT_UNPROVEN");var payment=object(row.get("result_json"));var expected=object(row.get("request_json"));
        for(var raw:payment.getAsJsonObject("paid").getAsJsonArray("subOrders")){
            var sub=raw.getAsJsonObject();if(!transaction.equals(sub.get("transactionId").getAsString()))continue;
            Stage1Exception.require(f.text("subMchid",1,64,true).equals(sub.get("subMchid").getAsString())&&f.text("payerMemberId",1,19,true).equals(expected.get("payerMemberId").getAsString())&&f.text("platformMerchantId",1,64,true).equals(expected.get("platformMerchantId").getAsString())&&f.id("orderId",true).equals(sub.get("orderId").getAsString()),409,"MOCK_PAYMENT_IDENTITY_CONFLICT");
            return sub;
        }throw new Stage1Exception(409,"MOCK_ORIGINAL_PAYMENT_UNPROVEN");
    }
    private JsonObject result(JsonObject request,String state,String reason){
        long amount=new Fields(request).integer("amountCent",0,Stage2Money.MAX_SAFE_CENT,true);
        var response=object(map("mode","MOCK","request",request,"state",state,"reason",reason,"succeededCent",state.equals("SUCCEEDED")?amount:0,"detailState",state.equals("SUCCEEDED")?"SUCCESS":state,"retryAllowed",Set.of("FAILURE","INSUFFICIENT").contains(reason==null?"":reason),"acceptedAt",state.equals("NOT_FOUND")?null:clock.instant(),"succeededAt",state.equals("SUCCEEDED")?clock.instant():null,"observedAt",clock.instant(),"channelId","S45M"+request.get("requestNo").getAsString()));
        if("SHARE".equals(request.get("kind").getAsString())){var receivers=new JsonArray();String detailState=state.equals("SUCCEEDED")?"SUCCESS":state.equals("FAILED")?"CLOSED":"UNKNOWN";if(request.has("receivers")){int index=0;for(var raw:request.getAsJsonArray("receivers")){var r=new Fields(raw);receivers.add(object(map("detailId",request.get("requestNo").getAsString()+"_"+(++index),"account",r.text("account",1,64,true),"amountCent",r.integer("amountCent",1,amount,true),"state",detailState,"succeededAt",state.equals("SUCCEEDED")?clock.instant():null)));}}else receivers.add(object(map("detailId",request.get("requestNo").getAsString()+"_1","account",request.get("receiverMchid"),"amountCent",amount,"state",detailState,"succeededAt",state.equals("SUCCEEDED")?clock.instant():null)));response.add("receivers",receivers);response.addProperty("providerState",Set.of("SUCCEEDED","FAILED").contains(state)?"FINISHED":"PROCESSING");}
        return signed(response);
    }
    private JsonObject partial(JsonObject request,JsonObject receipt,boolean terminal){
        long total=request.get("amountCent").getAsLong(),success=total*3/5;var rows=new JsonArray();String prefix=request.get("requestNo").getAsString(),account=request.get("receiverMchid").getAsString();if(success>0)rows.add(object(map("detailId",prefix+"_1","account",account,"amountCent",success,"state","SUCCESS","succeededAt",clock.instant())));rows.add(object(map("detailId",prefix+"_2","account",account,"amountCent",total-success,"state",terminal?"CLOSED":"PENDING","succeededAt",null)));receipt.add("receivers",rows);receipt.addProperty("succeededCent",success);receipt.addProperty("detailState",terminal?"PARTIAL_FINAL":"PENDING");receipt.addProperty("providerState",terminal?"FINISHED":"PROCESSING");receipt.addProperty("retryAllowed",false);if(terminal)receipt.addProperty("reason","DETAIL_CLOSED");return signed(receipt);
    }
    public JsonObject send(JsonObject request){
        var f=new Fields(request);String no=f.text("requestNo",8,64,true),kind=f.choice("kind",Set.of("SHARE","RETURN","REFUND","FINISH"),true);long amount=f.integer("amountCent",kind.equals("FINISH")?0:1,Stage2Money.MAX_SAFE_CENT,true);
        final boolean[] lost={false};
        JsonObject receipt=tx.execute(status->{
            var paid=original(request);String transaction=f.text("transactionId",1,100,true);
            if(Set.of("SHARE","FINISH").contains(kind))Stage1Exception.require(new Fields(paid).bool("profitSharing",false),409,"ORIGINAL_SHARING_NOT_PROVEN");
            if(kind.equals("SHARE"))Stage1Exception.require(count("SELECT COUNT(*) FROM li_p0_mock_funds_request WHERE transaction_id=? AND kind='FINISH' AND state<>'FAILED'",transaction)==0,409,"MOCK_ALREADY_UNFROZEN");
            // Locks the independent original payment row, serializing provider-side caps across request keys.
            one("SELECT combine_no FROM li_p0_mock_payment WHERE JSON_SEARCH(result_json,'one',?,'!','$.paid.subOrders[*].transactionId') IS NOT NULL FOR UPDATE",transaction);
            var row=one("SELECT * FROM li_p0_mock_funds_request WHERE request_no=? FOR UPDATE",no);
            if(row!=null){Stage1Exception.require(hash(request).equals(id(row,"request_hash")),409,"MOCK_REQUEST_CONFLICT");var control=one("SELECT scenario FROM li_p0_mock_funds_control WHERE transaction_id=? AND kind=?",transaction,kind);if("FAILED".equals(id(row,"state"))&&new Fields(row.get("result_json")).bool("retryAllowed",false)&&control!=null&&"SUCCESS".equals(id(control,"scenario"))){var recovered=result(request,"SUCCEEDED",null);recovered.addProperty("acceptedAt",instant(row.get("created_at")).toString());recovered=signed(recovered);observe(no,recovered);update("UPDATE li_p0_mock_funds_request SET state='SUCCEEDED',result_json=?,send_count=send_count+1,updated_at=? WHERE request_no=?",write(recovered),clock.instant(),no);return recovered;}return object(row.get("result_json"));}
            String parent=f.text("originalShareRequest",1,64,false);
            if(kind.equals("RETURN")){
                var share=one("SELECT * FROM li_p0_mock_funds_request WHERE request_no=? AND transaction_id=? AND kind='SHARE' AND CAST(JSON_UNQUOTE(JSON_EXTRACT(result_json,'$.succeededCent')) AS UNSIGNED)>0 AND state IN ('SUCCEEDED','FAILED')",parent,transaction);
                Stage1Exception.require(share!=null,409,"MOCK_SHARE_NOT_SUCCESSFUL");
                long taken=count("SELECT COALESCE(SUM(amount_cent),0) FROM li_p0_mock_funds_request WHERE parent_request=? AND kind='RETURN' AND state<>'FAILED'",parent);
                Stage1Exception.require(amount<=new Fields(share.get("result_json")).integer("succeededCent",0,Long.MAX_VALUE,true)-taken,409,"MOCK_RETURN_LIMIT");
            }
            if(kind.equals("REFUND")){
                long taken=count("SELECT COALESCE(SUM(amount_cent),0) FROM li_p0_mock_funds_request WHERE transaction_id=? AND kind='REFUND' AND state<>'FAILED'",transaction);
                Stage1Exception.require(amount<=paid.get("amountCent").getAsLong()-taken,409,"MOCK_REFUND_LIMIT");
            }
            var cap=capacity(request,kind,parent);Stage1Exception.require(cap.get("complete").getAsBoolean(),409,"CHANNEL_CAPACITY_UNKNOWN");
            if(Set.of("REFUND","RETURN").contains(kind))Stage45Money.capacity(cap.get("used").getAsLong(),0,cap.get("external").getAsLong(),1,0);
            var control=one("SELECT * FROM li_p0_mock_funds_control WHERE transaction_id=? AND kind=?",transaction,kind);
            String scenario=control==null?"SUCCESS":id(control,"scenario"),state=switch(scenario){case "UNKNOWN","DELAYED","FINISH_UNKNOWN"->"UNKNOWN";case "FAILURE","INSUFFICIENT","EXPIRED","DETAIL_FINISHED"->"FAILED";case "DETAIL_PARTIAL"->"PROCESSING";default->"SUCCEEDED";};
            var response=result(request,state,Set.of("FAILURE","INSUFFICIENT","EXPIRED").contains(scenario)?scenario:null);
            if(kind.equals("SHARE")&&Set.of("DETAIL_PARTIAL","DETAIL_FINISHED").contains(scenario))response=partial(request,response,scenario.equals("DETAIL_FINISHED"));
            update("INSERT INTO li_p0_mock_funds_request(request_no,transaction_id,kind,parent_request,amount_cent,request_hash,request_json,state,result_json,send_count,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,?,1,?,?)",no,transaction,kind,parent,amount,hash(request),write(request),state,write(response),clock.instant(),clock.instant());
            observe(no,response);
            if(scenario.equals("LOST_RESPONSE"))lost[0]=true;
            return response;
        });
        if(lost[0])throw new Stage1Exception(503,"MOCK_RESPONSE_LOST");return receipt;
    }
    public JsonObject query(JsonObject request){
        return tx.execute(status->{
            original(request);String no=request.get("requestNo").getAsString();
            var row=one("SELECT * FROM li_p0_mock_funds_request WHERE request_no=? FOR UPDATE",no);
            if(row==null){
                if("SHARE".equals(request.get("kind").getAsString())){
                    var finished=one("SELECT request_no,state FROM li_p0_mock_funds_request WHERE transaction_id=? AND kind='FINISH' AND state<>'FAILED' ORDER BY created_at,request_no LIMIT 1",request.get("transactionId").getAsString());
                    if(finished!=null){
                        if(!"SUCCEEDED".equals(id(finished,"state"))){var unknown=result(request,"UNKNOWN","ORIGINAL_FINISH_UNKNOWN");unknown.add("acceptedAt",JsonNull.INSTANCE);return signed(unknown);}
                        var blocked=result(request,"FAILED","ORIGINAL_ALREADY_UNFROZEN");blocked.add("acceptedAt",JsonNull.INSTANCE);blocked.addProperty("detailState","PARTIAL_FINAL");blocked.addProperty("retryAllowed",false);blocked.add("originalFinish",object(map("requestNo",finished.get("request_no"),"state","SUCCEEDED","source","INDEPENDENT_PROVIDER_LEDGER")));return signed(blocked);
                    }
                }return result(request,"NOT_FOUND",null);
            }
            Stage1Exception.require(hash(request).equals(id(row,"request_hash")),409,"MOCK_REQUEST_CONFLICT");
            var control=one("SELECT * FROM li_p0_mock_funds_control WHERE transaction_id=? AND kind=?",request.get("transactionId").getAsString(),request.get("kind").getAsString());
            if(control!=null&&"SUCCESS".equals(id(control,"scenario"))&&!Set.of("SUCCEEDED","FAILED").contains(id(row,"state"))){
                var receipt=result(request,"SUCCEEDED",null);receipt.addProperty("acceptedAt",instant(row.get("created_at")).toString());receipt=signed(receipt);if("SHARE".equals(request.get("kind").getAsString())){var old=object(row.get("result_json"));var receivers=old.getAsJsonArray("receivers").deepCopy();for(var raw:receivers){var detail=raw.getAsJsonObject();if(!"SUCCESS".equals(detail.get("state").getAsString()))detail.addProperty("succeededAt",clock.instant().toString());detail.addProperty("state","SUCCESS");}receipt.add("receivers",receivers);receipt=signed(receipt);}observe(no,receipt);update("UPDATE li_p0_mock_funds_request SET state='SUCCEEDED',result_json=?,updated_at=? WHERE request_no=?",write(receipt),clock.instant(),no);return receipt;
            }
            return object(row.get("result_json"));
        });
    }
    public JsonObject bill(String account,LocalDate date,Instant cutoff){
        Instant from=date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),to=date.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();var lines=new JsonArray();var missing=new JsonArray();
        for(var r:jdbc.queryForList("SELECT * FROM li_p0_mock_funds_request WHERE created_at>=? AND created_at<? AND created_at<=? ORDER BY request_no",java.sql.Timestamp.from(from),java.sql.Timestamp.from(to),java.sql.Timestamp.from(cutoff))){
            var request=object(r.get("request_json"));if(!account.equals(request.get("platformMerchantId").getAsString()))continue;
            var observation=one("SELECT result_json FROM li_p0_mock_funds_observation WHERE request_no=? AND observed_at<=? ORDER BY observed_at DESC,id DESC LIMIT 1",r.get("request_no"),java.sql.Timestamp.from(cutoff));
            if(observation==null){missing.add(id(r,"request_no"));continue;}var result=object(observation.get("result_json"));Stage1Exception.require(trusted(result),409,"BILL_SOURCE_UNTRUSTED");
            lines.add(object(map("requestNo",r.get("request_no"),"kind",r.get("kind"),"state",result.get("state"),"succeededCent",result.get("succeededCent"),"acceptedAt",result.get("acceptedAt"),"succeededAt",result.get("succeededAt"),"feeCent",null)));
        }
        return signed(object(map("mode","MOCK","account",account,"currency","CNY","businessDate",date.toString(),"timezone","Asia/Shanghai","cutoff",cutoff,"complete",missing.isEmpty(),"missingAsOfEvidence",missing,"source","MOCK_PROVIDER_LEDGER_NOT_REAL_BILL","history","APPEND_ONLY_OBSERVATIONS","lines",lines)));
    }
    public JsonObject capacity(JsonObject request,String kind,String parent){
        String transaction=request.get("transactionId").getAsString();original(request);
        var external=one("SELECT external_count,complete FROM li_p0_mock_funds_capacity WHERE transaction_id=? AND kind=?",transaction,kind);
        long used=kind.equals("RETURN")?count("SELECT COUNT(*) FROM li_p0_mock_funds_request WHERE parent_request=? AND kind='RETURN'",parent):count("SELECT COUNT(*) FROM li_p0_mock_funds_request WHERE transaction_id=? AND kind=?",transaction,kind);
        return signed(object(map("mode","MOCK","transactionId",transaction,"kind",kind,"parentRequest",parent,"used",used,"external",external==null?0:number(external,"external_count"),"complete",external==null||flag(external,"complete"),"observedAt",clock.instant())));
    }
}
