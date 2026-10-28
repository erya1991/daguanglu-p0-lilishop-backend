package cn.lili.modules.community.p0.admission;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.support.*;
import cn.lili.modules.community.p0.staff.StaffTemplates;
import com.google.gson.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
public class AdmissionService {
    private final Stage1Db db;private final Stage1Context context;private final Stage1Mutations mutations;private final AdmissionRules rules;private final Stage1Regions regions;private final StaffTemplates templates;private final Clock clock;
    public AdmissionService(Stage1Db db,Stage1Context context,Stage1Mutations mutations,AdmissionRules rules,Stage1Regions regions,StaffTemplates templates,@Qualifier("stage1Clock") Clock clock){this.db=db;this.context=context;this.mutations=mutations;this.rules=rules;this.regions=regions;this.templates=templates;this.clock=clock;}
    public Object own(){var actor=context.require(SessionDomain.APPLICANT);var row=db.one("SELECT * FROM li_p0_application WHERE applicant_id=? AND kind='ADMISSION'",actor.id());return row==null?null:view(row,true);}
    public Object create(Object body){
        var actor=context.require(SessionDomain.APPLICANT);var f=new Fields(body).only("requestId","payload");var payload=rules.validate(f.child("payload",true),false,actor.id(),null);
        return mutations.run("application.create",body,null,()->{
            Stage1Exception.require(db.count("SELECT COUNT(*) FROM li_clerk WHERE member_id=?",actor.id())==0,409,"EXISTING_STAFF_RELATION");
            Stage1Exception.require(db.count("SELECT COUNT(*) FROM li_p0_application WHERE applicant_id=? AND kind='ADMISSION'",actor.id())==0,409,"APPLICATION_EXISTS");
            String id=db.id();insert(id,actor,"ADMISSION",null,payload);
            mutations.audit("application.create",null,id,f.requestId(),null,0L,null);return view(application(id),true);
        });
    }
    public Object draft(String id,Object body){
        var actor=context.require(SessionDomain.APPLICANT);var f=new Fields(body).only("requestId","expectedVersion","payload");var before=owned(id,actor.id());String store=id(before,"store_id");
        return mutations.run("application.draft:"+id,body,store,()->{
            var row=owned(id,actor.id());editable(row,f.version());
            var payload=rules.validate(f.child("payload",true),false,actor.id(),store);
            db.cas(db.update("UPDATE li_p0_application SET draft_payload=?,status='DRAFT',version=version+1,updated_at=?,binding_id=?,binding_version=?,identity_application=? WHERE id=? AND version=?",write(payload),clock.instant(),actor.identity().bindingId(),actor.identity().bindingVersion(),actor.identity().application(),id,f.version()));
            mutations.audit("application.draft",store,id,f.requestId(),f.version(),f.version()+1,null);return view(application(id),true);
        });
    }
    public Object submit(String id,Object body){
        var actor=context.require(SessionDomain.APPLICANT);var f=new Fields(body).only("requestId","expectedVersion");var initial=owned(id,actor.id());String existingStore=id(initial,"store_id");
        return mutations.run("application.submit:"+id,body,existingStore,()->{
            var row=owned(id,actor.id());editable(row,f.version());
            JsonObject payload=rules.validate(row.get("draft_payload"),true,actor.id(),existingStore);
            String store=existingStore;
            if(store==null){store=db.id();createPendingStore(store,actor,payload);db.update("UPDATE li_p0_application SET store_id=? WHERE id=?",store,id);}
            else {reserveIdentity(store,actor.id(),payload);projectStore(store,payload);}
            db.update("UPDATE li_store SET store_disable='APPLYING' WHERE id=?",store);
            rules.bindSubmitted(payload,store,actor.id(),id);
            freeze(row,payload,f.requestId());
            mutations.audit("application.submit",store,id,f.requestId(),f.version(),f.version()+1,null);
            return view(application(id),true);
        });
    }
    public Object queue(String kind,String status,int page,int size){
        context.require(SessionDomain.MANAGER);page(page,size);
        if(kind!=null&&!Set.of("ADMISSION","PROFILE_CHANGE","QUALIFICATION_CHANGE").contains(kind))throw Stage1Exception.field("kind","INVALID_CHOICE");
        if(status!=null&&!Set.of("DRAFT","SUBMITTED","APPROVED","REJECTED").contains(status))throw Stage1Exception.field("status","INVALID_CHOICE");
        String where=" WHERE (? IS NULL OR kind=?) AND (? IS NULL OR status=?)";
        return map("total",db.count("SELECT COUNT(*) FROM li_p0_application"+where,kind,kind,status,status),"pageNumber",page,"pageSize",size,"records",db.rows("SELECT * FROM li_p0_application"+where+" ORDER BY updated_at DESC,id DESC LIMIT ? OFFSET ?",kind,kind,status,status,size,(page-1)*size).stream().map(r->view(r,false)).toList());
    }
    public Object detail(String id){context.require(SessionDomain.MANAGER);return view(application(id),true);}
    public Object review(String id,Object body,boolean approve){
        var actor=context.require(SessionDomain.MANAGER);var f=new Fields(body).only("requestId","expectedVersion","revision","reason");
        long revision=f.integer("revision",1,Integer.MAX_VALUE,true);String reason=f.text("reason",2,500,!approve);
        var initial=application(id);String member=id(initial,"applicant_id"),store=id(initial,"store_id");Stage1Exception.require(store!=null,409,"APPLICATION_NOT_SUBMITTED");
        return mutations.run("application."+(approve?"approve:":"reject:")+id,body,store,List.of(member),()->{
            var row=application(id);Stage1Exception.require("SUBMITTED".equals(id(row,"status"))&&number(row,"version")==f.version()&&number(row,"revision")==revision,409,"VERSION_CONFLICT");
            var snapshot=db.required("SELECT payload FROM li_p0_application_revision WHERE application_id=? AND revision=? AND status='SUBMITTED'",id,revision);
            JsonObject payload=object(snapshot.get("payload"));
            String audit=mutations.audit(approve?"application.approve":"application.reject",store,id,f.requestId(),f.version(),f.version()+1,reason);
            if(approve){
                if(!"ADMISSION".equals(id(row,"kind")))Stage1Exception.require(row.get("base_store_version")!=null&&number(row,"base_store_version")==number(db.required("SELECT version FROM li_p0_store_control WHERE store_id=?",store),"version"),409,"REVIEW_SOURCE_CHANGED");
                if("QUALIFICATION_CHANGE".equals(id(row,"kind")))validateQualificationPayload(payload,true,member,store);
                else payload=rules.validate(payload,true,member,store);
                if("ADMISSION".equals(id(row,"kind")))approveOwner(row,payload,audit);
                else if("PROFILE_CHANGE".equals(id(row,"kind"))){
                    var control=db.required("SELECT legal_code_normalized FROM li_p0_store_control WHERE store_id=?",store);
                    Stage1Exception.require(payload.get("legalCode").getAsString().equals(id(control,"legal_code_normalized")),422,"LEGAL_ENTITY_CHANGE_NOT_ALLOWED");
                    projectStore(store,payload);
                    db.update("UPDATE li_p0_store_control SET category_ids=?,version=version+1 WHERE store_id=?",write(payload.getAsJsonArray("categoryIds")),store);
                }
                rules.projectQualifications(payload,id,store,member);
                if("QUALIFICATION_CHANGE".equals(id(row,"kind")))db.update("UPDATE li_p0_store_control SET version=version+1 WHERE store_id=?",store);
                mutations.invalidated(store,store,"STORE_ELIGIBILITY",f.version()+1);
            }else if("ADMISSION".equals(id(row,"kind")))db.update("UPDATE li_store SET store_disable='REFUSED' WHERE id=?",store);
            String state=approve?"APPROVED":"REJECTED";
            db.cas(db.update("UPDATE li_p0_application SET status=?,version=version+1,active_key=CASE WHEN ?='APPROVED' THEN NULL ELSE active_key END,reviewed_at=?,updated_at=? WHERE id=? AND version=?",state,state,clock.instant(),clock.instant(),id,f.version()));
            db.update("UPDATE li_p0_application_revision SET status=?,reason=?,reviewer_id=?,reviewed_at=? WHERE application_id=? AND revision=?",state,reason,actor.id(),clock.instant(),id,revision);
            return view(application(id),true);
        });
    }
    private void approveOwner(Map<String,Object> row,JsonObject payload,String audit){
        String store=id(row,"store_id"),member=id(row,"applicant_id");
        Stage1Exception.require(db.count("SELECT COUNT(*) FROM li_clerk WHERE member_id=?",member)==0,409,"OWNER_RELATION_CONFLICT");
        var owner=db.required("SELECT have_store,store_id,disabled,delete_flag FROM li_member WHERE id=?",member);
        Stage1Exception.require(!flag(owner,"have_store")&&owner.get("store_id")==null&&flag(owner,"disabled")&&!flag(owner,"delete_flag"),409,"OWNER_RELATION_CONFLICT");
        verifiedBinding(row,member);
        reserveIdentity(store,member,payload);projectStore(store,payload);
        String clerk=db.id();
        db.nativeInsert("li_clerk",map("id",clerk,"create_time",clock.instant(),"delete_flag",false,"member_id",member,"store_id",store,"shopkeeper",true,"is_super",false,"status",true,"role_ids",""));
        db.update("INSERT INTO li_p0_clerk_control(clerk_id,version,revoked) VALUES(?,0,FALSE)",clerk);
        db.update("UPDATE li_member SET have_store=1,store_id=? WHERE id=?",store,member);
        db.update("UPDATE li_store SET store_disable='OPEN',update_time=? WHERE id=?",clock.instant(),store);
        db.update("UPDATE li_p0_store_control SET admission_state='APPROVED',version=version+1 WHERE store_id=?",store);
        db.update("INSERT INTO li_p0_grant_evidence(id,application,binding_id,binding_version,member_id,store_id,clerk_id,source_type,source_id,source_revision,audit_reference,active,created_at,active_binding_key) VALUES(?,?,?,?,?,?,?,'ADMISSION',?,?,?,TRUE,?,?)",db.id(),row.get("identity_application"),row.get("binding_id"),row.get("binding_version"),member,store,clerk,id(row,"id"),number(row,"revision"),audit,clock.instant(),row.get("binding_id")+":"+row.get("binding_version")+":"+store);
        templates.create(store);
        templates.history(clerk,"ADMISSION",audit);
    }
    public void verifiedBinding(Map<String,Object> row,String member){
        Stage1Exception.require(db.count("SELECT COUNT(*) FROM li_wechat_identity WHERE id=? AND app_id=? AND member_id=? AND binding_version=? AND binding_state='ACTIVE'",row.get("binding_id"),row.get("identity_application"),member,row.get("binding_version"))==1,409,"IDENTITY_BINDING_CHANGED");
    }
    private void createPendingStore(String store,Stage1Context.Actor actor,JsonObject payload){
        Stage1Exception.require(db.count("SELECT COUNT(*) FROM li_store WHERE member_id=?",actor.id())==0,409,"OWNER_RELATION_CONFLICT");
        db.nativeInsert("li_store",map("id",store,"create_time",clock.instant(),"delete_flag",false,"member_id",actor.id(),"self_operated",false,"store_disable","APPLY","store_name",payload.get("storeName").getAsString()));
        db.nativeInsert("li_store_detail",map("id",db.id(),"store_id",store));
        db.update("INSERT INTO li_p0_store_control(store_id,admission_state,legal_code_normalized,owner_member_id,category_ids) VALUES(?,'PENDING',?,?,?)",store,payload.get("legalCode").getAsString(),actor.id(),write(payload.getAsJsonArray("categoryIds")));
        projectStore(store,payload);
    }
    private void reserveIdentity(String store,String member,JsonObject payload){
        var control=db.required("SELECT owner_member_id FROM li_p0_store_control WHERE store_id=?",store);
        Stage1Exception.require(member.equals(id(control,"owner_member_id")),409,"OWNER_RELATION_CONFLICT");
        db.update("UPDATE li_p0_store_control SET legal_code_normalized=?,category_ids=? WHERE store_id=?",payload.get("legalCode").getAsString(),write(payload.getAsJsonArray("categoryIds")),store);
    }
    private void projectStore(String store,JsonObject payload){
        var p=new Fields(payload);var region=regions.address(p,true);
        String idPath=String.join(",",id(region,"province_id"),id(region,"city_id"),id(region,"district_id")),namePath=String.join(",",id(region,"province_name"),id(region,"city_name"),id(region,"district_name"));
        db.update("UPDATE li_store SET store_name=?,store_desc=?,store_address_detail=?,store_address_id_path=?,store_address_path=?,update_time=? WHERE id=?",p.text("storeName",2,30,true),p.text("description",0,500,true),p.text("address",5,200,true),idPath,namePath,clock.instant(),store);
        db.update("UPDATE li_store_detail SET store_name=?,company_name=?,legal_name=?,license_num=?,link_name=?,link_phone=?,company_address=?,company_address_id_path=?,company_address_path=?,goods_management_category=? WHERE store_id=?",p.text("storeName",2,30,true),p.text("legalName",2,100,true),p.text("legalPerson",2,50,true),p.text("legalCode",18,18,true),p.text("contactName",2,30,true),p.text("phone",7,20,true),p.text("address",5,200,true),idPath,namePath,write(p.ids("categoryIds",1,100,true)),store);
        db.update("UPDATE li_p0_store_control SET profile_extra=? WHERE store_id=?",write(map("subjectType",p.text("subjectType",1,64,true),"provinceCode",p.text("provinceCode",6,6,true),"cityCode",p.text("cityCode",6,6,true),"districtCode",p.text("districtCode",6,6,true),"businessHours",p.text("businessHours",0,200,false))),store);
    }
    private void insert(String id,Stage1Context.Actor actor,String kind,String store,JsonObject payload){
        db.update("INSERT INTO li_p0_application(id,applicant_id,store_id,kind,status,active_key,draft_payload,created_at,updated_at,identity_application,binding_id,binding_version) VALUES(?,?,?,?,'DRAFT',?,?,?,?,?,?,?)",id,actor.id(),store,kind,kind+":"+(store==null?actor.id():store),write(payload),clock.instant(),clock.instant(),actor.identity().application(),actor.identity().bindingId(),actor.identity().bindingVersion());
    }
    private void freeze(Map<String,Object> row,JsonObject payload,String request){
        String id=id(row,"id");long revision=number(row,"revision")+1;
        db.update("INSERT INTO li_p0_application_revision(application_id,revision,payload,content_hash,status,created_at) VALUES(?,?,?,?,'SUBMITTED',?)",id,revision,write(payload),hash(payload),clock.instant());
        db.cas(db.update("UPDATE li_p0_application SET draft_payload=?,status='SUBMITTED',revision=?,version=version+1,submitted_at=?,updated_at=? WHERE id=? AND version=?",write(payload),revision,clock.instant(),clock.instant(),id,number(row,"version")));
        if(row.get("store_id")!=null)db.update("UPDATE li_p0_application SET base_store_version=(SELECT version FROM li_p0_store_control WHERE store_id=?) WHERE id=?",row.get("store_id"),id);
    }
    public Object profile(){var actor=context.require(SessionDomain.STORE);return profile(actor.store());}
    public Object profile(String store){
        var row=db.required("SELECT s.*,d.company_name,d.legal_name,d.license_num,d.link_name,d.link_phone,c.version,c.admission_state,c.closed_reason,c.category_ids,c.profile_extra FROM li_store s JOIN li_store_detail d ON d.store_id=s.id JOIN li_p0_store_control c ON c.store_id=s.id WHERE s.id=?",store);
        var extra=row.get("profile_extra")==null?new JsonObject():object(row.get("profile_extra"));
        return map("id",store,"version",number(row,"version"),"state",row.get("store_disable"),"admissionState",row.get("admission_state"),"closedReason",row.get("closed_reason"),"storeName",row.get("store_name"),"description",row.get("store_desc"),"legalName",row.get("company_name"),"legalPerson",row.get("legal_name"),"legalCode",row.get("license_num"),"contactName",row.get("link_name"),"phone",row.get("link_phone"),"address",row.get("store_address_detail"),"categoryIds",array(row.get("category_ids")),"extra",extra,"qualifications",qualifications(store));
    }
    public Object ordinaryProfile(Object body){
        var actor=context.require(SessionDomain.STORE);var f=new Fields(body).only("requestId","expectedVersion","contactName","phone","description","businessHours");
        String name=f.text("contactName",2,30,true),phone=f.text("phone",7,20,true),description=f.text("description",0,500,true),hours=f.text("businessHours",0,200,true);Stage1Rules.phone(phone);
        return mutations.run("profile.update",body,actor.store(),()->{
            var row=db.required("SELECT version,profile_extra FROM li_p0_store_control WHERE store_id=?",actor.store());Stage1Exception.require(number(row,"version")==f.version(),409,"VERSION_CONFLICT");
            var extra=object(row.get("profile_extra"));extra.addProperty("businessHours",hours);
            db.update("UPDATE li_store_detail SET link_name=?,link_phone=? WHERE store_id=?",name,phone,actor.store());
            db.update("UPDATE li_store SET store_desc=?,update_time=? WHERE id=?",description,clock.instant(),actor.store());
            db.cas(db.update("UPDATE li_p0_store_control SET version=version+1,profile_extra=? WHERE store_id=? AND version=?",write(extra),actor.store(),f.version()));
            mutations.audit("profile.update",actor.store(),actor.store(),f.requestId(),f.version(),f.version()+1,null);return profile(actor.store());
        });
    }
    public Object currentChange(){var actor=context.require(SessionDomain.STORE);var row=db.one("SELECT * FROM li_p0_application WHERE store_id=? AND kind='PROFILE_CHANGE' AND active_key IS NOT NULL",actor.store());return row==null?null:view(row,true);}
    public Object submitChange(Object body){
        var actor=context.require(SessionDomain.STORE);var f=new Fields(body).only("requestId","expectedVersion","payload");
        return mutations.run("profile-change.submit",body,actor.store(),()->{
            var control=db.required("SELECT version,legal_code_normalized FROM li_p0_store_control WHERE store_id=?",actor.store());Stage1Exception.require(number(control,"version")==f.version(),409,"VERSION_CONFLICT");
            var payload=rules.validate(f.child("payload",true),true,actor.id(),actor.store());
            Stage1Exception.require(payload.get("legalCode").getAsString().equals(id(control,"legal_code_normalized")),422,"LEGAL_ENTITY_CHANGE_NOT_ALLOWED");
            var row=db.one("SELECT * FROM li_p0_application WHERE store_id=? AND kind='PROFILE_CHANGE' AND active_key IS NOT NULL",actor.store());
            if(row==null){String id=db.id();insert(id,actor,"PROFILE_CHANGE",actor.store(),payload);row=application(id);}else Stage1Exception.require(Set.of("DRAFT","REJECTED").contains(id(row,"status")),409,"CHANGE_ALREADY_PENDING");
            rules.bindSubmitted(payload,actor.store(),actor.id(),id(row,"id"));
            freeze(row,payload,f.requestId());
            db.cas(db.update("UPDATE li_p0_store_control SET version=version+1 WHERE store_id=? AND version=?",actor.store(),f.version()));
            db.update("UPDATE li_p0_application SET base_store_version=? WHERE id=?",f.version()+1,id(row,"id"));
            mutations.audit("profile-change.submit",actor.store(),id(row,"id"),f.requestId(),f.version(),f.version()+1,null);return view(application(id(row,"id")),true);
        });
    }
    public Object qualifications(String store){return map("versions",db.rows("SELECT * FROM li_p0_qualification WHERE store_id=? ORDER BY qualification_key,revision DESC",store).stream().map(this::qualificationView).toList(),"drafts",db.rows("SELECT * FROM li_p0_application WHERE store_id=? AND kind='QUALIFICATION_CHANGE' AND status<>'APPROVED' ORDER BY updated_at DESC",store).stream().map(r->view(r,true)).toList());}
    public Object qualificationDetail(String id){
        var actor=context.require(SessionDomain.STORE);var draft=db.one("SELECT * FROM li_p0_application WHERE id=? AND store_id=? AND kind='QUALIFICATION_CHANGE'",id,actor.store());
        return draft!=null?view(draft,true):qualificationView(db.required("SELECT * FROM li_p0_qualification WHERE id=? AND store_id=?",id,actor.store()));
    }
    private Object qualificationView(Map<String,Object> r){
        var now=clock.instant();var from=instant(r.get("valid_from"));var until=instant(r.get("valid_to"));var replaced=instant(r.get("superseded_at"));
        String status=r.get("revoked_at")!=null?"REVOKED":replaced!=null&&!now.isBefore(replaced)?"SUPERSEDED":from!=null&&now.isBefore(from)?"FUTURE":until!=null&&!now.isBefore(until)?"EXPIRED":id(r,"status");
        return map("id",id(r,"id"),"key",r.get("qualification_key"),"revision",r.get("revision"),"version",r.get("version"),"type",r.get("type"),"scope",r.get("scope"),"categoryIds",array(r.get("category_ids")),"status",status,"validFrom",from,"validTo",until,"approvedAt",instant(r.get("approved_at")),"longTerm",flag(r,"long_term"),"warning",QualificationExpiry.warning(until,now),"details",r.get("details")==null?null:object(r.get("details")),"revocationReason",r.get("revocation_reason"));
    }
    private JsonObject validateQualificationPayload(Object value,boolean complete,String actor,String store){
        var input=new Fields(value).only("qualifications");var items=input.list("qualifications",1,1,true);
        var control=db.required("SELECT c.category_ids,d.company_name,d.license_num FROM li_p0_store_control c JOIN li_store_detail d ON d.store_id=c.store_id WHERE c.store_id=?",store);
        var categories=array(control.get("category_ids")).asList().stream().map(JsonElement::getAsString).toList();
        var q=rules.qualification(items.get(0),complete,actor,store,categories);
        if(complete&&q.get("type").getAsString().equals("BUSINESS_LICENSE"))Stage1Exception.require(id(control,"company_name").equals(q.get("holder").getAsString().trim())&&id(control,"license_num").equals(Stage1Rules.legalCode(q.get("number").getAsString())),422,"QUALIFICATION_SUBJECT_MISMATCH");
        return input.json();
    }
    public Object qualificationDraft(String id,Object body){
        var actor=context.require(SessionDomain.STORE);var f=id==null?new Fields(body).only("requestId","payload"):new Fields(body).only("requestId","expectedVersion","payload");
        return mutations.run("qualification.draft"+(id==null?"":":"+id),body,actor.store(),()->{
            var payload=validateQualificationPayload(f.child("payload",true),false,actor.id(),actor.store());
            if(id==null){String next=db.id();insert(next,actor,"QUALIFICATION_CHANGE",actor.store(),payload);mutations.audit("qualification.draft",actor.store(),next,f.requestId(),null,0L,null);return view(application(next),true);}
            var row=db.required("SELECT * FROM li_p0_application WHERE id=? AND store_id=? AND kind='QUALIFICATION_CHANGE'",id,actor.store());editable(row,f.version());
            db.cas(db.update("UPDATE li_p0_application SET draft_payload=?,status='DRAFT',version=version+1,updated_at=? WHERE id=? AND version=?",write(payload),clock.instant(),id,f.version()));
            mutations.audit("qualification.draft",actor.store(),id,f.requestId(),f.version(),f.version()+1,null);return view(application(id),true);
        });
    }
    public Object submitQualification(String id,Object body){
        var actor=context.require(SessionDomain.STORE);var f=new Fields(body).only("requestId","expectedVersion");
        return mutations.run("qualification.submit:"+id,body,actor.store(),()->{
            var row=db.required("SELECT * FROM li_p0_application WHERE id=? AND store_id=? AND kind='QUALIFICATION_CHANGE'",id,actor.store());editable(row,f.version());
            var payload=validateQualificationPayload(row.get("draft_payload"),true,actor.id(),actor.store());rules.bindSubmitted(payload,actor.store(),actor.id(),id);freeze(row,payload,f.requestId());
            mutations.audit("qualification.submit",actor.store(),id,f.requestId(),f.version(),f.version()+1,null);return view(application(id),true);
        });
    }
    public Object revokeQualification(String id,Object body){
        context.require(SessionDomain.MANAGER);var f=new Fields(body).only("requestId","expectedVersion","reason");String reason=f.text("reason",2,500,true),store=id(db.required("SELECT store_id FROM li_p0_qualification WHERE id=?",id),"store_id");
        return mutations.run("qualification.revoke:"+id,body,store,()->{
            db.cas(db.update("UPDATE li_p0_qualification SET revoked_at=?,revocation_reason=?,version=version+1 WHERE id=? AND version=? AND revoked_at IS NULL",clock.instant(),reason,id,f.version()));
            db.update("UPDATE li_p0_store_control SET version=version+1 WHERE store_id=?",store);mutations.audit("qualification.revoke",store,id,f.requestId(),f.version(),f.version()+1,reason);mutations.invalidated(store,id,"STORE_ELIGIBILITY",f.version()+1);
            return qualificationView(db.required("SELECT * FROM li_p0_qualification WHERE id=?",id));
        });
    }
    public Object stores(int page,int size){context.require(SessionDomain.MANAGER);page(page,size);return map("total",db.count("SELECT COUNT(*) FROM li_p0_store_control WHERE admission_state='APPROVED'"),"records",db.rows("SELECT s.id,s.store_name,s.store_disable,s.update_time,c.version FROM li_store s JOIN li_p0_store_control c ON c.store_id=s.id WHERE c.admission_state='APPROVED' ORDER BY s.update_time DESC,s.id DESC LIMIT ? OFFSET ?",size,(page-1)*size).stream().map(r->map("id",id(r,"id"),"storeName",r.get("store_name"),"state",r.get("store_disable"),"version",r.get("version"))).toList());}
    public Object supervise(String store,Object body,boolean open){
        context.require(SessionDomain.MANAGER);var f=new Fields(body).only("requestId","expectedVersion","reason");String reason=f.text("reason",2,500,true);
        return mutations.run(open?"store.reopen":"store.close",body,store,()->{
            var row=db.required("SELECT version,admission_state FROM li_p0_store_control WHERE store_id=?",store);Stage1Exception.require("APPROVED".equals(id(row,"admission_state"))&&number(row,"version")==f.version(),409,"VERSION_CONFLICT");
            db.update("UPDATE li_store SET store_disable=?,update_time=? WHERE id=?",open?"OPEN":"CLOSED",clock.instant(),store);
            db.cas(db.update(open?"UPDATE li_p0_store_control SET version=version+1,closed_reason=NULL WHERE store_id=? AND version=?":"UPDATE li_p0_store_control SET version=version+1,closed_reason=?,closed_at=? WHERE store_id=? AND version=?",open?new Object[]{store,f.version()}:new Object[]{reason,clock.instant(),store,f.version()}));
            if(!open){db.update("UPDATE li_goods SET market_enable='DOWN',update_time=? WHERE store_id=?",clock.instant(),store);db.update("UPDATE li_goods_sku SET market_enable='DOWN',update_time=? WHERE store_id=?",clock.instant(),store);}
            mutations.audit(open?"store.reopen":"store.close",store,store,f.requestId(),f.version(),f.version()+1,reason);mutations.invalidated(store,store,"STORE_ELIGIBILITY",f.version()+1);return profile(store);
        });
    }
    private Map<String,Object> application(String id){return db.required("SELECT * FROM li_p0_application WHERE id=?",id);}
    private Map<String,Object> owned(String id,String actor){return db.required("SELECT * FROM li_p0_application WHERE id=? AND applicant_id=? AND kind='ADMISSION'",id,actor);}
    private void editable(Map<String,Object> row,long version){Stage1Exception.require(Set.of("DRAFT","REJECTED").contains(id(row,"status"))&&number(row,"version")==version,409,"VERSION_CONFLICT");}
    private Object view(Map<String,Object> row,boolean detail){
        var payload=object(row.get("draft_payload"));var result=map("id",id(row,"id"),"storeId",id(row,"store_id"),"kind",row.get("kind"),"status",row.get("status"),"revision",row.get("revision"),"version",row.get("version"),"storeName",payload.get("storeName"),"updatedAt",instant(row.get("updated_at")));
        if(detail){result.put("payload",payload);result.put("history",db.rows("SELECT revision,status,reason,reviewed_at,created_at FROM li_p0_application_revision WHERE application_id=? ORDER BY revision DESC",row.get("id")).stream().map(r->map("revision",r.get("revision"),"status",r.get("status"),"reason",r.get("reason"),"reviewedAt",instant(r.get("reviewed_at")),"submittedAt",instant(r.get("created_at")))).toList());}
        result.put("allowedActions",Set.of("DRAFT","REJECTED").contains(id(row,"status"))?List.of("save","submit"):List.of());return result;
    }
    private static void page(int page,int size){Stage1Exception.require(page>=1&&page<=10000&&size>=1&&size<=100,422,"PAGE_INVALID");}
}
