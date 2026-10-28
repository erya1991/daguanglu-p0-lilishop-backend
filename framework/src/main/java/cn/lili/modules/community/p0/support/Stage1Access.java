package cn.lili.modules.community.p0.support;

import cn.lili.common.security.AuthUser;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.session.*;
import cn.lili.common.security.session.http.SessionAccessExtension;
import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.member.merchant.*;
import cn.lili.modules.member.mapper.MerchantGrantMapper;
import cn.lili.modules.store.entity.dos.Store;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Current native roles and exact approved evidence, without isSuper or cached grant shortcuts. */
@Component
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
public class Stage1Access implements SessionAccessExtension, MerchantLifecyclePolicy, MerchantOwnershipProof {
    private final Stage1Db db;
    private final Stage1Settings settings;
    private final MerchantGrantMapper merchantGrants;
    private static final Set<String> OWNER=Set.of("summary.read","profile.read","profile.write","staff.manage",
            "cooperation.read","goods.read","goods.write","goods.submit","goods.publish","stock.read","stock.write",
            "cost.read","cost.write","point.read","point.write","fulfillment.read","fulfillment.configure",
            "file.read","file.upload","operation.read","invitation.accept","region.read");
    public Stage1Access(Stage1Db db,Stage1Settings settings,MerchantGrantMapper merchantGrants){this.db=db;this.settings=settings;this.merchantGrants=merchantGrants;}
    @Override public boolean ownsManager(String id){
        return managerRoles(id).stream().anyMatch(settings.getManagerRoleIds()::contains);
    }
    private Set<String> managerRoles(String id){
        var result=new HashSet<String>();
        db.rows("SELECT r.id FROM li_role r JOIN li_user_role ur ON ur.role_id=r.id WHERE ur.user_id=? AND r.delete_flag=0 UNION SELECT r.id FROM li_role r JOIN li_department_role dr ON dr.role_id=r.id JOIN li_department d ON d.id=dr.department_id JOIN li_admin_user a ON a.department_id=d.id WHERE a.id=? AND r.delete_flag=0 AND dr.delete_flag=0 AND d.delete_flag=0",id,id).forEach(r->result.add(id(r,"id")));
        return Set.copyOf(result);
    }
    @Override public void checkManager(String id){
        var admin=db.one("SELECT status,delete_flag,is_super FROM li_admin_user WHERE id=?",id);
        deny(admin!=null && flag(admin,"status")&&!flag(admin,"delete_flag")&&admin.get("is_super")!=null);
        var roles=managerRoles(id);
        deny(!roles.isEmpty()&&settings.getManagerRoleIds().containsAll(roles));
    }
    public Set<String> managerPermissions(String actor){
        checkManager(actor);
        Set<String> roles=managerRoles(actor), permissions=new TreeSet<>();
        for(String role:roles) for(var row:db.rows("SELECT m.permission,rm.is_super FROM li_role_menu rm JOIN li_menu m ON m.id=rm.menu_id WHERE rm.role_id=? AND rm.delete_flag=0 AND m.delete_flag=0",role)) {
            String permission=id(row,"permission");
            deny(permission!=null&&permission.startsWith("s1.")&&row.get("is_super")!=null);
            String action=permission.substring(3);
            if(readAction(action)||flag(row,"is_super"))permissions.add(action);
        }
        return Set.copyOf(permissions);
    }
    public Set<String> storePermissions(AuthUser user){
        var clerk=db.required("SELECT shopkeeper,department_id,status,delete_flag FROM li_clerk WHERE id=? AND member_id=? AND store_id=?",user.getClerkId(),user.getId(),user.getStoreId());
        deny(flag(clerk,"status")&&!flag(clerk,"delete_flag"));
        var store=db.required("SELECT member_id FROM li_store WHERE id=? AND delete_flag=0",user.getStoreId());
        if(flag(clerk,"shopkeeper")){deny(user.getId().equals(id(store,"member_id")));return OWNER;}
        Set<String> result=new TreeSet<>(Set.of("summary.read","operation.read","region.read","invitation.accept"));
        var grants=merchantGrants.current(user.getClerkId(),user.getStoreId(),id(clerk,"department_id"));
        deny(grants!=null&&!grants.isEmpty());
        for(var grant:grants) {
            String permission=grant.getPermission();
            if(permission!=null&&permission.startsWith("s1.")) {
                String action=permission.substring(3);
                if(OWNER.contains(action)&&!action.equals("staff.manage")&&(readAction(action)||Boolean.TRUE.equals(grant.getWriteGrant()))) result.add(action);
            }
        }
        return Set.copyOf(result);
    }
    private static boolean readAction(String action){return action.endsWith(".read")||action.equals("fulfillment.inspect");}
    @Override public boolean checkRoute(AuthUser user,String method,String path,boolean verified){
        if(Stage1Routes.path(path)) {
            deny(verified);
            SessionDomain domain=user==null?SessionDomain.APPLICANT:switch(user.getRole()){case MANAGER->SessionDomain.MANAGER;case STORE->SessionDomain.STORE;case MEMBER->SessionDomain.RESIDENT;default->throw new SessionException(SessionException.Code.DENIED);};
            var route=Stage1Routes.find(domain,method,path);deny(route!=null);
            if(domain==SessionDomain.MANAGER) deny(managerPermissions(user.getId()).contains(route.action()) || Set.of("operation.read","file.read","file.upload","region.read").contains(route.action())&& !managerPermissions(user.getId()).isEmpty());
            if(domain==SessionDomain.STORE){deny(storePermissions(user).contains(route.action()));requireStoreRoute(user.getStoreId(),method,path,route.action());}
            return true;
        }
        if(user!=null&&user.getRole()==UserEnums.MANAGER&&ownsManager(user.getId())) {
            deny(verified);
            deny(method.equals("GET")&&Set.of("/manager/passport/user/info","/manager/permission/menu/memberMenu").contains(path)
                    || method.equals("POST")&&path.equals("/manager/passport/user/logout"));
            checkManager(user.getId());return true;
        }
        if(user!=null&&user.getRole()==UserEnums.STORE&&managed(user.getStoreId())) {
            // Every P0 native equivalent is closed until explicitly routed through a guarded application command.
            deny(verified);
            deny(method.equals("GET")&&Set.of("/store/member/user","/store/member/user/info").contains(path)
                    || method.equals("POST")&&path.equals("/store/passport/login/logout"));
            return true;
        }
        return false;
    }
    private void requireStoreRoute(String store,String method,String path,String action){
        var current=db.required("SELECT s.store_disable,c.admission_state FROM li_store s JOIN li_p0_store_control c ON c.store_id=s.id WHERE s.id=?",store);
        if("OPEN".equals(id(current,"store_disable")))return;
        deny("CLOSED".equals(id(current,"store_disable")));
        boolean corrective=method.equals("GET")||Set.of("profile.write","file.upload","operation.read").contains(action)
                ||method.equals("PUT")&&(Set.of("stock.write","cost.write","goods.write","point.write","fulfillment.configure").contains(action)||path.matches("/store/p0/s1/staff/[0-9]+/roles"))
                || method.equals("POST")&&(path.endsWith("/unlist")||path.endsWith("/disable")||path.endsWith("/revoke")||path.endsWith("/reject"));
        deny(corrective);
    }
    public boolean managed(String store){return store!=null&&db.count("SELECT COUNT(*) FROM li_p0_store_control WHERE store_id=?",store)==1;}
    public Object contextView(AuthUser user){var row=db.required("SELECT c.shopkeeper,k.version AS authorization_version,s.store_disable,s.store_name,p.closed_reason,p.version AS store_version FROM li_clerk c JOIN li_p0_clerk_control k ON k.clerk_id=c.id JOIN li_store s ON s.id=c.store_id JOIN li_p0_store_control p ON p.store_id=s.id WHERE c.id=? AND c.member_id=? AND c.store_id=?",user.getClerkId(),user.getId(),user.getStoreId());var permissions=new TreeSet<>(storePermissions(user));return map("identityDomain","STORE","memberId",user.getId(),"storeId",user.getStoreId(),"clerkId",user.getClerkId(),"ownerFlag",flag(row,"shopkeeper"),"permissions",permissions,"authVersion",hash(map("clerkVersion",row.get("authorization_version"),"storeVersion",row.get("store_version"),"permissions",permissions)),"storeOperationalState",row.get("store_disable"),"storeName",row.get("store_name"),"closedReason",row.get("closed_reason"));}
    @Override public void checkRecord(SessionRecord record){
        checkIssuedAt(record.identity(),record.issuedAt());
    }
    public void checkIssuedAt(SessionIdentity identity,long createdAt){
        if(identity.domain()!=SessionDomain.STORE)return;
        var cutoffs=db.rows("SELECT c.closed_at,k.session_cutoff FROM li_clerk n JOIN li_p0_store_control c ON c.store_id=n.store_id LEFT JOIN li_p0_clerk_control k ON k.clerk_id=n.id WHERE n.member_id=?",identity.subject());
        for(var row:cutoffs)for(String key:List.of("closed_at","session_cutoff")) {
            Instant cutoff=instant(row.get(key));
            if(cutoff!=null)deny(createdAt>cutoff.toEpochMilli());
        }
    }
    @Override public boolean pendingApplicant(SessionIdentity identity,Member member,List<Store> owned){
        if(owned.size()!=1||owned.getFirst()==null||!Set.of("APPLY","APPLYING","REFUSED").contains(owned.getFirst().getStoreDisable()))return false;
        return db.count("SELECT COUNT(*) FROM li_p0_application a JOIN li_p0_store_control c ON c.store_id=a.store_id WHERE a.applicant_id=? AND a.store_id=? AND a.kind='ADMISSION' AND a.binding_id=? AND a.binding_version=? AND a.identity_application=? AND a.status IN ('DRAFT','SUBMITTED','REJECTED') AND c.admission_state='PENDING' AND c.owner_member_id=a.applicant_id",member.getId(),owned.getFirst().getId(),identity.bindingId(),identity.bindingVersion(),identity.application())==1;
    }
    @Override public boolean restrictedStore(Store store){
        return "CLOSED".equals(store.getStoreDisable())&&db.count("SELECT COUNT(*) FROM li_p0_store_control WHERE store_id=? AND admission_state='APPROVED' AND closed_at IS NOT NULL",store.getId())==1;
    }
    @Override public Verified current(SessionIdentity identity){
        var row=db.one("SELECT e.audit_reference FROM li_p0_grant_evidence e JOIN li_clerk c ON c.id=e.clerk_id AND c.member_id=e.member_id AND c.store_id=e.store_id JOIN li_p0_store_control s ON s.store_id=e.store_id JOIN li_p0_audit proof ON proof.id=e.audit_reference AND proof.store_id=e.store_id WHERE e.application=? AND e.binding_id=? AND e.binding_version=? AND e.member_id=? AND e.active=1 AND c.status=1 AND c.delete_flag=0 AND s.admission_state='APPROVED' AND ((e.source_type='ADMISSION' AND proof.action='application.approve' AND proof.object_id=e.source_id AND c.shopkeeper=1 AND EXISTS(SELECT 1 FROM li_p0_application a JOIN li_p0_application_revision v ON v.application_id=a.id AND v.revision=e.source_revision WHERE a.id=e.source_id AND a.kind='ADMISSION' AND a.status='APPROVED' AND v.status='APPROVED' AND a.store_id=e.store_id AND a.applicant_id=e.member_id AND a.binding_id=e.binding_id AND a.binding_version=e.binding_version AND a.identity_application=e.application)) OR (e.source_type='INVITATION' AND proof.action='invitation.confirm' AND proof.object_id=e.clerk_id AND c.shopkeeper=0 AND EXISTS(SELECT 1 FROM li_p0_staff_invitation i WHERE i.id=e.source_id AND i.status='ACTIVE' AND i.store_id=e.store_id AND i.accepted_member_id=e.member_id AND i.binding_id=e.binding_id AND i.binding_version=e.binding_version AND i.identity_application=e.application)))",identity.application(),identity.bindingId(),identity.bindingVersion(),identity.subject());
        if(row==null){
            // A revoke may commit between the resolver's Clerk read and this proof read.
            // Known disabled/revoked grants are denials; an unavailable historical proof remains pending.
            boolean revoked=db.count("SELECT COUNT(*) FROM li_p0_grant_evidence e JOIN li_clerk c ON c.id=e.clerk_id AND c.member_id=e.member_id JOIN li_p0_clerk_control k ON k.clerk_id=c.id WHERE e.application=? AND e.binding_id=? AND e.binding_version=? AND e.member_id=? AND (k.revoked=1 OR c.status=0)",identity.application(),identity.bindingId(),identity.bindingVersion(),identity.subject())>0;
            throw new MerchantAccessException(revoked?MerchantAccessException.Code.DENIED:MerchantAccessException.Code.PROOF_SOURCE_PENDING);
        }
        return new Verified(identity.application(),identity.bindingId(),identity.bindingVersion(),identity.subject(),id(row,"audit_reference"),Kind.APPROVED_EVIDENCE);
    }
    @Override public boolean revokedApplicant(SessionIdentity identity,Member member,cn.lili.modules.member.entity.dos.Clerk clerk){
        if(!Boolean.FALSE.equals(clerk.getStatus())||!Boolean.FALSE.equals(clerk.getShopkeeper())||!Boolean.FALSE.equals(clerk.getDeleteFlag())||!member.getId().equals(clerk.getMemberId())||Boolean.TRUE.equals(member.getHaveStore())||member.getStoreId()!=null)return false;
        return db.count("SELECT COUNT(*) FROM li_p0_clerk_control c JOIN li_p0_grant_evidence e ON e.clerk_id=c.clerk_id WHERE c.clerk_id=? AND c.revoked=1 AND e.active=0 AND e.binding_id=? AND e.binding_version=? AND e.application=? AND e.member_id=?",clerk.getId(),identity.bindingId(),identity.bindingVersion(),identity.application(),member.getId())>=1;
    }
    private static void deny(boolean okay){if(!okay)throw new SessionException(SessionException.Code.DENIED);}
}
