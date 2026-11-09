package cn.lili.modules.community.p0.stage3;

import cn.lili.common.security.AuthUser;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.session.*;
import cn.lili.modules.member.mapper.MerchantGrantMapper;
import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service @ConditionalOnProperty(name="p0.stage3.enabled",havingValue="true")
public class Stage3Authorization {
    private final Stage1Db db;private final Stage1Access access;private final MerchantGrantMapper grants;
    public static final Set<String> OWNER=Set.of("order.read","fulfillment.write","order.exception","verification.lookup","verification.confirm","message.read");
    public Stage3Authorization(Stage1Db db,Stage1Access access,MerchantGrantMapper grants){this.db=db;this.access=access;this.grants=grants;}
    public Set<String> storePermissions(AuthUser user){
        var clerk=db.required("SELECT c.shopkeeper,c.department_id,c.status,c.delete_flag,k.revoked,s.member_id FROM li_clerk c JOIN li_p0_clerk_control k ON k.clerk_id=c.id JOIN li_store s ON s.id=c.store_id WHERE c.id=? AND c.member_id=? AND c.store_id=? AND s.delete_flag=0",user.getClerkId(),user.getId(),user.getStoreId());
        Stage1Exception.require(flag(clerk,"status")&&!flag(clerk,"delete_flag")&&!flag(clerk,"revoked"),403,"FORBIDDEN");
        if(flag(clerk,"shopkeeper")){Stage1Exception.require(user.getId().equals(id(clerk,"member_id")),403,"FORBIDDEN");return OWNER;}
        Set<String> result=new TreeSet<>();for(var grant:grants.current(user.getClerkId(),user.getStoreId(),id(clerk,"department_id"))){
            String permission=grant.getPermission();if(permission!=null&&permission.startsWith("s3.")){String action=permission.substring(3);if(OWNER.contains(action)&&(action.endsWith(".read")||Boolean.TRUE.equals(grant.getWriteGrant())))result.add(action);}
        }return Set.copyOf(result);
    }
    public Set<String> managerPermissions(String id){
        access.checkManager(id);Set<String> result=new TreeSet<>();
        for(var row:db.rows("SELECT DISTINCT m.permission FROM li_menu m JOIN li_role_menu rm ON rm.menu_id=m.id JOIN li_role r ON r.id=rm.role_id WHERE m.delete_flag=0 AND rm.delete_flag=0 AND r.delete_flag=0 AND (EXISTS(SELECT 1 FROM li_user_role ur WHERE ur.role_id=r.id AND ur.user_id=?) OR EXISTS(SELECT 1 FROM li_department_role dr JOIN li_admin_user a ON a.department_id=dr.department_id JOIN li_department d ON d.id=dr.department_id WHERE dr.role_id=r.id AND a.id=? AND dr.delete_flag=0 AND d.delete_flag=0))",id,id)){
            String permission=id(row,"permission");if(Set.of("s3.order.read","s3.verification.read").contains(permission))result.add(permission.substring(3));
        }return Set.copyOf(result);
    }
    public boolean checkRoute(AuthUser user,String method,String path,boolean verified){
        Stage1Exception.require(verified&&user!=null,403,"FORBIDDEN");
        SessionDomain domain=switch(user.getRole()){case MEMBER->SessionDomain.RESIDENT;case STORE->SessionDomain.STORE;case MANAGER->SessionDomain.MANAGER;default->throw new Stage1Exception(403,"FORBIDDEN");};
        var route=Stage3Routes.find(domain,method,path);Stage1Exception.require(route!=null,403,"FORBIDDEN");
        if(domain==SessionDomain.STORE){var current=storePermissions(user);Stage1Exception.require("context.read".equals(route.action())||current.contains(route.action()),403,"FORBIDDEN");}
        if(domain==SessionDomain.MANAGER)Stage1Exception.require(managerPermissions(user.getId()).contains(route.action()),403,"FORBIDDEN");return true;
    }
    public void order(Stage1Context.Actor actor,Map<String,Object> row,String action){
        if(actor.domain()==SessionDomain.RESIDENT){Stage1Exception.require(actor.id().equals(id(row,"member_id")),404,"RESOURCE_NOT_FOUND");return;}
        if(actor.domain()==SessionDomain.STORE){Stage1Exception.require(actor.store().equals(id(row,"store_id"))&&storePermissions(actor.user()).contains(action),404,"RESOURCE_NOT_FOUND");return;}
        Stage1Exception.require(actor.domain()==SessionDomain.MANAGER&&managerPermissions(actor.id()).contains(action),404,"RESOURCE_NOT_FOUND");
    }
}
