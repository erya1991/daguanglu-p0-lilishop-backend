package cn.lili.modules.community.p0.eligibility;

import cn.lili.modules.community.p0.agreement.*;
import cn.lili.modules.community.p0.support.*;
import cn.lili.common.security.session.http.SessionApplications;
import com.google.gson.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Reads one consistent set of current facts. Never writes an eligibility approval or uses a TTL decision. */
@Service
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
public class EligibilityService {
    private final Stage1Db db;private final AgreementService agreements;private final CommissionRuleService rules;private final Stage1Settings settings;private final Clock clock;private final TransactionTemplate read;private final SessionApplications applications;
    public EligibilityService(Stage1Db db,AgreementService agreements,CommissionRuleService rules,Stage1Settings settings,PlatformTransactionManager manager,@Qualifier("stage1Clock") Clock clock,SessionApplications applications){
        this.db=db;this.agreements=agreements;this.rules=rules;this.settings=settings;this.clock=clock;this.applications=applications;this.read=new TransactionTemplate(manager);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    public Map<String,Object> evaluate(String store,String category,Instant at){
        Instant time=at==null?clock.instant():at;
        return read.execute(ignored->{var result=one(store,category,time);if(category==null){var differences=new ArrayList<Object>();for(String id:operatingLeaves(store))differences.add(one(store,id,time));result.put("categories",differences);}return result;});
    }
    private Set<String> operatingLeaves(String store){
        Set<String> permitted=new TreeSet<>();array(db.required("SELECT category_ids FROM li_p0_store_control WHERE store_id=?",store).get("category_ids")).forEach(v->permitted.add(v.getAsString()));
        Map<String,List<String>> children=new HashMap<>();for(var row:db.rows("SELECT id,parent_id FROM li_category WHERE delete_flag=0"))children.computeIfAbsent(id(row,"parent_id"),key->new ArrayList<>()).add(id(row,"id"));
        Set<String> result=new TreeSet<>();for(String id:permitted)collectLeaves(id,children,new HashSet<>(),result);return result;
    }
    private void collectLeaves(String id,Map<String,List<String>> children,Set<String> path,Set<String> result){Stage1Exception.require(path.add(id)&&path.size()<=3,409,"CATEGORY_TREE_INVALID");var descendants=children.getOrDefault(id,List.of());if(descendants.isEmpty())result.add(id);else for(String child:descendants)collectLeaves(child,children,new HashSet<>(path),result);}
    public void requireBasic(String store,String category){var result=evaluate(store,category,null);if(!Boolean.TRUE.equals(result.get("BASIC_ELIGIBLE")))throw new Stage1Exception(409,"BASIC_NOT_ELIGIBLE",map("eligibility",result));}
    private Map<String,Object> one(String store,String category,Instant at){
        var source=new LinkedHashMap<String,Object>();var until=new ArrayList<Instant>();
        var basic=new ArrayList<Map<String,Object>>();var mock=new ArrayList<Map<String,Object>>();var real=new ArrayList<Map<String,Object>>();
        var current=db.required("SELECT s.id,s.store_disable,s.delete_flag,c.version,c.admission_state,c.category_ids FROM li_store s JOIN li_p0_store_control c ON c.store_id=s.id WHERE s.id=?",store);
        source.put("store",map("id",store,"version",current.get("version"),"state",current.get("store_disable")));
        if(!"APPROVED".equals(id(current,"admission_state"))||!"OPEN".equals(id(current,"store_disable"))||flag(current,"delete_flag"))reason(basic,"STORE_INACTIVE","store",store);
        var permitted=array(current.get("category_ids")).asList().stream().map(JsonElement::getAsString).toList();
        List<String> lineage=category==null?List.of():lineage(category,source,basic);
        if(category!=null&&Collections.disjoint(lineage,permitted))reason(basic,"CATEGORY_NOT_ALLOWED","category",category);
        Set<String> required=new TreeSet<>();db.rows("SELECT code FROM li_p0_qualification_type WHERE scope='STORE' AND enabled=1").forEach(r->required.add(id(r,"code")));
        if(required.isEmpty())reason(basic,"QUALIFICATION_POLICY_PENDING","store",store);
        for(String c:lineage)for(var q:db.rows("SELECT qualification_type,version FROM li_p0_category_qualification WHERE category_id=? AND required=1",c)){required.add(id(q,"qualification_type"));source.put("qualificationPolicy:"+c+":"+id(q,"qualification_type"),q.get("version"));}
        var certificates=db.rows("SELECT * FROM li_p0_qualification WHERE store_id=? ORDER BY qualification_key,revision",store);
        var certificateSources=new ArrayList<Object>();
        for(var q:certificates){certificateSources.add(map("id",id(q,"id"),"revision",q.get("revision"),"version",q.get("version"),"state",q.get("status")));boundaries(until,at,instant(q.get("valid_from")),instant(q.get("valid_to")),instant(q.get("approved_at")),instant(q.get("superseded_at")),instant(q.get("revoked_at")));}
        source.put("qualifications",certificateSources);
        var warnings=new ArrayList<Object>();
        for(var q:certificates){
            Instant from=CooperationWindows.max(instant(q.get("valid_from")),instant(q.get("approved_at"))),replaced=instant(q.get("superseded_at")),revoked=instant(q.get("revoked_at")),end=instant(q.get("valid_to"));
            if(!Set.of("APPROVED","SUPERSEDED").contains(id(q,"status"))||from==null||at.isBefore(from)||replaced!=null&&!at.isBefore(replaced)||revoked!=null&&!at.isBefore(revoked))continue;
            if(category!=null&&!"STORE".equals(id(q,"scope"))&&Collections.disjoint(array(q.get("category_ids")).asList().stream().map(JsonElement::getAsString).toList(),lineage))continue;
            if(end!=null)for(int days:List.of(30,7))boundaries(until,at,QualificationExpiry.validThrough(end).minusDays(days).atStartOfDay(Stage1Rules.ZONE).toInstant());
            String warning=QualificationExpiry.warning(end,at);
            if(warning!=null)warnings.add(map("id",id(q,"id"),"revision",q.get("revision"),"version",q.get("version"),"type",q.get("type"),"scope",q.get("scope"),"categoryIds",array(q.get("category_ids")),"warning",warning,"validThrough",QualificationExpiry.validThrough(end)));
        }

        for(String type:required){
            boolean available=certificates.stream().anyMatch(q->{
                if(!type.equals(id(q,"type"))||!Set.of("APPROVED","SUPERSEDED").contains(id(q,"status")))return false;
                Instant from=CooperationWindows.max(instant(q.get("valid_from")),instant(q.get("approved_at"))),to=CooperationWindows.min(instant(q.get("valid_to")),instant(q.get("superseded_at")),instant(q.get("revoked_at")));
                boolean scope="STORE".equals(id(q,"scope"))||category!=null&&!Collections.disjoint(array(q.get("category_ids")).asList().stream().map(JsonElement::getAsString).toList(),lineage);
                return scope&&from!=null&&!at.isBefore(from)&&(to==null||at.isBefore(to));
            });
            if(!available)reason(basic,"LICENSE_EXPIRED","qualificationType",type);
        }
        Map<String,Object> agreement=null,rule=null;
        try{agreement=agreements.effective(store,at);}catch(Stage1Exception conflict){if(conflict.status()!=409)throw conflict;reason(basic,"AGREEMENT_WINDOW_CONFLICT","store",store);}
        if(agreement==null)reason(basic,"AGREEMENT_MISSING","store",store);
        else{
            var window=CooperationWindows.agreement(agreement);source.put("agreement",map("id",id(agreement,"id"),"revision",agreement.get("revision"),"version",agreement.get("version")));boundaries(until,at,window.to());
            boolean evidence=agreements.evidenceAvailable(agreement);source.put("agreementEvidenceAvailable",evidence);if(!evidence)reason(basic,"AGREEMENT_ATTACHMENT_UNAVAILABLE","agreement",id(agreement,"id"));
            if(category!=null&&Collections.disjoint(array(agreement.get("scope")).asList().stream().map(JsonElement::getAsString).toList(),lineage))reason(basic,"CATEGORY_NOT_ALLOWED","agreement",id(agreement,"id"));
        }
        for(var future:db.rows("SELECT effective_from,effective_to,confirmed_at,replaced_at,terminated_at FROM li_p0_agreement WHERE store_id=? AND confirmed_at IS NOT NULL",store))for(var value:future.values())boundaries(until,at,instant(value));
        try{rule=rules.findEffectiveRule(store,AgreementService.BUSINESS,at);}catch(Stage1Exception conflict){if(conflict.status()!=409)throw conflict;reason(mock,"RULE_WINDOW_CONFLICT","store",store);reason(real,"RULE_WINDOW_CONFLICT","store",store);}
        if(rule==null){reason(mock,"RULE_MISSING","store",store);reason(real,"RULE_MISSING","store",store);}
        else{source.put("rule",map("id",id(rule,"id"),"revision",rule.get("revision"),"version",rule.get("version")));boundaries(until,at,rules.effectiveWindow(rule).to());}
        for(var future:db.rows("SELECT effective_from,effective_to,published_at,disabled_at,replaced_at FROM li_p0_commission_rule WHERE store_id=? AND published_at IS NOT NULL",store))for(var value:future.values())boundaries(until,at,instant(value));
        mock.addAll(basic);real.addAll(basic);
        if(!"MOCK".equals(settings.getMode())||!settings.permitsMode("MOCK"))reason(mock,"MOCK_MODE_UNAVAILABLE","environment",settings.getEnvironment());
        if(!settings.permitsMode("REAL"))reason(real,"CHANNEL_ENVIRONMENT_INVALID","environment",settings.getEnvironment());
        channel(store,"MOCK",rule,at,mock,source,until);channel(store,"REAL",rule,at,real,source,until);
        sort(basic);sort(mock);sort(real);
        return map("storeId",store,"categoryId",category,"categoryName",source.get("category:"+category) instanceof Map<?,?> entry?entry.get("name"):null,"businessType",AgreementService.BUSINESS,"environment",settings.getEnvironment(),"authorizedMode",settings.getMode(),"evaluatedAt",at,"validUntil",until.stream().min(Instant::compareTo).orElse(null),"BASIC_ELIGIBLE",basic.isEmpty(),"MOCK_READY",mock.isEmpty(),"REAL_READY",real.isEmpty(),"reasons",map("BASIC",basic,"MOCK",mock,"REAL",real),"qualificationWarnings",warnings,"sourceVersions",source,"sourceFingerprint",hash(source));
    }
    private List<String> lineage(String category,Map<String,Object> source,List<Map<String,Object>> reasons){
        var result=new ArrayList<String>();String current=category;
        while(current!=null&&!current.equals("0")){
            Stage1Exception.require(!result.contains(current)&&result.size()<10,409,"CATEGORY_CYCLE");result.add(current);
            var row=db.one("SELECT c.name,c.parent_id,c.delete_flag,p.version,p.enabled FROM li_category c LEFT JOIN li_p0_catalog_control p ON p.object_id=c.id AND p.object_type='CATEGORY' WHERE c.id=?",current);
            if(row==null||flag(row,"delete_flag")||!flag(row,"enabled")){reason(reasons,"CATEGORY_NOT_ALLOWED","category",current);if(row==null)break;}
            source.put("category:"+current,map("name",row.get("name"),"version",row.get("version"),"enabled",flag(row,"enabled")));current=id(row,"parent_id");
        }return List.copyOf(result);
    }
    private void channel(String store,String mode,Map<String,Object> rule,Instant at,List<Map<String,Object>> reasons,Map<String,Object> sources,List<Instant> until){
        var rows=db.rows("SELECT r.*,a.state AS application_state,c.enabled AS config_enabled,c.mode AS config_mode,c.environment,c.config_version AS current_config_version,c.payment_appid FROM li_p0_submerchant_relation r JOIN li_p0_channel_config c ON c.id=r.config_id LEFT JOIN li_p0_channel_application a ON a.id=r.application_id AND a.relation_id=r.id AND a.config_id=r.config_id AND a.mode=r.mode WHERE r.store_id=? AND r.mode=? AND r.active_store_key IS NOT NULL",store,mode);
        if(rows.size()!=1){reason(reasons,"CHANNEL_PENDING","channelMode",mode);return;}var row=rows.getFirst();String id=id(row,"id");
        sources.put(mode+"Channel",map("id",id,"version",row.get("version"),"configId",id(row,"config_id"),"configVersion",row.get("current_config_version"),"evidenceVersion",row.get("evidence_version"),"lastSyncState",row.get("last_sync_state"),"applicationState",row.get("application_state"),"verifiedAt",instant(row.get("verified_at"))));
        boolean okay=mode.equals(id(row,"config_mode"))&&settings.getEnvironment().equals(id(row,"environment"))&&flag(row,"enabled")&&flag(row,"config_enabled")&&number(row,"config_version")==number(row,"current_config_version")&&"COMPLETED".equals(id(row,"application_state"))&&"SUCCESS".equals(id(row,"last_sync_state"))&&"VERIFIED".equals(id(row,"capability_state"))&&row.get("sub_mchid")!=null;
        var verified=instant(row.get("verified_at"));if(verified==null||verified.isAfter(at)||!at.isBefore(verified.plusSeconds(86400)))okay=false;else boundaries(until,at,verified.plusSeconds(86400));
        if(!okay){reason(reasons,"SUB_MERCHANT_UNVERIFIED","submerchant",id);reason(reasons,"CHANNEL_PENDING","channelMode",mode);}
        var evidence=row.get("capability_evidence")==null?new JsonObject():object(row.get("capability_evidence"));
        boolean trustworthy=mode.equals(string(evidence,"sourceMode"))&&bool(evidence,"paymentEnabled")&&bool(evidence,"appIdLinked")&&Objects.equals(string(evidence,"paymentAppId"),id(row,"payment_appid"));
        if(mode.equals("REAL")){String paymentApp=applications.getResidentApplication();trustworthy&=paymentApp!=null&&paymentApp.matches("wx[a-fA-F0-9]{16}")&&paymentApp.equals(id(row,"payment_appid"))&&!paymentApp.equals(applications.getMerchantApplication());}
        if(!trustworthy)reason(reasons,"CHANNEL_PENDING","submerchant",id);
        if(rule!=null&&number(rule,"rate_bps")>0){
            Long cap=null;try{cap=new Fields(evidence).integer("maxRateBps",0,10000,false);}catch(Stage1Exception ignored){/* Invalid evidence cannot authorize money-related readiness. */}
            if(cap==null||cap<number(rule,"rate_bps")||!bool(evidence,"profitsharingEnabled"))reason(reasons,"CHANNEL_LIMIT_UNVERIFIED","submerchant",id);
            var receiver=row.get("receiver_id")==null?null:db.one("SELECT * FROM li_p0_receiver_relation WHERE id=? AND config_id=? AND mode=?",row.get("receiver_id"),row.get("config_id"),mode);
            Instant receiverAt=receiver==null?null:instant(receiver.get("verified_at"));
            if(receiver==null||!flag(receiver,"enabled")||!cn.lili.modules.community.p0.channel.ReceiverEvidence.current(receiver,mode,number(row,"current_config_version"),at))reason(reasons,"RECEIVER_UNVERIFIED","submerchant",id);
            else{sources.put(mode+"Receiver",map("id",id(receiver,"id"),"version",receiver.get("version"),"verifiedAt",receiverAt));boundaries(until,at,receiverAt.plusSeconds(86400));}
        }
    }
    private static String string(JsonObject value,String key){return value.has(key)&&!value.get(key).isJsonNull()&&value.get(key).isJsonPrimitive()&&value.getAsJsonPrimitive(key).isString()?value.get(key).getAsString():null;}
    private static boolean bool(JsonObject value,String key){return value.has(key)&&value.get(key).isJsonPrimitive()&&value.getAsJsonPrimitive(key).isBoolean()&&value.get(key).getAsBoolean();}
    private static void reason(List<Map<String,Object>> reasons,String code,String kind,String id){var reason=map("code",code,"objectType",kind,"objectId",id);if(!reasons.contains(reason))reasons.add(reason);}
    private static void sort(List<Map<String,Object>> reasons){reasons.sort(Comparator.comparing(r->r.get("code")+":"+r.get("objectType")+":"+r.get("objectId")));}
    private static void boundaries(List<Instant> target,Instant at,Instant... values){for(var value:values)if(value!=null&&value.isAfter(at))target.add(value);}
}
