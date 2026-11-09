package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.catalog.ConsumerGoodsService;
import cn.lili.modules.community.p0.eligibility.EligibilityService;
import cn.lili.modules.community.p0.fulfillment.FulfillmentService;
import cn.lili.modules.community.p0.catalog.GoodsManagementService;
import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service @ConditionalOnProperty(name="p0.stage2.enabled",havingValue="true")
public class Stage2Catalog {
    private final FulfillmentService fulfillment;private final Stage1Db db;private final ConsumerGoodsService consumer;private final EligibilityService eligibility;private final Stage2Transactions transactions;
    public Stage2Catalog(Stage1Db db,ConsumerGoodsService consumer,EligibilityService eligibility,Stage2Transactions transactions,FulfillmentService fulfillment){this.fulfillment=fulfillment;this.db=db;this.consumer=consumer;this.eligibility=eligibility;this.transactions=transactions;}
    public Object detail(String id){transactions.member();return enrich(db,object(consumer.detail(id)));}
    public Object list(String keyword,String category,String sort,int page,int size){
        transactions.member();Stage1Exception.require(page>=1&&size>=1&&size<=100,422,"PAGE_INVALID");
        keyword=keyword==null?"":keyword.trim();Stage1Exception.require(keyword.codePointCount(0,keyword.length())<=50,422,"KEYWORD_TOO_LONG");
        Stage1Exception.require(Set.of("COMPREHENSIVE","PRICE_ASC","PRICE_DESC").contains(sort),422,"SORT_INVALID");
        if(category!=null)db.required("SELECT c.id FROM li_category c JOIN li_p0_catalog_control p ON p.object_id=c.id AND p.object_type='CATEGORY' WHERE c.id=? AND c.delete_flag=0 AND p.enabled=1",category);
        String order=switch(sort){case "PRICE_ASC"->"price_cent ASC,gid DESC";case "PRICE_DESC"->"price_cent DESC,gid DESC";default->"updated DESC,gid DESC";};
        // Only remove facts that already necessarily fail the existing MOCK qualification.
        // The remaining candidates still undergo the full current eligibility and fulfillment checks.
        var candidates=db.rows("SELECT g.id gid,c.store_id,c.category_id,c.methods,COALESCE(g.update_time,g.create_time) updated,MIN(s.price) price_cent FROM li_goods g JOIN li_p0_goods_control c ON c.goods_id=g.id JOIN li_store st ON st.id=c.store_id JOIN li_goods_sku s ON s.goods_id=g.id JOIN li_p0_sku_control k ON k.sku_id=s.id WHERE c.review_status='APPROVED' AND c.approved_version=c.content_version AND c.forced_off=0 AND g.delete_flag=0 AND g.market_enable='UPPER' AND g.auth_flag='PASS' AND s.delete_flag=0 AND k.enabled=1 AND s.quantity>0 AND EXISTS (SELECT 1 FROM li_p0_submerchant_relation r JOIN li_p0_channel_config cfg ON cfg.id=r.config_id WHERE r.store_id=c.store_id AND r.mode='MOCK' AND r.enabled=1 AND r.active_store_key IS NOT NULL AND cfg.mode='MOCK' AND cfg.environment='TEST' AND cfg.enabled=1 AND r.config_version=cfg.config_version) AND (LOCATE(?,g.goods_name)>0 OR LOCATE(?,st.store_name)>0) AND (? IS NULL OR FIND_IN_SET(?,g.category_path)>0) GROUP BY g.id,c.store_id,c.category_id,c.methods,updated ORDER BY "+order,keyword,keyword,category,category);
        var allowed=new ArrayList<String>();var states=new HashMap<String,Boolean>();var effective=new HashMap<String,Set<String>>();
        boolean readComplete=true;
        for(var row:candidates){String store=id(row,"store_id"),cat=id(row,"category_id"),key=store+":"+cat;
            try{boolean ready=states.computeIfAbsent(key,k->Boolean.TRUE.equals(eligibility.evaluate(store,cat,null).get("MOCK_READY")));if(ready){var usable=new HashSet<>(GoodsManagementService.methods(row));usable.retainAll(effective.computeIfAbsent(store,k->fulfillment.effective(k).keySet()));if(!usable.isEmpty())allowed.add(id(row,"gid"));}}
            catch(RuntimeException unavailable){if(!dependencyUnavailable(unavailable))throw unavailable;states.put(key,false);readComplete=false;}
        }
        long offset=(long)(page-1)*size;
        var records=new ArrayList<Object>();var unverified=new HashSet<String>();
        var requested=allowed.stream().skip(offset).limit(size).toList();
        for(String gid:requested){
            try{records.add(enrich(db,object(consumer.detail(gid))));}
            catch(RuntimeException unavailable){if(!dependencyUnavailable(unavailable))throw unavailable;unverified.add(gid);readComplete=false;}
        }
        allowed.removeAll(unverified);
        Stage1Exception.require(readComplete||!allowed.isEmpty()&&(requested.isEmpty()||!records.isEmpty()),503,"CATALOG_UNVERIFIED");
        return map("records",records,"total",allowed.size(),"page",page,"pageSize",size,"mode","MOCK","readComplete",readComplete);
    }
    private static boolean dependencyUnavailable(RuntimeException failure){return failure instanceof org.springframework.dao.DataAccessException||failure instanceof Stage1Exception business&&business.status()>=500;}
    public static Object enrich(Stage1Db db,com.google.gson.JsonObject detail){var meta=db.required("SELECT s.store_name,g.category_id,c.name category_name FROM li_p0_goods_control g JOIN li_store s ON s.id=g.store_id LEFT JOIN li_category c ON c.id=g.category_id WHERE g.goods_id=?",detail.get("id").getAsString());detail.addProperty("storeName",id(meta,"store_name"));detail.addProperty("categoryId",id(meta,"category_id"));detail.addProperty("categoryName",id(meta,"category_name"));return detail;}
    public Object categories(){transactions.member();return db.rows("SELECT c.id,c.name,c.parent_id FROM li_category c JOIN li_p0_catalog_control p ON p.object_id=c.id AND p.object_type='CATEGORY' WHERE c.delete_flag=0 AND p.enabled=1 ORDER BY c.sort_order,c.id").stream().map(r->map("id",id(r,"id"),"name",r.get("name"),"parentId",id(r,"parent_id"))).toList();}
}
