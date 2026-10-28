package cn.lili.modules.community.p0.agreement;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
public class AgreementService {
    public static final String BUSINESS="MALL",BASE="PRODUCT_PAID_EXCLUDING_SHIPPING",ROUND="FLOOR_CENT";
    private final Stage1Db db;private final Stage1Context context;private final Stage1Mutations mutations;private final Stage1Files files;private final Clock clock;
    public AgreementService(Stage1Db db,Stage1Context context,Stage1Mutations mutations,Stage1Files files,@Qualifier("stage1Clock") Clock clock){this.db=db;this.context=context;this.mutations=mutations;this.files=files;this.clock=clock;}
    public Object create(Object body){
        var actor=context.require(SessionDomain.MANAGER);var f=new Fields(body).only("requestId","storeId","businessType","predecessorId","payload");
        String store=f.id("storeId",true),previous=f.id("predecessorId",false);f.choice("businessType",Set.of(BUSINESS),true);
        return mutations.run("agreement.create",body,store,()->{
            var payload=validate(f.child("payload",true),false,store,actor.id());String id=db.id(),number="S1A_"+id;long revision=1;
            if(previous!=null){var old=raw(previous);Stage1Exception.require(store.equals(id(old,"store_id"))&&old.get("confirmed_at")!=null,409,"PREDECESSOR_INVALID");number=id(old,"agreement_no");revision=db.count("SELECT COALESCE(MAX(revision),0)+1 FROM li_p0_agreement WHERE agreement_no=?",number);}
            db.update("INSERT INTO li_p0_agreement(id,agreement_no,revision,store_id,business_type,status,subject_refs,scope,file_refs,terms,creator_id,created_at,updated_at,predecessor_id) VALUES(?,?,?,?,?,'DRAFT','{}','[]','[]',?,?,?,?,?)",id,number,revision,store,BUSINESS,write(payload),actor.id(),clock.instant(),clock.instant(),previous);
            project(id,store,payload,actor.id());mutations.audit("agreement.create",store,id,f.requestId(),null,0L,null);return view(raw(id));
        });
    }
    public Object save(String id,Object body){
        var actor=context.require(SessionDomain.MANAGER);var f=new Fields(body).only("requestId","expectedVersion","payload");String store=id(raw(id),"store_id");
        return mutations.run("agreement.draft:"+id,body,store,()->{
            var row=raw(id);Stage1Exception.require(row.get("confirmed_at")==null&&number(row,"version")==f.version(),409,"VERSION_IMMUTABLE");
            var payload=validate(f.child("payload",true),false,store,actor.id());project(id,store,payload,actor.id());
            db.cas(db.update("UPDATE li_p0_agreement SET version=version+1,updated_at=? WHERE id=? AND version=?",clock.instant(),id,f.version()));
            mutations.audit("agreement.draft",store,id,f.requestId(),f.version(),f.version()+1,null);return view(raw(id));
        });
    }
    private JsonObject validate(Object input,boolean complete,String store,String actor){
        var f=new Fields(input).only("name","platformLegalName","merchantLegalName","merchantLegalCode","categoryIds","startDate","endDate","fileIds","confirmationReference","rateBps","baseCode","roundCode","commissionClause","refundSummary");
        f.text("name",1,100,complete);f.text("platformLegalName",2,100,complete);String name=f.text("merchantLegalName",2,100,complete),code=f.text("merchantLegalCode",18,18,complete);
        var current=db.required("SELECT c.category_ids,d.company_name,d.license_num FROM li_p0_store_control c JOIN li_store_detail d ON d.store_id=c.store_id WHERE c.store_id=? AND c.admission_state='APPROVED'",store);
        if(name!=null)Stage1Exception.require(name.equals(id(current,"company_name")),422,"AGREEMENT_SUBJECT_MISMATCH");
        if(code!=null)Stage1Exception.require(Stage1Rules.legalCode(code).equals(id(current,"license_num")),422,"AGREEMENT_SUBJECT_MISMATCH");
        var categories=f.ids("categoryIds",complete?1:0,100,complete);var allowed=array(current.get("category_ids")).asList().stream().map(JsonElement::getAsString).toList();
        Stage1Exception.require(allowed.containsAll(categories),422,"AGREEMENT_SCOPE_INVALID");
        LocalDate from=f.date("startDate",complete),through=f.date("endDate",complete);if(from!=null&&through!=null)Stage1Rules.dates(from,through);
        f.text("confirmationReference",1,255,complete);f.integer("rateBps",0,10000,complete);f.choice("baseCode",Set.of(BASE),complete);f.choice("roundCode",Set.of(ROUND),complete);
        f.text("commissionClause",1,2000,complete);f.text("refundSummary",1,2000,complete);
        var refs=f.ids("fileIds",complete?1:0,10,complete);if(!refs.isEmpty())files.validate(refs,"AGREEMENT",actor,store);
        return f.json();
    }
    private void project(String id,String store,JsonObject payload,String actor){
        var f=new Fields(payload);LocalDate from=f.date("startDate",false),through=f.date("endDate",false);
        var refs=f.ids("fileIds",0,10,false);if(!refs.isEmpty())files.bind(refs,"AGREEMENT",actor,store,id);
        db.update("UPDATE li_p0_agreement SET terms=?,subject_refs=?,scope=?,file_refs=?,confirmation_reference=?,effective_from=?,effective_to=? WHERE id=?",write(payload),write(map("platform",f.text("platformLegalName",2,100,false),"merchant",f.text("merchantLegalName",2,100,false),"legalCode",f.text("merchantLegalCode",18,18,false))),write(f.ids("categoryIds",0,100,false)),write(refs),f.text("confirmationReference",1,255,false),from==null?null:from.atStartOfDay(Stage1Rules.ZONE).toInstant(),through==null?null:through.plusDays(1).atStartOfDay(Stage1Rules.ZONE).toInstant(),id);
    }
    public Object command(String id,Object body,String action){
        var actor=context.require(SessionDomain.MANAGER);var f=new Fields(body).only("requestId","expectedVersion","reason");String store=id(raw(id),"store_id");
        return mutations.run("agreement."+action+":"+id,body,store,()->{
            var row=raw(id);Stage1Exception.require(number(row,"version")==f.version(),409,"VERSION_CONFLICT");Instant now=clock.instant();
            String reason=f.text("reason",2,500,!action.equals("confirm"));String audit=mutations.audit("agreement."+action,store,id,f.requestId(),f.version(),f.version()+1,reason);
            if(action.equals("confirm")){
                Stage1Exception.require(row.get("confirmed_at")==null,409,"VERSION_IMMUTABLE");validate(row.get("terms"),true,store,actor.id());
                var window=new Stage1Rules.Window(CooperationWindows.max(instant(row.get("effective_from")),now),instant(row.get("effective_to")));
                var oldRows=db.rows("SELECT * FROM li_p0_agreement WHERE store_id=? AND business_type=? AND id<>? AND confirmed_at IS NOT NULL",store,BUSINESS,id);
                String previous=id(row,"predecessor_id");
                for(var old:oldRows){var occupied=CooperationWindows.agreement(old);if(occupied==null)continue;
                    if(id(old,"id").equals(previous)&&occupied.overlaps(window)){
                        Stage1Exception.require(old.get("replaced_at")==null&&window.from().isAfter(occupied.from()),409,"REPLACEMENT_WINDOW_INVALID");
                        // Truncation is an explicit event; original contractual dates and attachments stay unchanged.
                        db.update("UPDATE li_p0_agreement SET replaced_at=?,version=version+1 WHERE id=?",window.from(),previous);
                        db.update("INSERT INTO li_p0_cooperation_cutover(id,object_type,previous_id,replacement_id,cutoff_at,audit_reference,created_at) VALUES(?,'AGREEMENT',?,?,?,?,?)",db.id(),previous,id,window.from(),audit,now);
                    }else Stage1Exception.require(!occupied.overlaps(window),409,"AGREEMENT_WINDOW_OVERLAP");
                }
                db.cas(db.update("UPDATE li_p0_agreement SET confirmed_at=?,status='CONFIRMED',version=version+1,updated_at=? WHERE id=? AND version=?",now,now,id,f.version()));
            }else if(action.equals("cancel")){
                var window=CooperationWindows.agreement(row);Stage1Exception.require(window!=null&&now.isBefore(window.from()),409,"AGREEMENT_NOT_FUTURE");
                db.cas(db.update("UPDATE li_p0_agreement SET cancelled_at=?,status='CANCELLED',termination_reason=?,version=version+1,updated_at=? WHERE id=? AND version=?",now,reason,now,id,f.version()));
            }else if(action.equals("terminate")){
                var window=CooperationWindows.agreement(row);Stage1Exception.require(window!=null&&window.contains(now),409,"AGREEMENT_NOT_EFFECTIVE");
                db.cas(db.update("UPDATE li_p0_agreement SET terminated_at=?,status='TERMINATED',termination_reason=?,version=version+1,updated_at=? WHERE id=? AND version=?",now,reason,now,id,f.version()));
            }else throw new Stage1Exception(404,"RESOURCE_NOT_FOUND");
            mutations.invalidated(store,id,"STORE_ELIGIBILITY",f.version()+1);return view(raw(id));
        });
    }
    public Map<String,Object> raw(String id){return db.required("SELECT * FROM li_p0_agreement WHERE id=?",id);}
    public boolean evidenceAvailable(Map<String,Object> row){return files.boundEvidenceAvailable(row.get("file_refs"),"AGREEMENT",id(row,"store_id"));}
    public Map<String,Object> effective(String store,Instant at){
        var matches=db.rows("SELECT * FROM li_p0_agreement WHERE store_id=? AND business_type=? AND confirmed_at IS NOT NULL",store,BUSINESS).stream().filter(r->{var w=CooperationWindows.agreement(r);return w!=null&&w.contains(at);}).toList();
        Stage1Exception.require(matches.size()<=1,409,"AGREEMENT_WINDOW_CONFLICT");return matches.isEmpty()?null:matches.getFirst();
    }
    public Object detail(String id){var actor=context.current();var row=raw(id);if(actor.domain()==SessionDomain.STORE)Stage1Exception.require(actor.store().equals(id(row,"store_id"))&&row.get("confirmed_at")!=null,404,"RESOURCE_NOT_FOUND");else context.require(SessionDomain.MANAGER);return view(row);}
    public Object versions(String id){context.require(SessionDomain.MANAGER);var row=raw(id);return db.rows("SELECT * FROM li_p0_agreement WHERE agreement_no=? ORDER BY revision DESC",row.get("agreement_no")).stream().map(this::view).toList();}
    public Object list(String store,int page,int size){context.require(SessionDomain.MANAGER);page(page,size);return map("total",db.count("SELECT COUNT(*) FROM li_p0_agreement WHERE (? IS NULL OR store_id=?)",store,store),"records",db.rows("SELECT * FROM li_p0_agreement WHERE (? IS NULL OR store_id=?) ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",store,store,size,(page-1)*size).stream().map(this::view).toList());}
    public Object storeList(String store){return db.rows("SELECT * FROM li_p0_agreement WHERE store_id=? AND confirmed_at IS NOT NULL ORDER BY created_at DESC,id DESC",store).stream().map(this::view).toList();}
    public Object view(Map<String,Object> row){var window=CooperationWindows.agreement(row);return map("id",id(row,"id"),"storeId",id(row,"store_id"),"number",row.get("agreement_no"),"revision",row.get("revision"),"version",row.get("version"),"businessType",BUSINESS,"status",CooperationWindows.state(row,true,clock.instant()),"payload",object(row.get("terms")),"actualFrom",window==null?null:window.from(),"actualTo",window==null?null:window.to(),"confirmedAt",instant(row.get("confirmed_at")),"terminatedAt",instant(row.get("terminated_at")),"replacedAt",instant(row.get("replaced_at")),"reason",row.get("termination_reason"),"predecessorId",id(row,"predecessor_id"));}
    public static void page(int page,int size){Stage1Exception.require(page>=1&&page<=10000&&size>=1&&size<=100,422,"PAGE_INVALID");}
}
