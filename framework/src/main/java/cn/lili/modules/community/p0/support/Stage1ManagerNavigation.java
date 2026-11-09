package cn.lili.modules.community.p0.support;
import cn.lili.modules.permission.entity.dos.Menu;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
/** UI locations are finite. Permissions always come from current native Role/Menu assignments. */
@Component
@cn.lili.modules.community.p0.support.P0BusinessEnabled
public class Stage1ManagerNavigation {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private org.springframework.beans.factory.ObjectProvider<cn.lili.modules.community.p0.stage3.Stage3Authorization> stage3;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private org.springframework.beans.factory.ObjectProvider<cn.lili.modules.community.p0.stage45.Stage45Authorization> stage45;
    private static final String[][] STAGE45_PAGES={{"channels","收付通渠道关系","channel.read","Channels"},{"rules","分账规则","rule.read","Rules"},{"aftersales","售后管理","aftersale.read","AfterSales"},{"evaluations","评价管理","evaluation.read","Evaluations"},{"payments","支付记录","funds.read","Payments"},{"refunds","退款记录","funds.read","Refunds"},{"sharing","分账记录","funds.read","Sharing"},{"returns","分账回退","funds.read","Returns"},{"exceptions","资金异常","funds.read","Exceptions"},{"reconciliation","基础对账","reconciliation.read","Reconciliation"}};
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
    public Object describe(String actor){var permissions=new TreeSet<>(access.managerPermissions(actor));var result=map("permissions",permissions,"authVersion",hash(permissions),"environment",settings.getEnvironment(),"mode",settings.getMode());var s3=stage3==null?null:stage3.getIfAvailable();if(s3!=null){var grants=new TreeSet<>(s3.managerPermissions(actor));result.put("stage3",map("permissions",grants,"authVersion",hash(grants)));}var s45=stage45==null?null:stage45.getIfAvailable();if(s45!=null){var grants=new TreeSet<>(s45.managerPermissions(actor));result.put("stage45",map("permissions",grants,"authVersion",hash(grants)));}return result;}
    public List<Menu> menus(String actor){var permissions=access.managerPermissions(actor);var rows=new ArrayList<Menu>();rows.add(menu("s1-root","0","s1-root","商城管理","/s1-root","Main",0,0));rows.add(menu("s1-management","s1-root","s1-management","Stage1 商城","/s1","Main",1,0));int index=0;for(var page:PAGES)if(permissions.contains(page[2])){var row=menu("s1-"+page[0],"s1-management","s1-"+page[0],page[1],"/s1/"+page[0],page[3],2,++index);row.setPermission("s1."+page[2]);rows.add(row);}var s3=stage3==null?null:stage3.getIfAvailable();if(s3!=null){var grants=s3.managerPermissions(actor);rows.add(menu("s3-management","s1-root","s3-management","订单与履约","/s3","Main",1,1));for(String key:List.of("orders","verifications")){String action=key.equals("orders")?"order.read":"verification.read";if(grants.contains(action)){var row=menu("s3-"+key,"s3-management","s3-"+key,key.equals("orders")?"商城订单":"核销记录","/s3/"+key,"p0-stage3/"+(key.equals("orders")?"Orders":"Verifications"),2,++index);row.setPermission("s3."+action);rows.add(row);}}}var s45=stage45==null?null:stage45.getIfAvailable();if(s45!=null){var grants=s45.managerPermissions(actor);rows.add(menu("s45-management","s1-root","s45-management","售后与资金","/s45","Main",1,2));for(var page:STAGE45_PAGES)if(grants.contains(page[2])){var row=menu("s45-"+page[0],"s45-management","s45-"+page[0],page[1],"/s45/"+page[0],"p0-stage45/"+page[3],2,++index);row.setPermission("s45."+page[2]);rows.add(row);}}return rows;}
    private static Menu menu(String id,String parent,String name,String title,String path,String front,int level,int order){var row=new Menu();row.setId(id);row.setParentId(parent);row.setName(name);row.setTitle(title);row.setPath(path);row.setFrontRoute(front);row.setLevel(level);row.setSortOrder((double)order);row.setDeleteFlag(false);return row;}
}
