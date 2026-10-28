package cn.lili.modules.community.p0.catalog;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Typed text/image blocks, native SKU combinations and exact integer units. No arbitrary HTML or URL input. */
@Component
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
public class GoodsContentRules {
    private final Stage1Db db;private final Stage1Files files;private final CatalogService catalog;
    public GoodsContentRules(Stage1Db db,Stage1Files files,CatalogService catalog){this.db=db;this.files=files;this.catalog=catalog;}
    public JsonObject validate(Object body,String goods,String store,String actor,boolean complete,boolean costWrite){
        var f=new Fields(body).only("name","subtitle","categoryId","unitId","brandId","galleryFileIds","detailBlocks","methods","skus","note");f.text("name",2,60,complete);f.text("subtitle",0,120,false);f.text("note",0,500,false);
        String category=f.id("categoryId",complete),unit=f.id("unitId",complete),brand=f.id("brandId",false);if(category!=null)catalog.forProduct(category,store);
        if(unit!=null){var u=db.required("SELECT c.enabled FROM li_goods_unit u JOIN li_p0_catalog_control c ON c.object_id=u.id AND c.object_type='UNIT' WHERE u.id=? AND u.delete_flag=0",unit);boolean existing=goods!=null&&db.count("SELECT COUNT(*) FROM li_p0_goods_control WHERE goods_id=? AND unit_id=?",goods,unit)>0;Stage1Exception.require(flag(u,"enabled")||existing,422,"UNIT_DISABLED");}
        if(brand!=null)db.required("SELECT id FROM li_brand WHERE id=? AND delete_flag=0",brand);
        Set<String> refs=new LinkedHashSet<>(f.ids("galleryFileIds",complete?1:0,9,complete));var blocks=f.list("detailBlocks",complete?1:0,200,complete);int textLength=0;
        for(var item:blocks){var block=new Fields(item);String type=block.choice("type",Set.of("text","image"),true);if(type.equals("text")){block.only("type","text");String text=block.text("text",1,10000,true);textLength+=text.codePointCount(0,text.length());checkSensitive(text);}else{block.only("type","fileId");refs.add(block.id("fileId",true));}}
        Stage1Exception.require(textLength<=10000,422,"DETAIL_TOO_LONG");if(complete)Stage1Exception.require(!blocks.isEmpty(),422,"DETAIL_REQUIRED");if(f.has("name"))checkSensitive(f.text("name",2,60,true));if(f.has("subtitle"))checkSensitive(f.text("subtitle",0,120,false));
        Set<String> methods=new TreeSet<>();for(var item:f.list("methods",complete?1:0,4,complete)){String method=new Fields(map("method",item)).choice("method",Stage1Rules.METHODS,true);Stage1Exception.require(methods.add(method),422,"METHOD_DUPLICATE");}
        var skus=f.list("skus",complete?1:0,100,complete);Set<String> keys=new HashSet<>(),codes=new HashSet<>(),ids=new HashSet<>();Set<String> dimensionNames=null;JsonArray canonical=new JsonArray();
        for(var item:skus){var s=new Fields(item).only("id","code","specifications","priceCent","costCent","initialQuantity","weightGram","imageFileId","enabled");boolean required=complete&&s.bool("enabled",true);String id=s.id("id",false),code=s.text("code",1,64,required);var specs=specifications(s.list("specifications",0,3,complete));String specKey=specKey(specs);Stage1Exception.require(keys.add(specKey),422,"SKU_COMBINATION_DUPLICATE");
            Set<String> dimensions=new TreeSet<>();for(var spec:specs)dimensions.add(CatalogService.normalized(new Fields(spec).text("name",1,20,true)));if(dimensionNames==null)dimensionNames=dimensions;else Stage1Exception.require(dimensionNames.equals(dimensions),422,"SKU_DIMENSIONS_INCONSISTENT");
            if(code!=null){code=Stage1Rules.skuCode(code);Stage1Exception.require(codes.add(code),422,"SKU_CODE_DUPLICATE");}
            if(id!=null){Stage1Exception.require(ids.add(id),422,"SKU_ID_DUPLICATE");var existing=db.required("SELECT * FROM li_p0_sku_control WHERE sku_id=? AND goods_id=? AND store_id=?",id,goods,store);if(flag(existing,"submitted_once")||flag(existing,"historical_reference")||db.count("SELECT COUNT(*) FROM li_p0_inventory_adjustment WHERE sku_id=?",id)>0)Stage1Exception.require(specKey.equals(id(existing,"spec_key")),409,"SKU_SPECIFICATION_IMMUTABLE");if(flag(existing,"submitted_once"))Stage1Exception.require(Objects.equals(code,id(existing,"normalized_code")),409,"SKU_CODE_IMMUTABLE");Stage1Exception.require(!s.has("initialQuantity")&&!s.has("costCent"),422,"USE_INDEPENDENT_STOCK_OR_COST_COMMAND");}
            else{if(s.has("costCent"))Stage1Exception.require(costWrite,403,"COST_WRITE_FORBIDDEN");s.integer("initialQuantity",0,9999999,false);s.integer("costCent",0,99999999,false);}
            s.integer("priceCent",1,99999999,required);Long weight=s.integer("weightGram",0,99999999,required);if(required&&methods.contains("EXPRESS"))Stage1Exception.require(weight!=null&&weight>0,422,"EXPRESS_WEIGHT_REQUIRED");String image=s.id("imageFileId",false);if(image!=null)refs.add(image);s.bool("enabled",true);
            var normalized=s.json();normalized.add("specifications",specs);if(code!=null)normalized.addProperty("code",code);canonical.add(normalized);
        }
        for(String file:refs)files.validate(List.of(file),"GOODS",actor,store);
        var result=f.json();if(f.has("skus"))result.add("skus",canonical);return result;
    }
    public static JsonArray specifications(JsonArray input){var result=new JsonArray();Set<String> names=new HashSet<>();for(var item:input){var f=new Fields(item).only("name","value");String name=f.text("name",1,20,true),value=f.text("value",1,30,true);Stage1Exception.require(names.add(CatalogService.normalized(name)),422,"SKU_DIMENSION_DUPLICATE");result.add(object(map("name",name,"value",value)));}return result;}
    public static String specKey(JsonArray specs){Map<String,String> values=new TreeMap<>();for(var item:specs){var f=new Fields(item);values.put(CatalogService.normalized(f.text("name",1,20,true)),CatalogService.normalized(f.text("value",1,30,true)));}return hash(values);}
    public Set<String> files(JsonObject payload){var f=new Fields(payload);Set<String> refs=new LinkedHashSet<>(f.ids("galleryFileIds",0,9,false));for(var item:f.list("detailBlocks",0,200,false)){var block=new Fields(item);if(block.has("fileId"))refs.add(block.id("fileId",true));}for(var item:f.list("skus",0,100,false)){var s=new Fields(item);if(s.has("imageFileId"))refs.add(s.id("imageFileId",true));}return refs;}
    public String html(JsonObject payload){StringBuilder html=new StringBuilder();for(var item:new Fields(payload).list("detailBlocks",0,200,false)){var block=new Fields(item);if("text".equals(block.text("type",1,10,true)))html.append("<p>").append(org.springframework.web.util.HtmlUtils.htmlEscape(block.text("text",1,10000,true)).replace("\n","<br>" )).append("</p>");else html.append("<img src=\"stage1-file:").append(block.id("fileId",true)).append("\" alt=\"商品图片\">");}return html.toString();}
    private void checkSensitive(String value){for(var row:db.rows("SELECT sensitive_word FROM li_sensitive_words WHERE delete_flag=0")){String word=id(row,"sensitive_word");if(word!=null&&!word.isBlank()&&value.contains(word))throw new Stage1Exception(422,"CONTENT_SENSITIVE_WORD");}}
}
