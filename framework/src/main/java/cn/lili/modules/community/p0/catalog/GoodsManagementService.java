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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.time.Clock;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
public class GoodsManagementService {
    private final Stage1Db db;private final Stage1Context context;private final Stage1Access access;private final Stage1Mutations mutations;private final GoodsContentRules rules;private final NativeGoodsProjector nativeGoods;private final EligibilityService eligibility;private final FulfillmentService fulfillment;private final Clock clock;
    public GoodsManagementService(Stage1Db db,Stage1Context context,Stage1Access access,Stage1Mutations mutations,GoodsContentRules rules,NativeGoodsProjector nativeGoods,EligibilityService eligibility,FulfillmentService fulfillment,@Qualifier("stage1Clock") Clock clock){this.db=db;this.context=context;this.access=access;this.mutations=mutations;this.rules=rules;this.nativeGoods=nativeGoods;this.eligibility=eligibility;this.fulfillment=fulfillment;this.clock=clock;}
    public Object save(String id,Object body){
        var actor=context.require(SessionDomain.STORE);var f=new Fields(body).only(id==null?new String[]{"requestId","payload"}:new String[]{"requestId","expectedVersion","payload","confirmUnlist"});
        return mutations.run("goods.save:"+id,body,actor.store(),()->{
            var previous=id==null?null:raw(id,actor.store());String target=id==null?db.id():id;
            if(previous!=null){Stage1Exception.require(number(previous,"version")==f.version(),409,"VERSION_CONFLICT");Stage1Exception.require(!"SUBMITTED".equals(id(previous,"review_status")),409,"GOODS_REVIEW_PENDING");}
            var payload=rules.validate(f.child("payload",true),target,actor.store(),actor.id(),false,access.storePermissions(actor.user()).contains("cost.write"));
            if(previous!=null&&Objects.equals(hash(payload),id(previous,"content_hash")))return detail(target);
            if(previous!=null&&"UPPER".equals(id(previous,"market_enable")))Stage1Exception.require(f.bool("confirmUnlist",false),409,"CONFIRM_UNLIST_BEFORE_EDIT");
            if(previous==null){db.update("INSERT INTO li_p0_goods_control(goods_id,store_id,review_status,methods,created_at) VALUES(?,?,'DRAFT','[]',?)",target,actor.store(),clock.instant());}
            payload=nativeGoods.project(target,actor.store(),actor.id(),f.requestId(),payload);var p=new Fields(payload);long contentVersion=previous==null?0:number(previous,"content_version")+1,version=previous==null?0:number(previous,"version")+1;
            db.update("UPDATE li_p0_goods_control SET content_version=?,content_hash=?,review_status='DRAFT',methods=?,unit_id=?,category_id=?,version=? WHERE goods_id=? AND store_id=?",contentVersion,hash(payload),write(p.list("methods",0,4,false)),p.id("unitId",false),p.id("categoryId",false),version,target,actor.store());
            if(previous==null)db.update("INSERT INTO li_p0_goods_draft(goods_id,store_id,version,payload,file_refs,updated_at) VALUES(?,?,?,?,?,?)",target,actor.store(),contentVersion,write(payload),write(rules.files(payload)),clock.instant());
            else db.update("UPDATE li_p0_goods_draft SET version=?,payload=?,file_refs=?,archived=0,updated_at=? WHERE goods_id=?",contentVersion,write(payload),write(rules.files(payload)),clock.instant(),target);
            mutations.audit("goods.save",actor.store(),target,f.requestId(),previous==null?null:number(previous,"version"),version,null);mutations.invalidated(actor.store(),target,"GOODS_CONTENT",contentVersion);return detail(target);
        });
    }
    public Object merchantCommand(String id,Object body,String action){
        var actor=context.require(SessionDomain.STORE);var f=new Fields(body).only("requestId","expectedVersion","reason");String reason=f.text("reason",1,500,false);
        return mutations.run("goods."+action+":"+id,body,actor.store(),()->{
            var row=raw(id,actor.store());Stage1Exception.require(number(row,"version")==f.version(),409,"VERSION_CONFLICT");long content=number(row,"content_version");
            if(action.equals("submit")){
                Stage1Exception.require(Set.of("DRAFT","REJECTED").contains(id(row,"review_status")),409,"GOODS_NOT_SUBMITTABLE");var payload=complete(row);eligibility.requireBasic(actor.store(),id(row,"category_id"));fulfillment.requireConfigured(actor.store(),methods(row));
                db.update("INSERT INTO li_p0_goods_review(goods_id,content_version,payload,content_hash,submitted_at) VALUES(?,?,?,?,?)",id,content,write(map("content",payload,"skus",skuViews(id,true))),hash(payload),clock.instant());
                db.update("UPDATE li_p0_goods_control SET review_status='SUBMITTED',submitted_at=?,version=version+1 WHERE goods_id=?",clock.instant(),id);db.update("UPDATE li_p0_sku_control SET submitted_once=1 WHERE goods_id=?",id);nativeGoods.status(id,actor.store(),"DOWN","TOBEAUDITED");
            }else if(action.equals("list")){
                Stage1Exception.require("APPROVED".equals(id(row,"review_status"))&&row.get("approved_version")!=null&&number(row,"approved_version")==content&&!flag(row,"forced_off"),409,"GOODS_NOT_APPROVED_OR_RESTRICTED");complete(row);eligibility.requireBasic(actor.store(),id(row,"category_id"));fulfillment.requireConfigured(actor.store(),methods(row));nativeGoods.status(id,actor.store(),"UPPER",null);db.update("UPDATE li_p0_goods_control SET ever_listed=1,version=version+1 WHERE goods_id=?",id);
            }else if(action.equals("unlist")){if(row.get("native_id")!=null)nativeGoods.status(id,actor.store(),"DOWN",null);db.update("UPDATE li_p0_goods_control SET version=version+1 WHERE goods_id=?",id);}
            else throw new Stage1Exception(404,"RESOURCE_NOT_FOUND");mutations.audit("goods."+action,actor.store(),id,f.requestId(),f.version(),f.version()+1,reason);mutations.invalidated(actor.store(),id,"GOODS_STATE",f.version()+1);return detail(id);
        });
    }
    public Object managerCommand(String id,Object body,String action){
        var actor=context.require(SessionDomain.MANAGER);var f=new Fields(body).only("requestId","expectedVersion","contentVersion","reason");var initial=raw(id,null);String store=id(initial,"store_id"),reason=f.text("reason",1,500,!action.equals("approve"));
        return mutations.run("goods."+action+":"+id,body,store,()->{
            var row=raw(id,store);Stage1Exception.require(number(row,"version")==f.version(),409,"VERSION_CONFLICT");
            if(Set.of("approve","reject").contains(action)){
                long content=f.integer("contentVersion",0,Long.MAX_VALUE,true);Stage1Exception.require("SUBMITTED".equals(id(row,"review_status"))&&content==number(row,"content_version"),409,"REVIEW_VERSION_CONFLICT");
                if(action.equals("approve")){complete(row);eligibility.requireBasic(store,id(row,"category_id"));fulfillment.requireConfigured(store,methods(row));}
                db.cas(db.update("UPDATE li_p0_goods_review SET decision=?,reviewer_id=?,reviewed_at=?,reason=? WHERE goods_id=? AND content_version=? AND decision IS NULL",action.equals("approve")?"APPROVED":"REJECTED",actor.id(),clock.instant(),reason,id,content));
                db.update("UPDATE li_p0_goods_control SET review_status=?,approved_version=?,version=version+1 WHERE goods_id=?",action.equals("approve")?"APPROVED":"REJECTED",action.equals("approve")?content:row.get("approved_version"),id);nativeGoods.status(id,store,"DOWN",action.equals("approve")?"PASS":"REFUSE");
            }else if(Set.of("force-off","release").contains(action)){
                db.update("UPDATE li_p0_goods_control SET forced_off=?,forced_reason=?,version=version+1 WHERE goods_id=?",action.equals("force-off"),reason,id);if(row.get("native_id")!=null)nativeGoods.status(id,store,"DOWN",null);
            }else throw new Stage1Exception(404,"RESOURCE_NOT_FOUND");mutations.audit("goods."+action,store,id,f.requestId(),f.version(),f.version()+1,reason);mutations.invalidated(store,id,"GOODS_STATE",f.version()+1);return detail(id);
        });
    }
    private JsonObject complete(Map<String,Object> row){
        var payload=object(db.required("SELECT payload FROM li_p0_goods_draft WHERE goods_id=?",row.get("goods_id")).get("payload"));var actor=context.current();rules.validate(payload,id(row,"goods_id"),id(row,"store_id"),actor.id(),true,false);
        var enabled=db.rows("SELECT s.*,c.cost_provided,c.weight_provided FROM li_goods_sku s JOIN li_p0_sku_control c ON c.sku_id=s.id WHERE c.goods_id=? AND c.enabled=1 AND s.delete_flag=0",row.get("goods_id"));Stage1Exception.require(!enabled.isEmpty()&&enabled.size()<=100,422,"ENABLED_SKU_REQUIRED");
        for(var sku:enabled){Stage1Exception.require(flag(sku,"cost_provided")&&sku.get("cost")!=null,422,"SKU_COST_REQUIRED");Stage1Exception.require(flag(sku,"weight_provided")&&sku.get("weight")!=null&&sku.get("price")!=null&&cents(sku.get("price"))>0&&sku.get("sn")!=null&&sku.get("quantity")!=null,422,"SKU_FIELDS_REQUIRED");}
        return payload;
    }
    public Map<String,Object> raw(String id,String store){return db.required("SELECT c.*,g.id AS native_id,g.goods_name,g.market_enable,g.auth_flag,g.price,g.quantity,d.payload,d.updated_at FROM li_p0_goods_control c JOIN li_p0_goods_draft d ON d.goods_id=c.goods_id LEFT JOIN li_goods g ON g.id=c.goods_id WHERE c.goods_id=? AND (? IS NULL OR c.store_id=?)",id,store,store);}
    public Object detail(String id){var actor=context.current();String store=actor.domain()==SessionDomain.STORE?actor.store():null;Stage1Exception.require(store!=null||actor.domain()==SessionDomain.MANAGER,403,"FORBIDDEN");var row=raw(id,store);boolean cost=canCost();return map("goods",view(row),"payload",object(row.get("payload")),"skus",skuViews(id,cost),"reviews",db.rows("SELECT * FROM li_p0_goods_review WHERE goods_id=? ORDER BY content_version DESC",id).stream().map(r->{var snapshot=object(r.get("payload"));if(!cost)for(var item:snapshot.getAsJsonArray("skus"))item.getAsJsonObject().remove("costCent");return map("contentVersion",r.get("content_version"),"submitted",snapshot,"decision",r.get("decision"),"reason",r.get("reason"),"submittedAt",instant(r.get("submitted_at")),"reviewedAt",instant(r.get("reviewed_at")));}).toList(),"regulationHistory",db.rows("SELECT action,reason,created_at,before_version,after_version FROM li_p0_audit WHERE object_id=? AND action IN ('goods.force-off','goods.release') ORDER BY created_at DESC,id DESC",id).stream().map(r->map("action",r.get("action"),"reason",r.get("reason"),"at",instant(r.get("created_at")),"beforeVersion",r.get("before_version"),"afterVersion",r.get("after_version"))).toList());}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Object list(String store,String status,String search,Boolean forced,int page,int size){return list(store,status,search,forced,null,null,page,size);}
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Object list(String store,String status,String search,Boolean forced,String marketStatus,String stockState,int page,int size){
        return list(store,status,search,forced,marketStatus,stockState,null,page,size);
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public Object list(String store,String status,String search,Boolean forced,String marketStatus,String stockState,String saleability,int page,int size){
        var actor=context.current();if(actor.domain()==SessionDomain.STORE)store=actor.store();else context.require(SessionDomain.MANAGER);AgreementService.page(page,size);
        if(status!=null)Stage1Exception.require(Set.of("DRAFT","SUBMITTED","APPROVED","REJECTED").contains(status),422,"GOODS_STATUS_INVALID");
        if(marketStatus!=null)Stage1Exception.require(Set.of("UPPER","DOWN").contains(marketStatus),422,"GOODS_MARKET_STATUS_INVALID");
        if(stockState!=null)Stage1Exception.require(Set.of("IN_STOCK","OUT_OF_STOCK","INCOMPLETE").contains(stockState),422,"GOODS_STOCK_STATE_INVALID");
        if(saleability!=null)Stage1Exception.require(Set.of("SALEABLE","UNSALEABLE").contains(saleability),422,"GOODS_SALEABILITY_INVALID");
        if(search!=null)Stage1Exception.require(search.length()<=60,422,"SEARCH_TOO_LONG");
        // An incomplete draft is not out of stock. All list and workbench projections use
        // enabled native SKU facts and the current controlled mode, inside one read snapshot.
        String where=" FROM li_p0_goods_control c JOIN li_p0_goods_draft d ON d.goods_id=c.goods_id LEFT JOIN li_goods g ON g.id=c.goods_id WHERE (? IS NULL OR c.store_id=?) AND (? IS NULL OR c.review_status=?) AND (? IS NULL OR c.forced_off=?) AND (? IS NULL OR COALESCE(g.market_enable,'DOWN')=?) AND (? IS NULL OR JSON_UNQUOTE(JSON_EXTRACT(d.payload,'$.name')) LIKE ?)";
        var params=new ArrayList<Object>(Arrays.asList(store,store,status,status,forced,forced,marketStatus,marketStatus,search,search==null?null:"%"+search.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%"));
        if(stockState!=null)where+=" AND "+switch(stockState){case "IN_STOCK"->HAS_STOCK;case "OUT_OF_STOCK"->"("+HAS_SKU+" AND NOT "+HAS_STOCK+" AND NOT "+INVALID_STOCK+")";default->"(NOT "+HAS_STOCK+" AND (NOT "+HAS_SKU+" OR "+INVALID_STOCK+"))";};
        var snapshot=new SupplySnapshot();
        if(saleability!=null){var all=db.rows(SUPPLY_SELECT+where+" ORDER BY d.updated_at DESC,c.goods_id DESC",params.toArray()).stream().map(r->view(r,snapshot)).filter(r->Boolean.TRUE.equals(r.get("currentSaleable"))==saleability.equals("SALEABLE")).toList();return map("total",all.size(),"records",all.stream().skip((long)(page-1)*size).limit(size).toList());}
        long count=db.count("SELECT COUNT(*)"+where,params.toArray());params.add(size);params.add((page-1)*size);
        var rows=db.rows(SUPPLY_SELECT+where+" ORDER BY d.updated_at DESC,c.goods_id DESC LIMIT ? OFFSET ?",params.toArray());
        return map("total",count,"records",rows.stream().map(r->view(r,snapshot)).toList());
    }
    public List<Object> skuViews(String id,boolean cost){return db.rows("SELECT s.*,c.enabled,c.inventory_version,c.cost_version,c.cost_provided,c.weight_provided,c.submitted_once FROM li_goods_sku s JOIN li_p0_sku_control c ON c.sku_id=s.id WHERE c.goods_id=? ORDER BY s.id",id).stream().map(r->{var result=map("id",id(r,"id"),"code",r.get("sn"),"specifications",r.get("specs")==null?List.of():array(r.get("specs")),"priceCent",r.get("price")==null?null:cents(r.get("price")),"quantity",r.get("quantity"),"inventoryVersion",r.get("inventory_version"),"weightGram",r.get("weight")==null?null:new java.math.BigDecimal(String.valueOf(r.get("weight"))).movePointRight(3).longValueExact(),"enabled",flag(r,"enabled"),"submittedOnce",flag(r,"submitted_once"),"image",r.get("original"));if(cost)result.putAll(map("costCent",r.get("cost")==null?null:cents(r.get("cost")),"costVersion",r.get("cost_version")));return (Object)result;}).toList();}
    private static final String SKU_FROM=" FROM li_goods_sku s JOIN li_p0_sku_control sc ON sc.sku_id=s.id WHERE sc.goods_id=c.goods_id AND sc.enabled=1 AND s.delete_flag=0";
    private static final String HAS_SKU="EXISTS(SELECT 1"+SKU_FROM+")",HAS_STOCK="EXISTS(SELECT 1"+SKU_FROM+" AND s.quantity>0)",INVALID_STOCK="EXISTS(SELECT 1"+SKU_FROM+" AND (s.quantity IS NULL OR s.quantity<0))";
    private static final String SUPPLY_SELECT="SELECT c.*,g.id AS native_id,g.goods_name,g.market_enable,g.auth_flag,g.price,g.quantity,d.payload,d.updated_at,"+HAS_SKU+" AS has_sku,"+HAS_STOCK+" AS has_stock,"+INVALID_STOCK+" AS invalid_stock";
    private static final class SupplySnapshot {final Map<String,Map<String,Object>> eligibility=new HashMap<>();final Map<String,Set<String>> methods=new HashMap<>();}
    private Map<String,Object> view(Map<String,Object> row){return view(row,new SupplySnapshot());}
    private Map<String,Object> view(Map<String,Object> row,SupplySnapshot snapshot){
        String id=id(row,"goods_id"),store=id(row,"store_id"),category=id(row,"category_id");
        var state=snapshot.eligibility.computeIfAbsent(store+":"+category,key->eligibility.evaluate(store,category,null));
        Set<String> effective=new TreeSet<>(methods(row));effective.retainAll(snapshot.methods.computeIfAbsent(store,key->fulfillment.effective(store).keySet()));
        var stock=row.containsKey("has_sku")?row:db.required("SELECT "+HAS_SKU+" AS has_sku,"+HAS_STOCK+" AS has_stock,"+INVALID_STOCK+" AS invalid_stock FROM li_p0_goods_control c WHERE c.goods_id=?",id);
        String stockState=flag(stock,"has_stock")?"IN_STOCK":flag(stock,"has_sku")&&!flag(stock,"invalid_stock")?"OUT_OF_STOCK":"INCOMPLETE";
        List<String> reasons=new ArrayList<>();
        if(!"APPROVED".equals(id(row,"review_status"))||row.get("approved_version")==null||number(row,"approved_version")!=number(row,"content_version")||!"PASS".equals(id(row,"auth_flag")))reasons.add("CONTENT_NOT_APPROVED");
        if(!"UPPER".equals(id(row,"market_enable")))reasons.add("GOODS_UNLISTED");if(flag(row,"forced_off"))reasons.add("GOODS_RESTRICTED");if(effective.isEmpty())reasons.add("NO_EFFECTIVE_METHOD");
        if(!stockState.equals("IN_STOCK"))reasons.add(stockState.equals("INCOMPLETE")?"SKU_INCOMPLETE":"OUT_OF_STOCK");
        boolean mock=reasons.isEmpty()&&Boolean.TRUE.equals(state.get("MOCK_READY")),real=reasons.isEmpty()&&Boolean.TRUE.equals(state.get("REAL_READY"));String mode=String.valueOf(state.get("authorizedMode"));
        for(var reason:object(state.get("reasons")).getAsJsonArray(mode))reasons.add(reason.getAsJsonObject().get("code").getAsString());
        return map("id",id,"storeId",store,"name",new Fields(row.get("payload")).text("name",2,60,false),"galleryFileIds",new Fields(row.get("payload")).ids("galleryFileIds",0,9,false),"version",row.get("version"),"contentVersion",row.get("content_version"),"approvedVersion",row.get("approved_version"),"reviewStatus",row.get("review_status"),"marketStatus",row.get("market_enable")==null?"DOWN":row.get("market_enable"),"forcedOff",flag(row,"forced_off"),"forcedReason",row.get("forced_reason"),"categoryId",category,"unitId",id(row,"unit_id"),"methods",methods(row),"effectiveMethods",effective,"stockState",stockState,"quantity",row.get("quantity"),"priceCent",row.get("price")==null?null:cents(row.get("price")),"basicEligible",state.get("BASIC_ELIGIBLE"),"mockSaleable",mock,"realSaleable",real,"currentSaleable",mode.equals("MOCK")?mock:real,"saleabilityReasons",reasons.stream().distinct().toList(),"eligibility",state,"updatedAt",instant(row.get("updated_at")));
    }
    public Map<String,Object> counts(String store){
        var actor=context.require(SessionDomain.STORE);Stage1Exception.require(actor.store().equals(store),404,"RESOURCE_NOT_FOUND");var snapshot=new SupplySnapshot();
        var rows=db.rows(SUPPLY_SELECT+" FROM li_p0_goods_control c JOIN li_p0_goods_draft d ON d.goods_id=c.goods_id LEFT JOIN li_goods g ON g.id=c.goods_id WHERE c.store_id=?",store).stream().map(r->view(r,snapshot)).toList();
        var counts=new LinkedHashMap<String,Object>();counts.put("total",rows.size());
        for(var state:Map.of("draft","DRAFT","pending","SUBMITTED","rejected","REJECTED").entrySet())counts.put(state.getKey(),rows.stream().filter(r->state.getValue().equals(r.get("reviewStatus"))).count());
        counts.put("restricted",rows.stream().filter(r->Boolean.TRUE.equals(r.get("forcedOff"))).count());counts.put("logicallyListed",rows.stream().filter(r->"UPPER".equals(r.get("marketStatus"))).count());counts.put("unlisted",rows.stream().filter(r->"DOWN".equals(r.get("marketStatus"))).count());
        counts.put("outOfStock",rows.stream().filter(r->"OUT_OF_STOCK".equals(r.get("stockState"))).count());counts.put("incomplete",rows.stream().filter(r->"INCOMPLETE".equals(r.get("stockState"))).count());long saleable=rows.stream().filter(r->Boolean.TRUE.equals(r.get("currentSaleable"))).count();counts.put("saleable",saleable);counts.put("unavailable",rows.size()-saleable);return counts;
    }
    public static List<String> methods(Map<String,Object> row){return array(row.get("methods")).asList().stream().map(JsonElement::getAsString).toList();}
    private boolean canCost(){var actor=context.current();return actor.domain()==SessionDomain.STORE?access.storePermissions(actor.user()).contains("cost.read"):access.managerPermissions(actor.id()).contains("goods.review");}
}
