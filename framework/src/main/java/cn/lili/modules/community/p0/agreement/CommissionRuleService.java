package cn.lili.modules.community.p0.agreement;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.JsonObject;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static cn.lili.modules.community.p0.agreement.AgreementService.*;

/** Commercial rule versions and read-only arithmetic examples. No order snapshot or money operation. */
@Service
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
public class CommissionRuleService {
    private final Stage1Db db;private final Stage1Context context;private final Stage1Mutations mutations;private final AgreementService agreements;private final Clock clock;
    public Object preview(String id,long productPaidCent,long shippingCent){context.require(SessionDomain.MANAGER);var row=raw(id);var f=new Fields(row.get("terms"));long rate=f.integer("rateBps",0,10000,true);f.choice("baseCode",Set.of(BASE),true);f.choice("roundCode",Set.of(ROUND),true);var result=CommissionPreview.calculate(productPaidCent,shippingCent,rate);result.putAll(map("ruleId",id,"revision",row.get("revision"),"version",row.get("version"),"illustrationOnly",true));return result;}
    public CommissionRuleService(Stage1Db db,Stage1Context context,Stage1Mutations mutations,AgreementService agreements,@Qualifier("stage1Clock") Clock clock){this.db=db;this.context=context;this.mutations=mutations;this.agreements=agreements;this.clock=clock;}
    public Object create(Object body){
        var actor=context.require(SessionDomain.MANAGER);var f=new Fields(body).only("requestId","storeId","agreementId","businessType","predecessorId","payload");
        String store=f.id("storeId",true),agreement=f.id("agreementId",true),previous=f.id("predecessorId",false);f.choice("businessType",Set.of(BUSINESS),true);
        return mutations.run("rule.create",body,store,()->{
            var parent=agreements.raw(agreement);Stage1Exception.require(store.equals(id(parent,"store_id")),404,"RESOURCE_NOT_FOUND");var payload=validate(f.child("payload",true),false);String id=db.id(),key="S1R_"+id;long revision=1;
            if(previous!=null){var old=raw(previous);Stage1Exception.require(store.equals(id(old,"store_id"))&&old.get("published_at")!=null,409,"PREDECESSOR_INVALID");key=id(old,"rule_key");revision=db.count("SELECT COALESCE(MAX(revision),0)+1 FROM li_p0_commission_rule WHERE rule_key=?",key);}
            db.update("INSERT INTO li_p0_commission_rule(id,rule_key,revision,store_id,agreement_id,agreement_revision,business_type,base_code,round_code,status,terms,creator_id,created_at,updated_at,predecessor_id) VALUES(?,?,?,?,?,?,?,?,?,'DRAFT',?,?,?,?,?)",id,key,revision,store,agreement,number(parent,"revision"),BUSINESS,BASE,ROUND,write(payload),actor.id(),clock.instant(),clock.instant(),previous);
            project(id,payload);mutations.audit("rule.create",store,id,f.requestId(),null,0L,null);return view(raw(id));
        });
    }
    public Object save(String id,Object body){
        context.require(SessionDomain.MANAGER);var f=new Fields(body).only("requestId","expectedVersion","payload");String store=id(raw(id),"store_id");
        return mutations.run("rule.draft:"+id,body,store,()->{
            var row=raw(id);Stage1Exception.require(row.get("published_at")==null&&number(row,"version")==f.version(),409,"VERSION_IMMUTABLE");
            project(id,validate(f.child("payload",true),false));db.cas(db.update("UPDATE li_p0_commission_rule SET version=version+1,updated_at=? WHERE id=? AND version=?",clock.instant(),id,f.version()));
            mutations.audit("rule.draft",store,id,f.requestId(),f.version(),f.version()+1,null);return view(raw(id));
        });
    }
    private JsonObject validate(Object value,boolean complete){
        var f=new Fields(value).only("name","rateBps","baseCode","roundCode","startDate","endDate","commissionClause","triggerBasis","executionIntent","note");
        f.text("name",1,60,complete);f.integer("rateBps",0,10000,complete);f.choice("baseCode",Set.of(BASE),complete);f.choice("roundCode",Set.of(ROUND),complete);
        LocalDate from=f.date("startDate",complete),through=f.date("endDate",complete);if(from!=null&&through!=null)Stage1Rules.dates(from,through);
        f.text("commissionClause",1,2000,complete);f.choice("triggerBasis",Set.of("ORDER_COMPLETED_NO_BLOCKING_AFTERSALE"),complete);
        f.bool("executionIntent",false);f.text("note",0,500,false);return f.json();
    }
    private void project(String id,JsonObject payload){
        var f=new Fields(payload);LocalDate from=f.date("startDate",false),through=f.date("endDate",false);
        db.update("UPDATE li_p0_commission_rule SET terms=?,rate_bps=?,effective_from=?,effective_to=? WHERE id=?",write(payload),f.integer("rateBps",0,10000,false),from==null?null:from.atStartOfDay(Stage1Rules.ZONE).toInstant(),through==null?null:through.plusDays(1).atStartOfDay(Stage1Rules.ZONE).toInstant(),id);
    }
    public Object command(String id,Object body,boolean publish){
        context.require(SessionDomain.MANAGER);var f=new Fields(body).only("requestId","expectedVersion","reason");String store=id(raw(id),"store_id");
        return mutations.run("rule."+(publish?"publish:":"disable:")+id,body,store,()->{
            var row=raw(id);Stage1Exception.require(number(row,"version")==f.version(),409,"VERSION_CONFLICT");Instant now=clock.instant();String reason=f.text("reason",2,500,!publish);
            String audit=mutations.audit(publish?"rule.publish":"rule.disable",store,id,f.requestId(),f.version(),f.version()+1,reason);
            if(publish){
                Stage1Exception.require(row.get("published_at")==null,409,"VERSION_IMMUTABLE");var terms=validate(row.get("terms"),true);
                var agreement=agreements.raw(id(row,"agreement_id"));var permission=CooperationWindows.agreement(agreement);
                var window=new Stage1Rules.Window(CooperationWindows.max(instant(row.get("effective_from")),now),instant(row.get("effective_to")));
                Stage1Exception.require(permission!=null&&permission.covers(window)&&store.equals(id(agreement,"store_id"))&&number(row,"agreement_revision")==number(agreement,"revision"),422,"RULE_OUTSIDE_AGREEMENT");
                var contract=new Fields(agreement.get("terms"));var proposed=new Fields(terms);
                Stage1Exception.require(Objects.equals(contract.integer("rateBps",0,10000,true),proposed.integer("rateBps",0,10000,true))&&Objects.equals(contract.text("commissionClause",1,2000,true),proposed.text("commissionClause",1,2000,true)),422,"RULE_CLAUSE_MISMATCH");
                String previous=id(row,"predecessor_id");
                for(var old:db.rows("SELECT * FROM li_p0_commission_rule WHERE store_id=? AND business_type=? AND id<>? AND published_at IS NOT NULL",store,BUSINESS,id)){
                    var occupied=effectiveWindow(old);if(occupied==null||!occupied.overlaps(window))continue;
                    if(id(old,"id").equals(previous)){
                        Stage1Exception.require(old.get("replaced_at")==null&&window.from().isAfter(occupied.from()),409,"REPLACEMENT_WINDOW_INVALID");
                        db.update("UPDATE li_p0_commission_rule SET replaced_at=?,version=version+1 WHERE id=?",window.from(),previous);
                        db.update("INSERT INTO li_p0_cooperation_cutover(id,object_type,previous_id,replacement_id,cutoff_at,audit_reference,created_at) VALUES(?,'RULE',?,?,?,?,?)",db.id(),previous,id,window.from(),audit,now);
                    }else throw new Stage1Exception(409,"RULE_WINDOW_OVERLAP");
                }
                db.cas(db.update("UPDATE li_p0_commission_rule SET status='PUBLISHED',enabled=1,published_at=?,version=version+1,updated_at=? WHERE id=? AND version=?",now,now,id,f.version()));
            }else{
                Stage1Exception.require(row.get("published_at")!=null&&row.get("disabled_at")==null,409,"RULE_NOT_ENABLED");
                db.cas(db.update("UPDATE li_p0_commission_rule SET status='DISABLED',enabled=0,disabled_at=?,version=version+1,updated_at=? WHERE id=? AND version=?",now,now,id,f.version()));
            }
            mutations.invalidated(store,id,"STORE_ELIGIBILITY",f.version()+1);return view(raw(id));
        });
    }
    public Map<String,Object> raw(String id){return db.required("SELECT * FROM li_p0_commission_rule WHERE id=?",id);}
    public Stage1Rules.Window effectiveWindow(Map<String,Object> row){
        var own=CooperationWindows.rule(row);var contract=CooperationWindows.agreement(agreements.raw(id(row,"agreement_id")));if(own==null||contract==null)return null;
        Instant from=CooperationWindows.max(own.from(),contract.from()),to=CooperationWindows.min(own.to(),contract.to());return from.isBefore(to)?new Stage1Rules.Window(from,to):null;
    }
    public Map<String,Object> findEffectiveRule(String store,String business,Instant at){
        Stage1Exception.require(BUSINESS.equals(business),422,"BUSINESS_UNSUPPORTED");var matches=db.rows("SELECT * FROM li_p0_commission_rule WHERE store_id=? AND business_type=? AND published_at IS NOT NULL",store,business).stream().filter(r->{var w=effectiveWindow(r);return w!=null&&w.contains(at);}).toList();
        Stage1Exception.require(matches.size()<=1,409,"RULE_WINDOW_CONFLICT");return matches.isEmpty()?null:matches.getFirst();
    }
    public Object getImmutableVersion(String id,long revision){var row=raw(id);Stage1Exception.require(row.get("published_at")!=null&&number(row,"revision")==revision,404,"RESOURCE_NOT_FOUND");return view(row);}
    public Object detail(String id){context.require(SessionDomain.MANAGER);return view(raw(id));}
    public Object list(String store,int page,int size){context.require(SessionDomain.MANAGER);page(page,size);return map("total",db.count("SELECT COUNT(*) FROM li_p0_commission_rule WHERE (? IS NULL OR store_id=?)",store,store),"records",db.rows("SELECT * FROM li_p0_commission_rule WHERE (? IS NULL OR store_id=?) ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",store,store,size,(page-1)*size).stream().map(this::view).toList());}
    public Object versions(String id){context.require(SessionDomain.MANAGER);var row=raw(id);return db.rows("SELECT * FROM li_p0_commission_rule WHERE rule_key=? ORDER BY revision DESC",row.get("rule_key")).stream().map(this::view).toList();}
    public Object storeList(String store){return db.rows("SELECT * FROM li_p0_commission_rule WHERE store_id=? AND published_at IS NOT NULL ORDER BY created_at DESC,id DESC",store).stream().map(this::view).toList();}
    public Object view(Map<String,Object> row){var window=effectiveWindow(row);return map("id",id(row,"id"),"storeId",id(row,"store_id"),"ruleKey",row.get("rule_key"),"revision",row.get("revision"),"version",row.get("version"),"agreementId",id(row,"agreement_id"),"agreementRevision",row.get("agreement_revision"),"businessType",BUSINESS,"status",CooperationWindows.state(row,false,clock.instant()),"payload",object(row.get("terms")),"actualFrom",window==null?null:window.from(),"actualTo",window==null?null:window.to(),"publishedAt",instant(row.get("published_at")),"disabledAt",instant(row.get("disabled_at")),"replacedAt",instant(row.get("replaced_at")),"predecessorId",id(row,"predecessor_id"),"fundExecutionEnabled",false,"realChannelValidation","CHANNEL_PENDING");}
}
