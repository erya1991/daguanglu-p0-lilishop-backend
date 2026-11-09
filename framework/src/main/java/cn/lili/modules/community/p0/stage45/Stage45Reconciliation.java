package cn.lili.modules.community.p0.stage45;

import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.time.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service @ConditionalOnProperty(name="p0.stage45.enabled",havingValue="true")
public class Stage45Reconciliation {
    private final Stage45Data d;private final Stage45Funds funds;
    public Stage45Reconciliation(Stage45Data d,Stage45Funds funds){this.d=d;this.funds=funds;}
    private void require(String action){Stage1Exception.require(d.auth.permissions(d.context.current().user()).contains(action),403,"FORBIDDEN");}
    public Object accounts(){require("reconciliation.read");return d.db.rows("SELECT id,mode,provider_mchid FROM li_p0_channel_config ORDER BY id").stream().map(r->map("configId",id(r,"id"),"mode",r.get("mode"),"platformMerchant",mask(id(r,"provider_mchid")))).toList();}
    public Object create(Object body){
        require("reconciliation.create");var f=new Fields(body).only("operationId","businessDate","configId");String config=f.id("configId",true);LocalDate date=f.date("businessDate",true);
        Stage1Exception.require(!date.isAfter(d.clock.instant().atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()),422,"BILL_DATE_IN_FUTURE");
        var c=d.db.required("SELECT mode,provider_mchid FROM li_p0_channel_config WHERE id=?",config);Stage1Exception.require("MOCK".equals(id(c,"mode")),403,"REAL_CHANNEL_NOT_AUTHORIZED");
        String account=id(c,"provider_mchid");Instant cutoff=d.clock.instant();var bill=funds.channel.bill(account,date,cutoff);
        Stage1Exception.require(funds.channel.trusted(bill)&&"MOCK".equals(bill.get("mode").getAsString())&&account.equals(bill.get("account").getAsString())&&date.toString().equals(bill.get("businessDate").getAsString()),409,"BILL_SOURCE_UNTRUSTED");
        Stage1Exception.require("CNY".equals(new Fields(bill).text("currency",1,8,true))&&"Asia/Shanghai".equals(new Fields(bill).text("timezone",1,32,true))&&cutoff.toString().equals(new Fields(bill).text("cutoff",1,64,true))&&bill.has("complete")&&bill.get("complete").isJsonPrimitive()&&bill.getAsJsonPrimitive("complete").isBoolean(),409,"BILL_COVERAGE_UNTRUSTED");
        return d.accountCommand("reconciliation.create",config,body,()->{
            var lines=new LinkedHashMap<String,Map<String,Object>>();Instant start=date.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),end=date.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
            for(var t:d.db.rows("SELECT * FROM li_p0_s45_task WHERE created_at>=? AND created_at<? AND created_at<=? ORDER BY id",start,end,cutoff)){
                var request=object(t.get("request_json"));if(!account.equals(request.get("platformMerchantId").getAsString()))continue;
                var e=d.db.one("SELECT result_json FROM li_p0_s45_channel_evidence WHERE task_id=? AND observed_at<=? ORDER BY observed_at DESC,id DESC LIMIT 1",t.get("id"),cutoff);
                var result=e==null?null:object(e.get("result_json"));Long actual=result==null?null:new Fields(result).integer("succeededCent",0,Long.MAX_VALUE,true);
                lines.put(id(t,"request_no"),map("requestNo",id(t,"request_no"),"kind",t.get("kind"),"localCent",actual,"localState",result==null?"NO_OBSERVATION":result.get("state"),"channelCent",null,"channelState",null,"feeCent",null));
            }
            for(var raw:bill.getAsJsonArray("lines")){
                var b=new Fields(raw);String no=b.text("requestNo",8,64,true),kind=b.choice("kind",Set.of("SHARE","RETURN","REFUND","FINISH"),true);var line=lines.computeIfAbsent(no,key->map("requestNo",no,"kind",kind,"localCent",null,"localState",null));
                Stage1Exception.require(kind.equals(line.get("kind")),409,"BILL_IDENTITY_MISMATCH");
                line.put("channelCent",b.integer("succeededCent",0,Long.MAX_VALUE,true));line.put("channelState",b.text("state",1,24,true));line.put("feeCent",b.integer("feeCent",0,Long.MAX_VALUE,false));
            }
            String id=d.db.id();var counts=new LinkedHashMap<String,Long>();counts.put("MATCH",0L);counts.put("MISSING_LOCAL",0L);counts.put("MISSING_CHANNEL",0L);counts.put("AMOUNT_MISMATCH",0L);counts.put("TIMING_OR_PENDING",0L);
            var classified=new ArrayList<Map<String,Object>>();
            for(var line:lines.values()){
                String classification=line.get("localState")==null?"MISSING_LOCAL":line.get("channelState")==null?(bill.get("complete").getAsBoolean()?"MISSING_CHANNEL":"TIMING_OR_PENDING"):!Objects.equals(String.valueOf(line.get("localCent")),String.valueOf(line.get("channelCent")))?"AMOUNT_MISMATCH":!Set.of("SUCCEEDED","FAILED").contains(String.valueOf(line.get("localState")))||!String.valueOf(line.get("localState")).equals(String.valueOf(line.get("channelState")))?"TIMING_OR_PENDING":"MATCH";
                counts.put(classification,counts.get(classification)+1);line.put("classification",classification);classified.add(line);
            }
            var internal=Stage45Consistency.scan(d,funds,account,date,cutoff);
            var coverage=map("internalChecks",internal,"scope","STAGE45_PROVIDER_REQUESTS_CREATED_ON_BUSINESS_DATE","timezone","Asia/Shanghai","cutoff",cutoff,"providerComplete",bill.get("complete"),"feeEvidence","NOT_PROVIDED","notWalletOrBankSettlement",true,"counts",counts);
            d.db.update("INSERT INTO li_p0_s45_reconciliation(id,mode,account,currency,business_date,zone_id,cutoff_at,source_type,source_hash,source_json,coverage_json,state,created_by,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)",id,"MOCK",account,"CNY",date,"Asia/Shanghai",cutoff,"MOCK_PROVIDER_LEDGER",hash(bill),write(bill),write(coverage),"RECORDED",d.context.current().id(),d.clock.instant());
            for(var line:classified)d.db.update("INSERT INTO li_p0_s45_reconciliation_line(id,reconciliation_id,request_no,kind,local_cent,channel_cent,bill_cent,fee_cent,classification,detail_json) VALUES(?,?,?,?,?,?,?,?,?,?)",d.db.id(),id,line.get("requestNo"),line.get("kind"),line.get("localCent"),line.get("channelCent"),line.get("channelCent"),line.get("feeCent"),line.get("classification"),write(map("localState",line.get("localState"),"channelState",line.get("channelState"),"source","MOCK_PROVIDER_LEDGER")));
            d.audit(null,"RECONCILIATION",id,"reconciliation.create",null,null,coverage);return detail(id);
        });
    }
    @Transactional(readOnly=true)
    public Object list(Map<String,String> query){
        require("reconciliation.read");int page=Stage45Data.page(query,"pageNumber",1,1000000),size=Stage45Data.page(query,"pageSize",20,50);
        long count=d.db.count("SELECT COUNT(*) FROM li_p0_s45_reconciliation");return Stage45Data.page(d.db.rows("SELECT * FROM li_p0_s45_reconciliation ORDER BY id DESC LIMIT ? OFFSET ?",size,(long)(page-1)*size).stream().map(this::view).toList(),count,page,size);
    }
    private Map<String,Object> view(Map<String,Object> r){var coverage=object(r.get("coverage_json")).deepCopy();if(coverage.has("internalChecks"))coverage.getAsJsonObject("internalChecks").remove("checkpoints");return map("id",id(r,"id"),"mode",r.get("mode"),"account",mask(id(r,"account")),"currency",r.get("currency"),"businessDate",String.valueOf(r.get("business_date")),"timezone",r.get("zone_id"),"cutoff",instant(r.get("cutoff_at")),"sourceType",r.get("source_type"),"sourceHash",r.get("source_hash"),"coverage",coverage,"state",r.get("state"),"createdAt",instant(r.get("created_at")));}
    @Transactional(readOnly=true)
    public Map<String,Object> detail(String id){
        require("reconciliation.read");var r=d.db.required("SELECT * FROM li_p0_s45_reconciliation WHERE id=?",id);var out=view(r);
        out.put("lines",d.db.rows("SELECT * FROM li_p0_s45_reconciliation_line WHERE reconciliation_id=? ORDER BY id",id).stream().map(l->map("id",id(l,"id"),"requestNo",l.get("request_no"),"kind",l.get("kind"),"localCent",l.get("local_cent"),"channelCent",l.get("channel_cent"),"billCent",l.get("bill_cent"),"feeCent",l.get("fee_cent"),"classification",l.get("classification"),"reviewReason",l.get("review_reason"),"reviewedAt",instant(l.get("reviewed_at")))).toList());return out;
    }
    public Object review(String line,Object body){
        require("reconciliation.review");var f=new Fields(body).only("operationId","reason");String reason=f.text("reason",2,500,true);
        var row=d.db.required("SELECT * FROM li_p0_s45_reconciliation_line WHERE id=?",line);
        return d.accountCommand("reconciliation.review",line,body,()->{
            // A review records evidence assessment; it cannot clear a monetary difference or issue a payment.
            d.db.update("UPDATE li_p0_s45_reconciliation_line SET reviewed_by=?,review_reason=?,reviewed_at=? WHERE id=?",d.context.current().id(),reason,d.clock.instant(),line);
            d.audit(null,"RECON_LINE",line,"reconciliation.review",reason,map("classification",row.get("classification")),map("reviewRecorded",true));return map("id",line,"classification",row.get("classification"),"reviewRecorded",true,"fundsChanged",false);
        });
    }
    private static String mask(String s){return s==null?null:s.length()<6?"****":s.substring(0,2)+"****"+s.substring(s.length()-2);}
}
