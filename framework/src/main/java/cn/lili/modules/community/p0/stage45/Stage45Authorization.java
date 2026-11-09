package cn.lili.modules.community.p0.stage45;

import cn.lili.common.security.AuthUser;
import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.member.mapper.MerchantGrantMapper;
import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service @ConditionalOnProperty(name="p0.stage45.enabled",havingValue="true")
public class Stage45Authorization {
    public static final Set<String> STORE_ACTIONS=Set.of("aftersale.read","aftersale.review","aftersale.receive","evaluation.read","funds.read","message.read");
    public static final Set<String> MANAGER_ACTIONS=Set.of("channel.read","channel.sync","rule.read","aftersale.read","aftersale.resolve","evaluation.read","evaluation.moderate","funds.read","funds.recover","reconciliation.read","reconciliation.create","reconciliation.review","message.read");
    private final Stage1Db db;private final Stage1Access access;private final MerchantGrantMapper grants;
    public Stage45Authorization(Stage1Db db,Stage1Access access,MerchantGrantMapper grants){this.db=db;this.access=access;this.grants=grants;}
    public Set<String> permissions(AuthUser user) {
        if(user.getRole()==cn.lili.common.security.enums.UserEnums.MEMBER)return Set.of("aftersale.read","aftersale.apply","aftersale.cancel","aftersale.return","aftersale.dispute","aftersale.evidence","evaluation.read","evaluation.write","evaluation.public","message.read","attachment.read","attachment.write");
        var out=new TreeSet<String>();
        if(user.getRole()==cn.lili.common.security.enums.UserEnums.STORE) {
            var clerk=db.required("SELECT c.shopkeeper,c.department_id,c.status,c.delete_flag,k.revoked,s.member_id FROM li_clerk c JOIN li_p0_clerk_control k ON k.clerk_id=c.id JOIN li_store s ON s.id=c.store_id WHERE c.id=? AND c.member_id=? AND c.store_id=? AND s.delete_flag=0",user.getClerkId(),user.getId(),user.getStoreId());
            Stage1Exception.require(flag(clerk,"status")&&!flag(clerk,"delete_flag")&&!flag(clerk,"revoked"),403,"FORBIDDEN");
            if(flag(clerk,"shopkeeper")) {
                Stage1Exception.require(user.getId().equals(id(clerk,"member_id")),403,"FORBIDDEN");
                out.addAll(STORE_ACTIONS);
            } else for(var g:grants.current(user.getClerkId(),user.getStoreId(),id(clerk,"department_id"))) {
                String permission=g.getPermission();
                if(permission!=null&&permission.startsWith("s45.")) {
                    String action=permission.substring(4);
                    if(STORE_ACTIONS.contains(action)&&(action.endsWith(".read")||Boolean.TRUE.equals(g.getWriteGrant())))out.add(action);
                }
            }
        } else {
            Stage1Exception.require(user.getRole()==cn.lili.common.security.enums.UserEnums.MANAGER,403,"FORBIDDEN");
            access.checkManager(user.getId());
            for(var row:db.rows("SELECT DISTINCT m.permission,rm.is_super FROM li_menu m JOIN li_role_menu rm ON rm.menu_id=m.id JOIN li_role r ON r.id=rm.role_id WHERE m.delete_flag=0 AND rm.delete_flag=0 AND r.delete_flag=0 AND (EXISTS(SELECT 1 FROM li_user_role ur WHERE ur.role_id=r.id AND ur.user_id=?) OR EXISTS(SELECT 1 FROM li_department_role dr JOIN li_admin_user a ON a.department_id=dr.department_id JOIN li_department d ON d.id=dr.department_id WHERE dr.role_id=r.id AND a.id=? AND dr.delete_flag=0 AND d.delete_flag=0))",user.getId(),user.getId())) {
                String permission=id(row,"permission");
                if(permission!=null&&permission.startsWith("s45.")) {
                    String action=permission.substring(4);
                    if(MANAGER_ACTIONS.contains(action)&&(action.endsWith(".read")||flag(row,"is_super")))out.add(action);
                }
            }
        }
        if(out.contains("aftersale.read")||out.contains("evaluation.read"))out.add("attachment.read");
        if(out.contains("aftersale.review")||out.contains("aftersale.receive")||out.contains("aftersale.resolve"))out.add("attachment.write");
        return Set.copyOf(out);
    }
    public Set<String> managerPermissions(String actor){return permissions(AuthUser.builder().id(actor).role(cn.lili.common.security.enums.UserEnums.MANAGER).build());}
    public boolean checkRoute(AuthUser user,String method,String path,boolean verified) {
        Stage1Exception.require(verified&&user!=null,403,"FORBIDDEN");
        SessionDomain domain=switch(user.getRole()){case MEMBER->SessionDomain.RESIDENT;case STORE->SessionDomain.STORE;case MANAGER->SessionDomain.MANAGER;default->throw new Stage1Exception(403,"FORBIDDEN");};
        var route=Stage45Routes.find(domain,method,path);
        Stage1Exception.require(route!=null,403,"FORBIDDEN");
        var allowed=permissions(user);
        Stage1Exception.require("context.read".equals(route.action())||allowed.contains(route.action()),403,"FORBIDDEN");
        return true;
    }
    public void order(Stage1Context.Actor actor,Map<String,Object> order,String action) {
        Stage1Exception.require(permissions(actor.user()).contains(action),403,"FORBIDDEN");
        if(actor.domain()==SessionDomain.RESIDENT)Stage1Exception.require(actor.id().equals(id(order,"member_id")),404,"RESOURCE_NOT_FOUND");
        else if(actor.domain()==SessionDomain.STORE)Stage1Exception.require(actor.store().equals(id(order,"store_id")),404,"RESOURCE_NOT_FOUND");
        else Stage1Exception.require(actor.domain()==SessionDomain.MANAGER,403,"FORBIDDEN");
    }
    public String scope(Stage1Context.Actor actor,String alias,List<Object> args) {
        if(actor.domain()==SessionDomain.RESIDENT){args.add(actor.id());return alias+".member_id=?";}
        if(actor.domain()==SessionDomain.STORE){args.add(actor.store());return alias+".store_id=?";}
        Stage1Exception.require(actor.domain()==SessionDomain.MANAGER,403,"FORBIDDEN");return "1=1";
    }
}
