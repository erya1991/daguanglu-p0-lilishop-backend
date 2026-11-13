package cn.lili.modules.community.p0.member;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.common.security.session.http.SessionApplications;
import cn.lili.modules.community.p0.support.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Limited manager projection of resident identities. Member IDs are never authentication claims. */
@Service
@P0BusinessEnabled
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="p0.uat.enabled",havingValue="true")
public class ResidentManagerService {
    private final Stage1Db db; private final Stage1Context context; private final Stage1Mutations mutations;
    private final SessionApplications applications; private final Clock clock;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private Stage1Access access;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.lili.modules.community.p0.stage3.Stage3Authorization orderAccess;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private cn.lili.modules.community.p0.stage45.Stage45Authorization saleAccess;
    public ResidentManagerService(Stage1Db db,Stage1Context context,Stage1Mutations mutations,
            SessionApplications applications,@Qualifier("stage1Clock") Clock clock){
        this.db=db;this.context=context;this.mutations=mutations;this.applications=applications;this.clock=clock;
    }
    private String residentApp(){return applications.application(SessionDomain.RESIDENT);}
    private static String validId(String id){
        Stage1Exception.require(id!=null&&id.matches("[1-9][0-9]{0,18}"),422,"INVALID_MEMBER_ID");
        try{Long.parseLong(id);}catch(NumberFormatException e){throw new Stage1Exception(422,"INVALID_MEMBER_ID");}return id;
    }
    public Object search(Object input){
        var actor=context.require(SessionDomain.MANAGER);
        var f=new Fields(input).only("memberId","nickname","mobile","status","pageNumber","pageSize");
        String id=f.id("memberId",false),name=f.text("nickname",1,100,false),mobile=f.text("mobile",1,32,false);
        String status=f.choice("status",Set.of("ACTIVE","DISABLED"),false);
        long page=Optional.ofNullable(f.integer("pageNumber",1,100000,false)).orElse(1L);
        long size=Optional.ofNullable(f.integer("pageSize",1,100,false)).orElse(20L);
        if(mobile!=null&&!mobile.matches("[0-9]{6,20}"))throw Stage1Exception.field("mobile","INVALID_FORMAT");
        String app=residentApp();var args=new ArrayList<Object>();args.add(app);
        String where=" FROM li_member m WHERE m.delete_flag=0 AND EXISTS (SELECT 1 FROM li_wechat_identity w WHERE w.member_id=m.id AND w.app_id=? AND w.binding_state IN ('ACTIVE','PENDING'))";
        if(id!=null){where+=" AND m.id=?";args.add(id);}if(name!=null){where+=" AND m.nick_name LIKE ?";args.add("%"+name.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");}
        if(mobile!=null){where+=" AND m.mobile=?";args.add(mobile);}if(status!=null){where+=" AND m.disabled=?";args.add(status.equals("ACTIVE")?1:0);}
        long total=db.count("SELECT COUNT(*)"+where,args.toArray());
        var paged=new ArrayList<>(args);paged.add(size);paged.add((page-1)*size);
        var rows=db.rows("SELECT m.id,m.nick_name,m.face,m.mobile,m.disabled,m.create_time"+where+" ORDER BY m.id DESC LIMIT ? OFFSET ?",paged.toArray());
        var grants=access==null?Set.<String>of():access.managerPermissions(actor.id());
        var result=new ArrayList<Map<String,Object>>();for(var row:rows){
            var item=view(row,app);
            if(grants.contains("store.read")){
                String member=id(row,"id");
                boolean related=db.count("SELECT COUNT(*) FROM li_clerk WHERE member_id=?",member)>0
                        ||db.count("SELECT COUNT(*) FROM li_store WHERE member_id=?",member)>0;
                if(!related&&grants.contains("admission.review"))related=db.count("SELECT COUNT(*) FROM li_p0_application WHERE applicant_id=?",member)>0;
                item.put("merchantAssociation",related?"RELATED":grants.contains("admission.review")?"NONE":"UNVERIFIED");
            }
            result.add(item);
        }
        context.recheck(actor);recheckRelatedGrants(actor,grants);return map("records",result,"total",total,"pageNumber",page,"pageSize",size);
    }
    public Object detail(String id){
        var actor=context.require(SessionDomain.MANAGER);String member=validId(id);
        var result=view(resident(member),residentApp());
        var grants=access==null?Set.<String>of():access.managerPermissions(actor.id());
        if(grants.contains("store.read")){
            var rows=db.rows("SELECT c.id,c.store_id,c.shopkeeper,c.status,c.delete_flag,s.store_name,s.store_disable FROM li_clerk c LEFT JOIN li_store s ON s.id=c.store_id WHERE c.member_id=? ORDER BY c.id",member);
            var relations=new ArrayList<Map<String,Object>>();
            for(var row:rows)relations.add(map("clerkId",id(row,"id"),"storeId",id(row,"store_id"),"storeName",id(row,"store_name"),
                    "role",flag(row,"shopkeeper")?"OWNER":"STAFF","active",flag(row,"status")&&!flag(row,"delete_flag"),"storeStatus",id(row,"store_disable")));
            result.put("merchantRelations",relations);
        }
        if(grants.contains("admission.review")){
            var rows=db.rows("SELECT id,kind,status,store_id FROM li_p0_application WHERE applicant_id=? ORDER BY id DESC LIMIT 20",member);
            var entries=new ArrayList<Map<String,Object>>();for(var row:rows)entries.add(map("id",id(row,"id"),"kind",id(row,"kind"),"status",id(row,"status"),"storeId",id(row,"store_id")));
            result.put("merchantApplications",entries);
        }
        var records=db.rows("SELECT a.action,a.reason,a.created_at,a.actor_id,u.nick_name FROM li_p0_audit a LEFT JOIN li_admin_user u ON u.id=a.actor_id WHERE a.identity_domain='MANAGER' AND a.object_id=? AND a.action IN ('resident.enable','resident.disable') ORDER BY a.created_at DESC,a.id DESC LIMIT 20",member);
        var audit=new ArrayList<Map<String,Object>>();for(var row:records)audit.add(map("action",id(row,"action"),"reason",id(row,"reason"),"operatorId",id(row,"actor_id"),"operatorName",id(row,"nick_name"),"createdAt",row.get("created_at")==null?null:row.get("created_at").toString()));
        result.put("audit",audit);context.recheck(actor);recheckRelatedGrants(actor,grants);return result;
    }
    private void recheckRelatedGrants(Stage1Context.Actor actor,Set<String> original){
        if(access==null)return;
        var current=access.managerPermissions(actor.id());
        for(String permission:List.of("store.read","admission.review"))
            Stage1Exception.require(!original.contains(permission)||current.contains(permission),403,"FORBIDDEN");
    }
    private Map<String,Object> resident(String id){return resident(id,false);}
    private Map<String,Object> resident(String id,boolean locked){
        var row=db.required("SELECT m.id,m.nick_name,m.face,m.mobile,m.disabled,m.create_time FROM li_member m WHERE m.id=? AND m.delete_flag=0 AND EXISTS (SELECT 1 FROM li_wechat_identity w WHERE w.member_id=m.id AND w.app_id=? AND w.binding_state IN ('ACTIVE','PENDING'))"+(locked?" FOR UPDATE":""),id,residentApp());
        return row;
    }
    private Map<String,Object> view(Map<String,Object> row,String app){
        String member=id(row,"id"),phone=id(row,"mobile");
        long active=db.count("SELECT COUNT(*) FROM li_wechat_identity WHERE member_id=? AND app_id=? AND binding_state='ACTIVE'",member,app);
        long pending=db.count("SELECT COUNT(*) FROM li_wechat_identity WHERE member_id=? AND app_id=? AND binding_state='PENDING'",member,app);
        String binding=active>0&&pending==0?"ACTIVE":active==0&&pending>0?"PENDING":"UNRESOLVED";
        String masked=phone==null||phone.isBlank()?null:phone.length()>=7?phone.substring(0,3)+"****"+phone.substring(phone.length()-4):"****";
        return map("id",member,"nickname",id(row,"nick_name"),"avatar",id(row,"face"),"mobileMasked",masked,
                "bindingStatus",binding,"status",row.get("disabled")==null?"UNKNOWN":flag(row,"disabled")?"ACTIVE":"DISABLED",
                "createdAt",row.get("create_time")==null?null:row.get("create_time").toString(),"source","UNVERIFIED");
    }
    public Object impact(String id){var actor=context.require(SessionDomain.MANAGER);var result=impactView(validId(id),false);context.recheck(actor);return result;}
    public Object orders(String id,int page,int size){
        var actor=context.require(SessionDomain.MANAGER);String member=validId(id);resident(member);
        Stage1Exception.require(orderAccess!=null&&orderAccess.managerPermissions(actor.id()).contains("order.read"),403,"FORBIDDEN");
        paging(page,size);long total=db.count("SELECT COUNT(*) FROM li_order o JOIN li_p0_order_snapshot s ON s.order_id=o.id WHERE o.member_id=? AND o.delete_flag=0",member);
        var rows=db.rows("SELECT o.id,o.sn,o.store_name,o.create_time FROM li_order o JOIN li_p0_order_snapshot s ON s.order_id=o.id WHERE o.member_id=? AND o.delete_flag=0 ORDER BY o.id DESC LIMIT ? OFFSET ?",member,size,(long)(page-1)*size);
        var out=new ArrayList<Map<String,Object>>();for(var row:rows)out.add(map("id",id(row,"id"),"orderSn",id(row,"sn"),"storeName",id(row,"store_name"),"createdAt",row.get("create_time")==null?null:row.get("create_time").toString()));
        context.recheck(actor);Stage1Exception.require(orderAccess.managerPermissions(actor.id()).contains("order.read"),403,"FORBIDDEN");
        return map("records",out,"total",total,"pageNumber",page,"pageSize",size);
    }
    public Object aftersales(String id,int page,int size){
        var actor=context.require(SessionDomain.MANAGER);String member=validId(id);resident(member);
        Stage1Exception.require(saleAccess!=null&&saleAccess.managerPermissions(actor.id()).contains("aftersale.read"),403,"FORBIDDEN");
        paging(page,size);long total=db.count("SELECT COUNT(*) FROM li_p0_s45_aftersale a JOIN li_after_sale n ON n.id=a.id WHERE a.member_id=?",member);
        var rows=db.rows("SELECT a.id,a.state,a.type,a.created_at,n.sn FROM li_p0_s45_aftersale a JOIN li_after_sale n ON n.id=a.id WHERE a.member_id=? ORDER BY a.id DESC LIMIT ? OFFSET ?",member,size,(long)(page-1)*size);
        var out=new ArrayList<Map<String,Object>>();for(var row:rows)out.add(map("id",id(row,"id"),"sn",id(row,"sn"),"state",id(row,"state"),"type",id(row,"type"),"createdAt",row.get("created_at")==null?null:row.get("created_at").toString()));
        context.recheck(actor);Stage1Exception.require(saleAccess.managerPermissions(actor.id()).contains("aftersale.read"),403,"FORBIDDEN");
        return map("records",out,"total",total,"pageNumber",page,"pageSize",size);
    }
    private static void paging(int page,int size){Stage1Exception.require(page>=1&&page<=100000&&size>=1&&size<=100,422,"PAGINATION_INVALID");}
    private Map<String,Object> impactView(String member,boolean locked){
        var row=resident(member,locked);Stage1Exception.require(row.get("disabled")!=null,409,"MEMBER_STATUS_UNKNOWN");String resident=residentApp(),merchant=applications.application(SessionDomain.APPLICANT);
        String lock=locked?" FOR UPDATE":"";
        var mappings=db.rows("SELECT id,app_id,binding_version,binding_state FROM li_wechat_identity WHERE member_id=? ORDER BY id"+lock,member);
        var stores=db.rows("SELECT id,store_disable,delete_flag FROM li_store WHERE member_id=? ORDER BY id"+lock,member);
        var clerks=db.rows("SELECT id,store_id,status,shopkeeper,delete_flag FROM li_clerk WHERE member_id=? ORDER BY id"+lock,member);
        var applicationsRows=db.rows("SELECT id,status,store_id FROM li_p0_application WHERE applicant_id=? ORDER BY id"+lock,member);
        var security=db.one("SELECT version,session_cutoff FROM li_p0_member_security WHERE member_id=?"+lock,member);
        Stage1Exception.require(stores.size()<=1&&clerks.size()<=1,409,"MEMBER_RELATION_CONFLICT");
        if(!stores.isEmpty()&&!clerks.isEmpty()&&flag(clerks.getFirst(),"shopkeeper"))
            Stage1Exception.require(Objects.equals(id(stores.getFirst(),"id"),id(clerks.getFirst(),"store_id")),409,"MEMBER_RELATION_CONFLICT");
        boolean residentBound=mappings.stream().anyMatch(m->resident.equals(id(m,"app_id"))&&Set.of("ACTIVE","PENDING").contains(id(m,"binding_state")));
        Stage1Exception.require(residentBound,409,"MEMBER_IDENTITY_CHANGED");
        boolean merchantBound=mappings.stream().anyMatch(m->merchant.equals(id(m,"app_id")));
        String version=hash(map("member",member,"enabled",row.get("disabled"),"mappings",mappings,"stores",stores,"clerks",clerks,"applications",applicationsRows,"security",security));
        return map("memberId",member,"status",flag(row,"disabled")?"ACTIVE":"DISABLED","residentIdentity",residentBound,
                "merchantIdentity",merchantBound,"merchantAssociated",merchantBound||!stores.isEmpty()||!clerks.isEmpty()||!applicationsRows.isEmpty(),"version",version);
    }
    public Object change(String id,Object body,boolean enabled){
        String member=validId(id);var f=new Fields(body).only("requestId","version","reason");
        String expected=f.text("version",64,64,true),reason=f.text("reason",1,500,true),request=f.requestId();
        Stage1Exception.require(expected.matches("[a-f0-9]{64}"),422,"INVALID_VERSION");
        String action=enabled?"resident.enable":"resident.disable";
        return mutations.run(action,body,null,List.of(member),()->{
            var impact=impactView(member,true);Stage1Exception.require(expected.equals(impact.get("version")),409,"VERSION_CONFLICT");
            Stage1Exception.require((enabled?"DISABLED":"ACTIVE").equals(impact.get("status")),409,"STATUS_CONFLICT");
            db.cas(db.update("UPDATE li_member SET disabled=? WHERE id=? AND disabled=? AND delete_flag=0",enabled?1:0,member,enabled?0:1));
            // Both edges fence a login that began before disable but completed after reenable.
            long cutoff=clock.millis();db.update("INSERT INTO li_p0_member_security(member_id,session_cutoff,version) VALUES(?,?,1) ON DUPLICATE KEY UPDATE session_cutoff=GREATEST(session_cutoff,VALUES(session_cutoff)),version=version+1",member,cutoff);
            String audit=mutations.audit(action,null,member,request,null,null,reason);
            return map("memberId",member,"status",enabled?"ACTIVE":"DISABLED","auditId",audit);
        });
    }
}
