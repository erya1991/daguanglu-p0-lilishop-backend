package cn.lili.modules.community.p0.catalog;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.agreement.AgreementService;
import cn.lili.modules.community.p0.eligibility.EligibilityService;
import cn.lili.modules.community.p0.fulfillment.FulfillmentService;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.*;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** One repeatable-read view of native Goods/Sku facts. Deliberately has no writer, cache or event dependency. */
@Service
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
public class ConsumerGoodsService {
    private final Stage1Db db;private final Stage1Context context;private final EligibilityService eligibility;private final FulfillmentService fulfillment;private final Stage1Settings settings;private final Clock clock;private final TransactionTemplate read;
    public ConsumerGoodsService(Stage1Db db,Stage1Context context,EligibilityService eligibility,FulfillmentService fulfillment,Stage1Settings settings,PlatformTransactionManager manager,@Qualifier("stage1Clock") Clock clock){this.db=db;this.context=context;this.eligibility=eligibility;this.fulfillment=fulfillment;this.settings=settings;this.clock=clock;read=new TransactionTemplate(manager);read.setReadOnly(true);read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);}
    public Object detail(String id){context.require(SessionDomain.RESIDENT);return read.execute(tx->detailValue(id));}
    private Map<String,Object> detailValue(String id){
        var row=goods(id,null);String store=id(row,"store_id");var state=eligibility.evaluate(store,id(row,"category_id"),null);var reasons=contentReasons(row);reasons.addAll(eligibilityReasons(state));var methods=methods(row,fulfillment.effective(store));if(methods.isEmpty())reasons.add("NO_EFFECTIVE_METHOD");
        var skus=db.rows("SELECT s.id,s.sn,s.specs,s.price,s.weight,s.quantity,s.original,c.inventory_version,c.enabled FROM li_goods_sku s JOIN li_p0_sku_control c ON c.sku_id=s.id WHERE c.goods_id=? AND s.delete_flag=0 AND c.enabled=1 ORDER BY s.id",id);
        if(skus.stream().noneMatch(s->number(s,"quantity")>0))reasons.add("OUT_OF_STOCK");boolean approved=approved(row);var payload=new Fields(row.get("payload"));
        return map("id",id,"storeId",store,"name",approved?row.get("goods_name"):null,"contentVersion",row.get("content_version"),"version",row.get("version"),"available",reasons.isEmpty(),"reasonCodes",distinct(reasons),"mode",settings.getMode(),"evaluatedAt",clock.instant(),"validUntil",state.get("validUntil"),"eligibility",publicEligibility(state),"methods",methods,"fulfillmentVersion",fulfillment.version(store),"galleryFileIds",approved?payload.list("galleryFileIds",0,9,false):List.of(),"detailBlocks",approved?payload.list("detailBlocks",0,200,false):List.of(),"skus",approved?skus.stream().map(s->map("id",id(s,"id"),"code",s.get("sn"),"specifications",array(s.get("specs")),"priceCent",cents(s.get("price")),"quantity",s.get("quantity"),"inventoryVersion",s.get("inventory_version"),"image",s.get("original"))).toList():List.of());
    }
    public Object list(String store,int page,int size){
        context.require(SessionDomain.RESIDENT);AgreementService.page(page,size);
        return read.execute(tx->{
            db.required("SELECT store_id FROM li_p0_store_control WHERE store_id=?",store);var effective=fulfillment.effective(store);Map<String,Boolean> categories=new HashMap<>();var available=new ArrayList<String>();
            var candidates=db.rows("SELECT c.goods_id,c.category_id,c.methods FROM li_p0_goods_control c JOIN li_goods g ON g.id=c.goods_id WHERE c.store_id=? AND c.review_status='APPROVED' AND c.approved_version=c.content_version AND c.forced_off=0 AND g.delete_flag=0 AND g.market_enable='UPPER' AND g.auth_flag='PASS' AND EXISTS(SELECT 1 FROM li_p0_sku_control k JOIN li_goods_sku s ON s.id=k.sku_id WHERE k.goods_id=c.goods_id AND k.enabled=1 AND s.delete_flag=0 AND s.quantity>0) ORDER BY c.goods_id DESC",store);
            for(var row:candidates)if(!methods(row,effective).isEmpty()&&categories.computeIfAbsent(id(row,"category_id"),cat->eligibilityReasons(eligibility.evaluate(store,cat,null)).isEmpty()))available.add(id(row,"goods_id"));
            long offset=(long)(page-1)*size;var ids=available.stream().skip(offset).limit(size).toList();return map("total",available.size(),"records",ids.stream().map(this::detailValue).toList(),"mode",settings.getMode());
        });
    }
    public Object precheck(Object body){
        context.require(SessionDomain.RESIDENT);var f=new Fields(body).only("storeId","items","method","address","pointId");String store=f.id("storeId",true),method=f.choice("method",Stage1Rules.METHODS,false),point=f.id("pointId",false);var items=f.list("items",1,100,true);Map<String,Long> quantities=new LinkedHashMap<>();
        for(var item:items){var line=new Fields(item).only("skuId","quantity");String sku=line.id("skuId",true);long quantity=line.integer("quantity",1,9999999,true);long combined=quantities.getOrDefault(sku,0L)+quantity;Stage1Exception.require(combined<=9999999,422,"SKU_QUANTITY_LIMIT");quantities.put(sku,combined);}
        JsonObject address=f.has("address")?f.child("address",true):null;return read.execute(tx->precheckValue(store,quantities,method,address,point));
    }
    private Object precheckValue(String store,Map<String,Long> quantities,String method,JsonObject address,String point){
        db.required("SELECT store_id FROM li_p0_store_control WHERE store_id=?",store);Instant at=clock.instant();var sources=new LinkedHashMap<String,Object>();var results=new ArrayList<Object>();var reasons=new ArrayList<String>();var missing=new ArrayList<String>();var effective=fulfillment.effective(store);Set<String> intersection=new TreeSet<>(effective.keySet());Map<String,Map<String,Object>> states=new HashMap<>();
        // Check every object's Store before returning any object facts, including the first valid item.
        Map<String,Map<String,Object>> skus=new LinkedHashMap<>();for(String id:quantities.keySet())skus.put(id,db.required("SELECT s.id,s.goods_id,s.store_id,s.price,s.quantity,s.delete_flag,k.enabled,k.inventory_version FROM li_goods_sku s JOIN li_p0_sku_control k ON k.sku_id=s.id AND k.store_id=s.store_id WHERE s.id=? AND s.store_id=?",id,store));
        for(var entry:quantities.entrySet()){
            var sku=skus.get(entry.getKey());var row=goods(id(sku,"goods_id"),store);var itemReasons=contentReasons(row);var state=states.computeIfAbsent(id(row,"category_id"),cat->eligibility.evaluate(store,cat,at));itemReasons.addAll(eligibilityReasons(state));
            if(flag(sku,"delete_flag")||!flag(sku,"enabled"))itemReasons.add("SKU_DISABLED");if(sku.get("quantity")==null||number(sku,"quantity")<entry.getValue())itemReasons.add("OUT_OF_STOCK");var usable=methods(row,effective);intersection.retainAll(usable);if(usable.isEmpty())itemReasons.add("NO_EFFECTIVE_METHOD");reasons.addAll(itemReasons);
            sources.put("goods:"+id(row,"goods_id"),map("contentVersion",row.get("content_version"),"version",row.get("version")));sources.put("sku:"+entry.getKey(),map("inventoryVersion",sku.get("inventory_version")));
            results.add(map("goodsId",id(row,"goods_id"),"skuId",entry.getKey(),"quantity",entry.getValue(),"priceCent",sku.get("price")==null?null:cents(sku.get("price")),"inventoryVersion",sku.get("inventory_version"),"contentVersion",row.get("content_version"),"valid",itemReasons.isEmpty(),"reasonCodes",distinct(itemReasons)));
        }
        if(intersection.isEmpty())reasons.add("NO_COMMON_METHOD");
        String district=null;if(address!=null){var a=new Fields(address).only("provinceCode","cityCode","districtCode");String province=a.text("provinceCode",6,6,true),city=a.text("cityCode",6,6,true);district=a.text("districtCode",6,6,true);db.required("SELECT d.id FROM li_region d JOIN li_region c ON c.id=d.parent_id AND c.ad_code=? JOIN li_region p ON p.id=c.parent_id AND p.ad_code=? WHERE d.ad_code=? AND d.level='district' AND d.delete_flag=0 AND c.delete_flag=0 AND p.delete_flag=0",city,province,district);}
        Map<String,Object> selectedPoint=point==null?null:fulfillment.point(point,store);Set<String> contextual=new TreeSet<>(intersection);
        if(district!=null){String code=district;contextual.removeIf(m->Set.of("MERCHANT_DELIVERY","EXPRESS").contains(m)&&!fulfillment.supports(effective.get(m),code));}
        if(method==null)missing.add("METHOD_REQUIRED");
        else if(!intersection.contains(method))reasons.add("METHOD_UNAVAILABLE");
        else if(Set.of("MERCHANT_DELIVERY","EXPRESS").contains(method)){
            if(point!=null)throw new Stage1Exception(422,"POINT_NOT_APPLICABLE");if(district==null)missing.add("ADDRESS_REQUIRED");else if(!contextual.contains(method))reasons.add("ADDRESS_OUT_OF_SCOPE");
        }else{
            if(address!=null)throw new Stage1Exception(422,"ADDRESS_NOT_APPLICABLE");if(point==null)missing.add("POINT_REQUIRED");else if(!fulfillment.isAvailablePoint(selectedPoint,method))reasons.add("POINT_UNAVAILABLE");else sources.put("point",map("id",point,"version",selectedPoint.get("version")));
        }
        sources.put("fulfillment",map("version",fulfillment.version(store)));states.forEach((cat,state)->sources.put("eligibility:"+cat,state.get("sourceFingerprint")));
        var candidates=new LinkedHashMap<String,Object>();for(String m:contextual)if(Set.of("COMMUNITY_PICKUP","STORE_REDEMPTION").contains(m))candidates.put(m,fulfillment.effectivePoints(store,m).stream().map(p->fulfillment.pointView(p,true)).toList());
        var feeSources=new LinkedHashMap<String,Object>();for(String m:contextual)feeSources.put(m,map("feeCent",effective.get(m).get("feeCent"),"meaning",Set.of("MERCHANT_DELIVERY","EXPRESS").contains(m)?"FIXED_PER_STORE_ORDER_CONFIGURATION":"FREE","configurationVersion",fulfillment.version(store)));
        return map("status",reasons.isEmpty()?(missing.isEmpty()?"PASS":"INPUT_REQUIRED"):"BLOCKED","storeId",store,"mode",settings.getMode(),"items",results,"reasonCodes",distinct(reasons),"missing",missing,"commonMethods",contextual,"candidatePoints",candidates,"feeConfigurations",feeSources,"eligibility",states.values().stream().map(this::publicEligibility).toList(),"sourceVersions",sources,"evaluatedAt",at,"validUntil",states.values().stream().map(s->(Instant)s.get("validUntil")).filter(Objects::nonNull).min(Instant::compareTo).orElse(null));
    }
    private Map<String,Object> goods(String id,String store){return db.required("SELECT c.*,g.goods_name,g.market_enable,g.auth_flag,g.delete_flag,d.payload FROM li_p0_goods_control c JOIN li_goods g ON g.id=c.goods_id JOIN li_p0_goods_draft d ON d.goods_id=c.goods_id WHERE c.goods_id=? AND (? IS NULL OR c.store_id=?)",id,store,store);}
    private static boolean approved(Map<String,Object> r){return "APPROVED".equals(id(r,"review_status"))&&r.get("approved_version")!=null&&number(r,"approved_version")==number(r,"content_version")&&"PASS".equals(id(r,"auth_flag"))&&!flag(r,"delete_flag");}
    private static List<String> contentReasons(Map<String,Object> r){var reasons=new ArrayList<String>();if(!approved(r))reasons.add("CONTENT_NOT_APPROVED");if(!"UPPER".equals(id(r,"market_enable")))reasons.add("GOODS_UNLISTED");if(flag(r,"forced_off"))reasons.add("GOODS_RESTRICTED");return reasons;}
    private static Set<String> methods(Map<String,Object> row,Map<String,JsonObject> effective){Set<String> result=new TreeSet<>(GoodsManagementService.methods(row));result.retainAll(effective.keySet());return result;}
    private List<String> eligibilityReasons(Map<String,Object> value){var reasons=object(value.get("reasons")).getAsJsonArray(settings.getMode());return reasons.asList().stream().map(e->e.getAsJsonObject().get("code").getAsString()).distinct().toList();}
    private Object publicEligibility(Map<String,Object> value){var sources=object(value.get("sourceVersions"));return map("storeId",value.get("storeId"),"categoryId",value.get("categoryId"),"mode",settings.getMode(),"ready",value.get(settings.getMode()+"_READY"),"reasonCodes",eligibilityReasons(value),"rule",sources.get("rule"),"evaluatedAt",value.get("evaluatedAt"),"validUntil",value.get("validUntil"),"sourceFingerprint",value.get("sourceFingerprint"));}
    private static List<String> distinct(List<String> values){return values.stream().distinct().toList();}
}
