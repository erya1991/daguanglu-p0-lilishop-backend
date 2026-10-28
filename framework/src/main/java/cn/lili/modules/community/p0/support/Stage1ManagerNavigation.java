package cn.lili.modules.community.p0.support;
import cn.lili.modules.permission.entity.dos.Menu;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
/** UI locations are finite. Permissions always come from current native Role/Menu assignments. */
@Component
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
public class Stage1ManagerNavigation {
    private final Stage1Access access;private final Stage1Settings settings;public Stage1ManagerNavigation(Stage1Access access,Stage1Settings settings){this.access=access;this.settings=settings;}
    private static final String[][] PAGES={
        {"applications","商户申请审核","admission.review","seller/shop/shopList"},
        {"stores","商户管理","store.read","seller/shop/shopList"},
        {"agreements","合作协议","agreement.read","p0-stage1/agreements"},
        {"eligibility","经营资格","store.read","p0-stage1/eligibility"},
        {"channel-configs","收付通配置","channel.configure","p0-stage1/channel-configs"},
        {"submerchants","二级商户与渠道关系","channel.onboard","p0-stage1/submerchants"},
        {"receivers","平台接收关系","channel.receiver","p0-stage1/receivers"},
        {"commission-rules","佣金规则","rule.read","p0-stage1/commission-rules"},
        {"categories","商品类目","catalog.configure","goods/goods-manage/category"},
        {"units","商品单位","catalog.configure","goods-unit/index"},
        {"reviews","商品审核","goods.review","goods/goods-info/goodsApply"},
        {"goods","商品监管","goods.read","goods/goods-info/goods"},
        {"fulfillment","履约核查","fulfillment.inspect","p0-stage1/fulfillment"}
    };
    public Object describe(String actor){var permissions=new TreeSet<>(access.managerPermissions(actor));return map("permissions",permissions,"authVersion",hash(permissions),"environment",settings.getEnvironment(),"mode",settings.getMode());}
    public List<Menu> menus(String actor){var permissions=access.managerPermissions(actor);var rows=new ArrayList<Menu>();rows.add(menu("s1-root","0","s1-root","商城管理","/s1-root","Main",0,0));rows.add(menu("s1-management","s1-root","s1-management","Stage1 商城","/s1","Main",1,0));int index=0;for(var page:PAGES)if(permissions.contains(page[2])){var row=menu("s1-"+page[0],"s1-management","s1-"+page[0],page[1],"/s1/"+page[0],page[3],2,++index);row.setPermission("s1."+page[2]);rows.add(row);}return rows;}
    private static Menu menu(String id,String parent,String name,String title,String path,String front,int level,int order){var row=new Menu();row.setId(id);row.setParentId(parent);row.setName(name);row.setTitle(title);row.setPath(path);row.setFrontRoute(front);row.setLevel(level);row.setSortOrder((double)order);row.setDeleteFlag(false);return row;}
}
