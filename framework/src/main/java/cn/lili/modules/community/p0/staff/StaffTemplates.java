package cn.lili.modules.community.p0.staff;

import cn.lili.modules.community.p0.support.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service
@cn.lili.modules.community.p0.support.P0BusinessEnabled
public class StaffTemplates {
    @org.springframework.beans.factory.annotation.Value("${p0.stage3.enabled:false}") private boolean stage3Enabled;
    @org.springframework.beans.factory.annotation.Value("${p0.stage45.enabled:false}") private boolean stage45Enabled;
    private Map<String,Set<String>> templates(){if(!stage3Enabled)return TEMPLATES;var result=new LinkedHashMap<>(TEMPLATES);result.put("S3_ORDER_READER",Set.of("s3.order.read","s3.message.read"));result.put("S3_FULFILLMENT",Set.of("s3.order.read","s3.fulfillment.write","s3.order.exception","s3.message.read"));result.put("S3_VERIFIER",Set.of("s3.order.read","s3.verification.lookup","s3.verification.confirm","s3.order.exception","s3.message.read"));if(stage45Enabled){result.put("S45_AFTERSALE_READER",Set.of("s45.aftersale.read","s45.message.read"));result.put("S45_AFTERSALE_REVIEWER",Set.of("s45.aftersale.read","s45.aftersale.review","s45.message.read"));result.put("S45_RETURN_RECEIVER",Set.of("s45.aftersale.read","s45.aftersale.receive","s45.message.read"));result.put("S45_FUNDS_READER",Set.of("s45.funds.read","s45.message.read"));result.put("S45_EVALUATION_READER",Set.of("s45.evaluation.read","s45.message.read"));}return result;}
    @org.springframework.beans.factory.annotation.Value("${p0.uat.enabled:false}") private boolean integrated;
    private final Stage1Db db;private final Clock clock;
    public StaffTemplates(Stage1Db db,@Qualifier("stage1Clock") Clock clock){this.db=db;this.clock=clock;}
    public static final Map<String,Set<String>> TEMPLATES=Map.of(
        "GOODS_OPERATOR",Set.of("goods.read","goods.write","goods.submit","goods.publish","stock.read","point.read","fulfillment.read","file.read","file.upload"),
        "INVENTORY",Set.of("goods.read","stock.read","stock.write"),
        "POINT",Set.of("point.read","point.write","fulfillment.read"),
        "READ_ONLY",Set.of("goods.read","stock.read","point.read","fulfillment.read"),
        "PROFILE_MAINTAINER",Set.of("profile.read","profile.write","file.read","file.upload"),
        "COST_VIEWER",Set.of("goods.read","cost.read"),
        "COST_EDITOR",Set.of("goods.read","cost.read","cost.write"),
        "FULFILLMENT_EDITOR",Set.of("fulfillment.read","fulfillment.configure","point.read"),
        "COOPERATION_VIEWER",Set.of("cooperation.read","file.read")
    );
    public void create(String store){
        Stage1Exception.require(db.count("SELECT COUNT(*) FROM li_store_role WHERE store_id=?",store)==0,409,"ROLE_TEMPLATE_CONFLICT");
        for(var entry:new TreeMap<>(templates()).entrySet()){
            String id=db.id();db.nativeInsert("li_store_role",map("id",id,"create_time",clock.instant(),"delete_flag",false,"default_role",false,"name",entry.getKey(),"store_id",store,"description",(entry.getKey().startsWith("S45_")?"S45:":entry.getKey().startsWith("S3_")?"S3:":"S1:")+entry.getKey()));
            for(String action:entry.getValue()){
                var menu=db.required("SELECT id FROM li_store_menu WHERE permission=? AND delete_flag=0",(action.startsWith("s3.")||action.startsWith("s45.")?action:"s1."+action));
                db.nativeInsert("li_store_menu_role",map("id",db.id(),"create_time",clock.instant(),"delete_flag",false,"role_id",id,"menu_id",id(menu,"id"),"store_id",store,"is_super",!action.endsWith(".read")));
            }
        }
    }
    public void validate(String store,List<String> roles){
        if(integrated){
            Stage1Exception.require(roles!=null&&roles.size()<=100&&new HashSet<>(roles).size()==roles.size(),422,"STAFF_ROLE_INVALID");
            for(String role:roles)Stage1Exception.require(role!=null&&role.matches("[0-9]{1,19}")&&db.count("SELECT COUNT(*) FROM li_store_role WHERE id=? AND store_id=? AND delete_flag=0",role,store)==1,422,"STAFF_ROLE_INVALID");
            return;
        }
        Stage1Exception.require(!roles.isEmpty()&&roles.size()<=templates().size(),422,"STAFF_ROLE_REQUIRED");
        for(String role:roles){var row=db.required("SELECT name,description FROM li_store_role WHERE id=? AND store_id=? AND delete_flag=0",role,store);Stage1Exception.require(templates().containsKey(id(row,"name"))&&((id(row,"name").startsWith("S45_")?"S45:":id(row,"name").startsWith("S3_")?"S3:":"S1:")+id(row,"name")).equals(id(row,"description")),422,"STAFF_ROLE_INVALID");}
    }
    public void assign(String clerk,String store,List<String> roles){
        validate(store,roles);db.update("DELETE FROM li_clerk_role WHERE clerk_id=?",clerk);
        for(String role:roles)db.nativeInsert("li_clerk_role",map("id",db.id(),"clerk_id",clerk,"role_id",role));
        db.update("UPDATE li_clerk SET role_ids=? WHERE id=? AND store_id=?",String.join(",",roles),clerk,store);
    }
    public Object options(String store){
        if(integrated)return db.rows("SELECT id,name FROM li_store_role WHERE store_id=? AND delete_flag=0 ORDER BY id",store).stream().map(r->map("id",id(r,"id"),"name",id(r,"name"),"permissions",db.rows("SELECT m.permission,rm.is_super FROM li_store_menu_role rm JOIN li_store_menu m ON m.id=rm.menu_id AND m.delete_flag=0 WHERE rm.role_id=? AND rm.store_id=? AND rm.delete_flag=0",id(r,"id"),store).stream().filter(g->id(g,"permission")!=null&&(id(g,"permission").endsWith(".read")||flag(g,"is_super"))).map(g->id(g,"permission")).filter(Objects::nonNull).toList())).toList();
        return db.rows("SELECT id,name FROM li_store_role WHERE store_id=? AND delete_flag=0 ORDER BY id",store).stream().filter(r->templates().containsKey(id(r,"name"))).map(r->map("id",id(r,"id"),"name",id(r,"name"),"permissions",templates().get(id(r,"name")))).toList();}
    public void history(String clerk,String source,String audit){
        var row=db.required("SELECT c.store_id,c.status,k.version,k.revoked FROM li_clerk c JOIN li_p0_clerk_control k ON k.clerk_id=c.id WHERE c.id=?",clerk);
        var roles=db.rows("SELECT role_id FROM li_clerk_role WHERE clerk_id=? ORDER BY role_id",clerk).stream().map(r->id(r,"role_id")).toList();
        db.update("INSERT INTO li_p0_staff_grant_history(clerk_id,version,store_id,role_ids,state,source_action,audit_reference,created_at) VALUES(?,?,?,?,?,?,?,?)",clerk,number(row,"version"),id(row,"store_id"),write(roles),flag(row,"revoked")?"REVOKED":flag(row,"status")?"ACTIVE":"DISABLED",source,audit,clock.instant());
    }
}
