package cn.lili.modules.community.p0.stage45;

import cn.lili.modules.community.p0.support.*;
import cn.lili.modules.community.p0.channel.ChannelService;
import cn.lili.modules.community.p0.agreement.CommissionRuleService;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service @ConditionalOnProperty(name="p0.stage45.enabled",havingValue="true")
public class Stage45Channels {
    private final Stage45Data d;private final ChannelService channels;private final CommissionRuleService rules;
    public Stage45Channels(Stage45Data d,ChannelService channels,CommissionRuleService rules){this.d=d;this.channels=channels;this.rules=rules;}
    private void require(String permission){Stage1Exception.require(d.auth.permissions(d.context.current().user()).contains(permission),403,"FORBIDDEN");}
    public Object list(Map<String,String> query){
        require("channel.read");int page=Stage45Data.page(query,"pageNumber",1,1000000),size=Stage45Data.page(query,"pageSize",20,50);
        String q=query.getOrDefault("q","").trim();Stage1Exception.require(q.length()<=80,422,"QUERY_INVALID");String like="%"+q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        String from=" FROM li_p0_submerchant_relation r JOIN li_store s ON s.id=r.store_id WHERE s.store_name LIKE ?";
        long total=d.db.count("SELECT COUNT(*)"+from,like);return Stage45Data.page(d.db.rows("SELECT r.id"+from+" ORDER BY r.id DESC LIMIT ? OFFSET ?",like,size,(long)(page-1)*size).stream().map(r->view(id(r,"id"))).toList(),total,page,size);
    }
    public Map<String,Object> view(String id){
        require("channel.read");var row=d.db.required("SELECT r.*,s.store_name,c.provider_mchid,c.payment_appid,a.state AS application_state,x.display_name AS receiver_name,x.account_hash,x.state AS receiver_state FROM li_p0_submerchant_relation r JOIN li_store s ON s.id=r.store_id JOIN li_p0_channel_config c ON c.id=r.config_id JOIN li_p0_channel_application a ON a.id=r.application_id LEFT JOIN li_p0_receiver_relation x ON x.id=r.receiver_id WHERE r.id=?",id);
        var checks=capabilityChecks(row,d.clock.instant());var capabilities=new LinkedHashMap<String,Object>();
        checks.forEach((key,value)->capabilities.put(key,value.get("status")));
        return map("id",id,"storeId",id(row,"store_id"),"storeName",row.get("store_name"),"configId",id(row,"config_id"),"mode",row.get("mode"),"version",row.get("version"),"platformMerchant",mask(id(row,"provider_mchid")),"subMerchant",mask(id(row,"sub_mchid")),"receiverName",row.get("receiver_name"),"receiverState",row.get("receiver_state"),"capabilities",capabilities,"capabilityChecks",checks,"enabled",flag(row,"enabled"),"replaced",row.get("active_store_key")==null,"verifiedAt",instant(row.get("verified_at")),"lastSyncState",row.get("last_sync_state"),"canSync",d.auth.permissions(d.context.current().user()).contains("channel.sync"),"history",d.db.rows("SELECT action,status,created_at FROM li_p0_channel_operation WHERE object_id=? AND object_type='APPLICATION' ORDER BY id DESC LIMIT 30",id).stream().map(r->map("action",r.get("action"),"state",r.get("status"),"at",instant(r.get("created_at")))).toList());
    }
    public Object sync(String id,Object body){
        require("channel.sync");d.gate.writes();var f=new Fields(body).only("operationId","expectedVersion","reason");
        var row=d.db.required("SELECT mode FROM li_p0_submerchant_relation WHERE id=?",id);Stage1Exception.require("MOCK".equals(id(row,"mode")),403,"REAL_CHANNEL_NOT_AUTHORIZED");
        String operation=cn.lili.modules.community.p0.stage2.Stage2Transactions.operation(f);
        channels.command(id,map("requestId",operation,"expectedVersion",f.version(),"reason",f.text("reason",2,500,true)),"query");
        var actor=d.context.current();return object(d.db.required("SELECT response_json FROM li_p0_operation WHERE identity_domain='MANAGER' AND actor_id=? AND request_id=? AND action=?",actor.id(),operation,"submerchant.query:"+id).get("response_json"));
    }
    public Object rules(Map<String,String> query){
        require("rule.read");String store=query.get("storeId");if(store!=null)new Fields(map("storeId",store)).id("storeId",true);
        int page=Stage45Data.page(query,"pageNumber",1,1000000),size=Stage45Data.page(query,"pageSize",20,50);
        return rules.list(store,page,size);
    }
    public Object rule(String id){require("rule.read");return map("current",rules.detail(id),"versions",rules.versions(id),"policy","商品款按历史整店基点向下取整；固定履约费不计佣；新规则不追溯已付款订单。");}
    static Map<String,Map<String,Object>> capabilityChecks(Map<String,Object> row,java.time.Instant now) {
        var evidence=row.get("capability_evidence")==null?new com.google.gson.JsonObject():object(row.get("capability_evidence"));
        String mode=id(row,"mode");var verifiedAt=instant(row.get("verified_at"));
        boolean sourceMatches=evidence.has("sourceMode")&&mode.equals(evidence.get("sourceMode").getAsString())
                &&evidence.has("paymentAppId")&&Objects.equals(id(row,"payment_appid"),evidence.get("paymentAppId").getAsString())
                &&evidence.has("verificationState")&&"VERIFIED".equals(evidence.get("verificationState").getAsString());
        boolean timely=verifiedAt!=null&&!verifiedAt.isAfter(now)&&verifiedAt.isAfter(now.minusSeconds(86400));
        boolean current=timely&&"SUCCESS".equals(id(row,"last_sync_state"));
        var fields=Map.of("payment","paymentEnabled","profitsharing","profitsharingEnabled","refund","refundEnabled","return","returnEnabled","bill","billEnabled");
        var result=new LinkedHashMap<String,Map<String,Object>>();
        for(String key:List.of("onboarding","payment","profitsharing","refund","return","bill")){
            Boolean available=null;
            if(key.equals("onboarding")){
                if("COMPLETED".equals(id(row,"application_state")))available=true;
                else if("REJECTED".equals(id(row,"application_state")))available=false;
            }else if(sourceMatches){
                var value=evidence.get(fields.get(key));
                if(value!=null&&value.isJsonPrimitive()&&value.getAsJsonPrimitive().isBoolean())available=value.getAsBoolean();
            }
            String known=available==null?"EVIDENCE_PENDING":available?("MOCK".equals(mode)?"MOCK_READY":"VERIFIED"):"RESTRICTED";
            String status=available==null?"EVIDENCE_PENDING":current?known:"STALE";
            String reason=available==null?"尚无此项独立能力证据":!current?"保留上次核验事实；当前需重新核验":available?"此项能力已有对应模式的核验依据":"此项能力的核验结果受限";
            result.put(key,map("status",status,"lastKnownStatus",known,"reason",reason,"verifiedAt",available==null?null:verifiedAt));
        }
        return result;
    }
    private static String mask(String s){return s==null?null:s.length()<6?"****":s.substring(0,2)+"****"+s.substring(s.length()-2);}
}
