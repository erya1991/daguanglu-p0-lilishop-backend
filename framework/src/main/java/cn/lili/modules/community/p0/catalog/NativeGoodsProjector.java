package cn.lili.modules.community.p0.catalog;
import cn.lili.modules.community.p0.support.*;
import cn.lili.modules.goods.entity.dos.*;
import cn.lili.modules.goods.service.*;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.google.gson.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Reuses LiLiShop's native services and tables. Does not call legacy SKU regeneration/cache-to-DB stock flows. */
@Component
@cn.lili.modules.community.p0.support.P0BusinessEnabled
public class NativeGoodsProjector {
    private final Stage1Db db;private final GoodsService goods;private final GoodsSkuService skus;private final GoodsContentRules rules;private final CatalogService catalog;private final Stage1Files files;private final Stage1Settings settings;private final Clock clock;
    public NativeGoodsProjector(Stage1Db db,GoodsService goods,GoodsSkuService skus,GoodsContentRules rules,CatalogService catalog,Stage1Files files,Stage1Settings settings,@Qualifier("stage1Clock") Clock clock){this.db=db;this.goods=goods;this.skus=skus;this.rules=rules;this.catalog=catalog;this.files=files;this.settings=settings;this.clock=clock;}
    public JsonObject project(String id,String store,String actor,String request,JsonObject content){
        var f=new Fields(content);var existing=db.one("SELECT id FROM li_goods WHERE id=?",id);
        // Empty drafts retain only their preallocated ID. Native records appear when content is actually supplied.
        if(existing==null&&!f.has("name")&&f.list("skus",0,100,false).isEmpty())return content;
        var owner=db.required("SELECT store_name,self_operated FROM li_store WHERE id=?",store);
        if(existing==null){var entity=new Goods();entity.setId(id);entity.setStoreId(store);entity.setStoreName(id(owner,"store_name"));entity.setDeleteFlag(false);entity.setMarketEnable("DOWN");entity.setAuthFlag("TOBEAUDITED");entity.setGoodsType("PHYSICAL_GOODS");entity.setSalesModel("RETAIL");entity.setRecommend(false);entity.setSelfOperated(flag(owner,"self_operated"));entity.setCreateBy(actor);entity.setCreateTime(Date.from(clock.instant()));Stage1Exception.require(goods.save(entity),503,"GOODS_SAVE_FAILED");}
        String category=f.id("categoryId",false),unit=f.id("unitId",false),path=category==null?null:String.join(",",catalog.lineage(category,false).stream().map(r->id(r,"id")).toList()),unitName=unit==null?null:id(db.required("SELECT name FROM li_goods_unit WHERE id=?",unit),"name");var gallery=f.ids("galleryFileIds",0,9,false);String image=gallery.isEmpty()?null:"stage1-file:"+gallery.getFirst();
        var common=map("goods_name",f.text("name",2,60,false),"selling_point",f.text("subtitle",0,120,false),"category_path",path,"goods_unit",unitName,"brand_id",f.id("brandId",false),"intro",rules.html(content),"mobile_intro",rules.html(content),"market_enable","DOWN","auth_flag","TOBEAUDITED","store_name",owner.get("store_name"),"update_by",actor,"update_time",clock.instant(),"original",image,"big",image,"small",image,"thumbnail",image);
        updateGoods(id,store,common);var normalized=content.deepCopy();JsonArray result=new JsonArray();Set<String> retained=new HashSet<>();
        for(var item:f.list("skus",0,100,false)){
            var s=new Fields(item);String sku=s.id("id",false);boolean fresh=sku==null;if(fresh)sku=db.id();retained.add(sku);var specification=s.list("specifications",0,3,false);String key=GoodsContentRules.specKey(specification);boolean enabled=s.bool("enabled",true);String code=s.text("code",1,64,false);
            if(fresh){var entity=new GoodsSku();entity.setId(sku);entity.setGoodsId(id);entity.setStoreId(store);entity.setDeleteFlag(false);entity.setRecommend(false);entity.setMarketEnable("DOWN");entity.setAuthFlag("TOBEAUDITED");entity.setGoodsType("PHYSICAL_GOODS");entity.setSalesModel("RETAIL");entity.setQuantity(0);entity.setCreateBy(actor);entity.setCreateTime(Date.from(clock.instant()));Stage1Exception.require(skus.save(entity),503,"SKU_SAVE_FAILED");db.update("INSERT INTO li_p0_sku_control(sku_id,goods_id,store_id,normalized_code,spec_key,enabled,source_run) VALUES(?,?,?,?,?,?,?)",sku,id,store,code,key,enabled,settings.getRunId());}
            else db.cas(db.update("UPDATE li_p0_sku_control SET normalized_code=?,spec_key=?,enabled=? WHERE sku_id=? AND goods_id=? AND store_id=?",code,key,enabled,sku,id,store));
            var nativeSpecs=new ArrayList<Object>();var descriptions=new ArrayList<String>();for(var spec:specification){var v=new Fields(spec);nativeSpecs.add(map("specName",v.text("name",1,20,true),"specValue",v.text("value",1,30,true),"specType",0));descriptions.add(v.text("name",1,20,true)+":"+v.text("value",1,30,true));}
            Long price=s.integer("priceCent",1,99999999,false),weight=s.integer("weightGram",0,99999999,false);var skuFields=new LinkedHashMap<>(common);skuFields.putAll(map("sn",code,"specs",write(nativeSpecs),"simple_specs",String.join(" ",descriptions),"price",price==null?null:money(price),"weight",weight==null?null:BigDecimal.valueOf(weight,3)));
            if(s.has("imageFileId")){String skuImage="stage1-file:"+s.id("imageFileId",true);for(String field:List.of("original","big","small","thumbnail"))skuFields.put(field,skuImage);}
            updateSku(sku,id,store,skuFields);db.update("UPDATE li_p0_sku_control SET weight_provided=? WHERE sku_id=?",weight!=null,sku);
            if(fresh&&s.has("costCent")){updateSku(sku,id,store,map("cost",money(s.integer("costCent",0,99999999,true))));db.update("UPDATE li_p0_sku_control SET cost_provided=1 WHERE sku_id=?",sku);}
            if(fresh&&s.has("initialQuantity")){long quantity=s.integer("initialQuantity",0,9999999,true);updateSku(sku,id,store,map("quantity",quantity));db.update("UPDATE li_p0_sku_control SET inventory_version=1 WHERE sku_id=?",sku);db.update("INSERT INTO li_p0_inventory_adjustment(id,sku_id,store_id,actor_id,request_id,before_quantity,after_quantity,delta_quantity,before_version,after_version,reason,created_at) VALUES(?,?,?,?,?,0,?,?,0,1,'INITIAL_SUPPLY',?)",db.id(),sku,store,actor,request,quantity,quantity,clock.instant());}
            var saved=s.json();saved.addProperty("id",sku);saved.remove("initialQuantity");saved.remove("costCent");result.add(saved);
        }
        for(var old:db.rows("SELECT sku_id FROM li_p0_sku_control WHERE goods_id=? AND store_id=?",id,store))if(!retained.contains(id(old,"sku_id"))){db.update("UPDATE li_p0_sku_control SET enabled=0 WHERE sku_id=?",old.get("sku_id"));updateSku(id(old,"sku_id"),id,store,map("market_enable","DOWN"));}
        if(f.has("skus"))normalized.add("skus",result);
        db.update("DELETE FROM li_goods_gallery WHERE goods_id=?",id);for(int i=0;i<gallery.size();i++){String url="stage1-file:"+gallery.get(i);db.nativeInsert("li_goods_gallery",map("id",db.id(),"create_by",actor,"goods_id",id,"is_default",i==0?1:0,"original",url,"small",url,"thumbnail",url,"sort",i));}
        for(String file:rules.files(normalized))files.bind(List.of(file),"GOODS",actor,store,id);summary(id,store);return normalized;
    }
    public void summary(String id,String store){var row=db.required("SELECT COALESCE(SUM(s.quantity),0) AS quantity,MIN(s.price) AS price,MIN(s.cost) AS cost,MIN(s.weight) AS weight FROM li_goods_sku s JOIN li_p0_sku_control c ON c.sku_id=s.id WHERE c.goods_id=? AND c.enabled=1 AND s.delete_flag=0",id);updateGoods(id,store,row);}
    public void status(String id,String store,String market,String auth){var fields=new LinkedHashMap<String,Object>();if(market!=null)fields.put("market_enable",market);if(auth!=null)fields.put("auth_flag",auth);updateGoods(id,store,fields);for(var row:db.rows("SELECT sku_id,enabled FROM li_p0_sku_control WHERE goods_id=? AND store_id=?",id,store)){var values=new LinkedHashMap<>(fields);if(market!=null&&!flag(row,"enabled"))values.put("market_enable","DOWN");updateSku(id(row,"sku_id"),id,store,values);}}
    public void updateGoods(String id,String store,Map<String,Object> values){var wrapper=new UpdateWrapper<Goods>().eq("id",id).eq("store_id",store);values.forEach((key,value)->wrapper.set(key,value instanceof java.time.Instant instant?Date.from(instant):value));Stage1Exception.require(goods.update(wrapper),503,"GOODS_SAVE_FAILED");}
    public void updateSku(String id,String goods,String store,Map<String,Object> values){var wrapper=new UpdateWrapper<GoodsSku>().eq("id",id).eq("goods_id",goods).eq("store_id",store);values.forEach((key,value)->wrapper.set(key,value instanceof java.time.Instant instant?Date.from(instant):value));Stage1Exception.require(skus.update(wrapper),503,"SKU_SAVE_FAILED");}
}
