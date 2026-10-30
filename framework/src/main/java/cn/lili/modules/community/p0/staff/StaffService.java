package cn.lili.modules.community.p0.staff;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.common.security.token.SecretKeyUtil;
import cn.lili.modules.community.p0.admission.AdmissionService;
import cn.lili.modules.community.p0.support.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import javax.crypto.Mac;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service
@cn.lili.modules.community.p0.support.P0BusinessEnabled
public class StaffService {
    private final Stage1Db db;private final Stage1Context context;private final Stage1Mutations mutations;private final StaffTemplates templates;private final AdmissionService admission;private final SecretKeyUtil keys;private final Clock clock;
    public StaffService(Stage1Db db,Stage1Context context,Stage1Mutations mutations,StaffTemplates templates,AdmissionService admission,SecretKeyUtil keys,@Qualifier("stage1Clock") Clock clock){this.db=db;this.context=context;this.mutations=mutations;this.templates=templates;this.admission=admission;this.keys=keys;this.clock=clock;}
    private Stage1Context.Actor owner(){var actor=context.require(SessionDomain.STORE);Stage1Exception.require(db.count("SELECT COUNT(*) FROM li_clerk c JOIN li_store s ON s.id=c.store_id WHERE c.id=? AND c.member_id=? AND c.shopkeeper=1 AND s.member_id=c.member_id AND c.status=1 AND c.delete_flag=0",actor.clerk(),actor.id())==1,403,"OWNER_REQUIRED");return actor;}
    public Object list(){var actor=owner();return db.rows("SELECT c.id,c.member_id,c.status,c.shopkeeper,m.nick_name,k.version,k.revoked FROM li_clerk c JOIN li_member m ON m.id=c.member_id JOIN li_p0_clerk_control k ON k.clerk_id=c.id WHERE c.store_id=? AND c.delete_flag=0 ORDER BY c.create_time DESC,c.id DESC",actor.store()).stream().map(this::person).toList();}
    private Object person(Map<String,Object> r){return map("id",id(r,"id"),"verifiedIdentityRef",id(r,"member_id"),"displayName",r.get("nick_name"),"owner",flag(r,"shopkeeper"),"state",flag(r,"revoked")?"REVOKED":flag(r,"status")?"ACTIVE":"DISABLED","version",r.get("version"),"roleIds",db.rows("SELECT role_id FROM li_clerk_role WHERE clerk_id=? ORDER BY role_id",r.get("id")).stream().map(v->id(v,"role_id")).toList());}
    public Object roles(){return templates.options(owner().store());}
    public Object invitations(){return db.rows("SELECT * FROM li_p0_staff_invitation WHERE store_id=? ORDER BY created_at DESC,id DESC",owner().store()).stream().map(this::invitationView).toList();}
    public Object page(boolean invitations,int page,int size){var actor=owner();cn.lili.modules.community.p0.agreement.AgreementService.page(page,size);if(invitations)return map("total",db.count("SELECT COUNT(*) FROM li_p0_staff_invitation WHERE store_id=?",actor.store()),"records",db.rows("SELECT * FROM li_p0_staff_invitation WHERE store_id=? ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",actor.store(),size,(page-1)*size).stream().map(this::invitationView).toList());return map("total",db.count("SELECT COUNT(*) FROM li_clerk c JOIN li_p0_clerk_control k ON k.clerk_id=c.id WHERE c.store_id=? AND c.delete_flag=0",actor.store()),"records",db.rows("SELECT c.id,c.member_id,c.status,c.shopkeeper,m.nick_name,k.version,k.revoked FROM li_clerk c JOIN li_member m ON m.id=c.member_id JOIN li_p0_clerk_control k ON k.clerk_id=c.id WHERE c.store_id=? AND c.delete_flag=0 ORDER BY c.create_time DESC,c.id DESC LIMIT ? OFFSET ?",actor.store(),size,(page-1)*size).stream().map(this::person).toList());}
    public Object detail(String id){var actor=owner();return map("person",person(db.required("SELECT c.id,c.member_id,c.status,c.shopkeeper,m.nick_name,k.version,k.revoked FROM li_clerk c JOIN li_member m ON m.id=c.member_id JOIN li_p0_clerk_control k ON k.clerk_id=c.id WHERE c.id=? AND c.store_id=? AND c.delete_flag=0",id,actor.store())),"history",db.rows("SELECT version,role_ids,state,source_action,created_at FROM li_p0_staff_grant_history WHERE clerk_id=? AND store_id=? ORDER BY version DESC",id,actor.store()).stream().map(r->map("version",r.get("version"),"roleIds",array(r.get("role_ids")),"state",r.get("state"),"action",r.get("source_action"),"at",instant(r.get("created_at")))).toList());}
    public Object invitationDetail(String id){return invitationView(invitation(id,owner().store()));}
    public Object create(Object body){
        var actor=owner();var f=new Fields(body).only("requestId","roleIds","reason");List<String> roles=f.ids("roleIds",1,9,true);String reason=f.text("reason",2,500,true);
        var issued=new java.util.concurrent.atomic.AtomicReference<String>();
        Object result=mutations.run("invitation.create",body,actor.store(),()->{
            owner();templates.validate(actor.store(),roles);String id=db.id(),token=token(id,actor.store());
            issued.set(token);
            db.update("INSERT INTO li_p0_staff_invitation(id,store_id,creator_id,token_hash,roles,status,expires_at,created_at) VALUES(?,?,?,?,?,'INVITED',?,?)",id,actor.store(),actor.id(),digest(token.getBytes(StandardCharsets.UTF_8)),write(roles),clock.instant().plusSeconds(86400),clock.instant());
            mutations.audit("invitation.create",actor.store(),id,f.requestId(),null,0L,reason);return invitationView(invitation(id,actor.store()));
        });
        var view=object(result);String id=view.get("id").getAsString();var current=invitation(id,actor.store());
        if("INVITED".equals(state(current))&&issued.get()!=null)view.addProperty("invitationToken",issued.get());
        view.addProperty("deliveryStatus",issued.get()==null?"TOKEN_ALREADY_ISSUED":"TOKEN_ISSUED_ONCE");
        return view; // The persistent idempotency result never contains an invitation credential.
    }
    public Object preview(Object body){var actor=merchant();var f=new Fields(body).only("token");var row=byToken(f.text("token",20,100,true));recipient(row,actor);return invitationView(row);}
    public Object respond(Object body,boolean accept){
        var actor=merchant();var f=new Fields(body).only("requestId","expectedVersion","token");String token=f.text("token",20,100,true);var first=byToken(token);String store=id(first,"store_id"),id=id(first,"id");
        var command=map("requestId",f.requestId(),"expectedVersion",f.version(),"invitationId",id,"tokenHash",digest(token.getBytes(StandardCharsets.UTF_8)));
        return mutations.run(accept?"invitation.accept":"invitation.decline",command,store,()->{
            var row=byToken(token);recipient(row,actor);
            if(actor.domain()==SessionDomain.STORE)return map("id",id,"status","ALREADY_JOINED");
            Stage1Exception.require("INVITED".equals(state(row))&&number(row,"version")==f.version(),409,"INVITATION_NOT_ACTIVE");
            db.cas(db.update("UPDATE li_p0_staff_invitation SET status=?,accepted_member_id=?,accepted_at=?,confirm_deadline=?,identity_application=?,binding_id=?,binding_version=?,version=version+1 WHERE id=? AND version=?",accept?"WAIT_OWNER_CONFIRM":"REJECTED",actor.id(),clock.instant(),accept?clock.instant().plusSeconds(86400):null,actor.identity().application(),actor.identity().bindingId(),actor.identity().bindingVersion(),id,f.version()));
            mutations.audit(accept?"invitation.accept":"invitation.decline",store,id,f.requestId(),f.version(),f.version()+1,null);return invitationView(invitation(id,store));
        });
    }
    public Object manageInvitation(String id,Object body,String action){
        var actor=owner();var f=new Fields(body).only("requestId","expectedVersion","reason");String reason=f.text("reason",2,500,true);var initial=invitation(id,actor.store());String member=id(initial,"accepted_member_id");
        return mutations.run("invitation."+action+":"+id,body,actor.store(),member==null?List.of():List.of(member),()->{
            owner();var row=invitation(id,actor.store());String current=state(row);Stage1Exception.require(number(row,"version")==f.version(),409,"VERSION_CONFLICT");
            if(action.equals("confirm")){
                Stage1Exception.require("WAIT_OWNER_CONFIRM".equals(current)&&member!=null&&member.equals(id(row,"accepted_member_id")),409,"INVITATION_NOT_CONFIRMABLE");
                admission.verifiedBinding(row,member);Stage1Exception.require(!actor.id().equals(member),409,"OWNER_IMMUTABLE");
                var person=db.required("SELECT disabled,delete_flag,have_store,store_id FROM li_member WHERE id=?",member);Stage1Exception.require(flag(person,"disabled")&&!flag(person,"delete_flag")&&!flag(person,"have_store")&&person.get("store_id")==null,409,"STAFF_RELATION_CONFLICT");
                Stage1Exception.require(db.count("SELECT COUNT(*) FROM li_store WHERE member_id=?",member)==0,409,"STAFF_RELATION_CONFLICT");
                var prior=db.one("SELECT id,store_id,shopkeeper,status FROM li_clerk WHERE member_id=?",member);
                if(prior!=null)Stage1Exception.require(actor.store().equals(id(prior,"store_id"))&&!flag(prior,"shopkeeper"),409,"CROSS_STORE_RELATION");
                List<String> roles=new Fields(map("roleIds",array(row.get("roles")))).ids("roleIds",1,9,true);templates.validate(actor.store(),roles);
                String clerk=prior==null?db.id():id(prior,"id");
                if(prior==null){db.nativeInsert("li_clerk",map("id",clerk,"create_time",clock.instant(),"delete_flag",false,"member_id",member,"store_id",actor.store(),"shopkeeper",false,"is_super",false,"status",true));db.update("INSERT INTO li_p0_clerk_control(clerk_id,version,revoked) VALUES(?,0,FALSE)",clerk);}
                else {db.update("UPDATE li_clerk SET status=1 WHERE id=?",clerk);db.update("UPDATE li_p0_clerk_control SET revoked=FALSE,version=version+1,session_cutoff=? WHERE clerk_id=?",clock.instant(),clerk);}
                templates.assign(clerk,actor.store(),roles);
                String audit=mutations.audit("invitation.confirm",actor.store(),clerk,f.requestId(),f.version(),f.version()+1,reason);
                templates.history(clerk,"INVITATION",audit);
                db.update("INSERT INTO li_p0_grant_evidence(id,application,binding_id,binding_version,member_id,store_id,clerk_id,source_type,source_id,source_revision,audit_reference,active,created_at,active_binding_key) VALUES(?,?,?,?,?,?,?,'INVITATION',?,0,?,TRUE,?,?)",db.id(),row.get("identity_application"),row.get("binding_id"),row.get("binding_version"),member,actor.store(),clerk,id,audit,clock.instant(),row.get("binding_id")+":"+row.get("binding_version")+":"+actor.store());
            }else Stage1Exception.require(Set.of("INVITED","WAIT_OWNER_CONFIRM").contains(current)&&Set.of("revoke","reject").contains(action),409,"INVITATION_NOT_ACTIVE");
            String next=switch(action){case "confirm"->"ACTIVE";case "revoke"->"REVOKED";case "reject"->"REJECTED";default->throw new Stage1Exception(422,"ACTION_INVALID");};
            db.cas(db.update("UPDATE li_p0_staff_invitation SET status=?,version=version+1 WHERE id=? AND version=?",next,id,f.version()));
            if(!action.equals("confirm"))mutations.audit("invitation."+action,actor.store(),id,f.requestId(),f.version(),f.version()+1,reason);
            mutations.invalidated(actor.store(),member,"STAFF_GRANT",f.version()+1);return invitationView(invitation(id,actor.store()));
        });
    }
    public Object change(String id,Object body,String action){
        var actor=owner();var f=new Fields(body).only(action.equals("roles")?new String[]{"requestId","expectedVersion","roleIds","reason"}:new String[]{"requestId","expectedVersion","reason"});
        String reason=f.text("reason",2,500,true);var target=db.required("SELECT member_id FROM li_clerk WHERE id=? AND store_id=?",id,actor.store());
        return mutations.run("staff."+action+":"+id,body,actor.store(),List.of(id(target,"member_id")),()->{
            owner();var row=db.required("SELECT c.*,k.version,k.revoked FROM li_clerk c JOIN li_p0_clerk_control k ON k.clerk_id=c.id WHERE c.id=? AND c.store_id=?",id,actor.store());
            Stage1Exception.require(!flag(row,"shopkeeper")&&!actor.id().equals(id(row,"member_id")),409,"OWNER_IMMUTABLE");
            Stage1Exception.require(number(row,"version")==f.version()&&!flag(row,"revoked"),409,"VERSION_CONFLICT");
            if(action.equals("roles")){
                var requested=f.ids("roleIds",1,9,true);
                if(!"OPEN".equals(id(db.required("SELECT store_disable FROM li_store WHERE id=?",actor.store()),"store_disable"))){var existing=db.rows("SELECT role_id FROM li_clerk_role WHERE clerk_id=?",id).stream().map(r->id(r,"role_id")).toList();Stage1Exception.require(existing.containsAll(requested),403,"STORE_CLOSED_EXPANSION_FORBIDDEN");}
                templates.assign(id,actor.store(),requested);
            }
            else {Stage1Exception.require(Set.of("enable","disable","revoke").contains(action),422,"ACTION_INVALID");db.update("UPDATE li_clerk SET status=?,update_time=? WHERE id=?",action.equals("enable"),clock.instant(),id);}
            db.cas(db.update("UPDATE li_p0_clerk_control SET version=version+1,revoked=?,session_cutoff=? WHERE clerk_id=? AND version=?",action.equals("revoke"),clock.instant(),id,f.version()));
            if(action.equals("revoke"))db.update("UPDATE li_p0_grant_evidence SET active=FALSE,active_binding_key=NULL WHERE clerk_id=?",id);
            String audit=mutations.audit("staff."+action,actor.store(),id,f.requestId(),f.version(),f.version()+1,reason);templates.history(id,"STAFF_"+action.toUpperCase(Locale.ROOT),audit);mutations.invalidated(actor.store(),id,"STAFF_GRANT",f.version()+1);
            return map("id",id,"version",f.version()+1,"status",action.equals("revoke")?"REVOKED":action.equals("disable")?"DISABLED":action.equals("enable")?"ACTIVE":flag(row,"status")?"ACTIVE":"DISABLED");
        });
    }
    private Stage1Context.Actor merchant(){var actor=context.current();Stage1Exception.require(actor.domain()==SessionDomain.APPLICANT||actor.domain()==SessionDomain.STORE,403,"DOMAIN_MISMATCH");return actor;}
    private void recipient(Map<String,Object> row,Stage1Context.Actor actor){
        Stage1Exception.require(!actor.id().equals(id(row,"creator_id")),409,"OWNER_IMMUTABLE");
        if(actor.store()!=null)Stage1Exception.require(actor.store().equals(id(row,"store_id")),409,"CROSS_STORE_RELATION");
        var prior=db.one("SELECT store_id,shopkeeper FROM li_clerk WHERE member_id=?",actor.id());
        if(prior!=null)Stage1Exception.require(!flag(prior,"shopkeeper")&&id(prior,"store_id").equals(id(row,"store_id")),409,"CROSS_STORE_RELATION");
        Stage1Exception.require(Set.of("INVITED","WAIT_OWNER_CONFIRM").contains(state(row)),409,"INVITATION_NOT_ACTIVE");
        if("WAIT_OWNER_CONFIRM".equals(state(row)))Stage1Exception.require(actor.id().equals(id(row,"accepted_member_id")),404,"RESOURCE_NOT_FOUND");
    }
    private Map<String,Object> byToken(String token){if(!token.matches("[1-9][0-9]{0,18}\\.[A-Za-z0-9_-]{43}"))throw new Stage1Exception(404,"RESOURCE_NOT_FOUND");return db.required("SELECT * FROM li_p0_staff_invitation WHERE token_hash=?",digest(token.getBytes(StandardCharsets.UTF_8)));}
    private Map<String,Object> invitation(String id,String store){return db.required("SELECT * FROM li_p0_staff_invitation WHERE id=? AND store_id=?",id,store);}
    private String state(Map<String,Object> row){String state=id(row,"status");if(state.equals("INVITED")&&!instant(row.get("expires_at")).isAfter(clock.instant())||state.equals("WAIT_OWNER_CONFIRM")&&!instant(row.get("confirm_deadline")).isAfter(clock.instant()))return "EXPIRED";return state;}
    private Object invitationView(Map<String,Object> row){
        String member=id(row,"accepted_member_id");var name=member==null?null:db.one("SELECT nick_name FROM li_member WHERE id=?",member);
        return map("id",id(row,"id"),"storeName",db.required("SELECT store_name FROM li_store WHERE id=?",row.get("store_id")).get("store_name"),"status",state(row),"version",row.get("version"),"roleIds",array(row.get("roles")),"expiresAt",instant(row.get("expires_at")),"confirmDeadline",instant(row.get("confirm_deadline")),"acceptedIdentity",member==null?null:map("verifiedIdentityRef",member,"displayName",name==null?null:name.get("nick_name")));
    }
    private String token(String id,String store){byte[] bytes=new byte[32];new java.security.SecureRandom().nextBytes(bytes);try{return id+"."+Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}finally{Arrays.fill(bytes,(byte)0);}}
}
