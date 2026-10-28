package cn.lili.modules.community.p0.catalog;
import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.JsonArray;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.text.Normalizer;
import java.time.Clock;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
public class CatalogService {
    private final Stage1Db db;private final Stage1Context context;private final Stage1Mutations mutations;private final Clock clock;
    public CatalogService(Stage1Db db,Stage1Context context,Stage1Mutations mutations,@Qualifier("stage1Clock") Clock clock){this.db=db;this.context=context;this.mutations=mutations;this.clock=clock;}
    public static String normalized(String value){return Normalizer.normalize(value.trim(),Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);}
    public List<Map<String,Object>> lineage(String category,boolean enabled){
        var result=new ArrayList<Map<String,Object>>();Set<String> visited=new HashSet<>();String current=category;
        while(current!=null&&!current.equals("0")){
            Stage1Exception.require(visited.add(current)&&visited.size()<=3,409,"CATEGORY_TREE_INVALID");var row=db.required("SELECT n.*,c.version,c.enabled FROM li_category n JOIN li_p0_catalog_control c ON c.object_type='CATEGORY' AND c.object_id=n.id WHERE n.id=? AND n.delete_flag=0",current);
            if(enabled)Stage1Exception.require(flag(row,"enabled"),422,"CATEGORY_UNAVAILABLE");result.add(row);current=id(row,"parent_id");
        }Collections.reverse(result);return result;
    }
    public List<Map<String,Object>> forProduct(String category,String store){
        var path=lineage(category,true);Stage1Exception.require(!path.isEmpty()&&db.count("SELECT COUNT(*) FROM li_category WHERE parent_id=? AND delete_flag=0",category)==0,422,"LEAF_CATEGORY_REQUIRED");
        var allowed=array(db.required("SELECT category_ids FROM li_p0_store_control WHERE store_id=? AND admission_state='APPROVED'",store).get("category_ids"));Set<String> ids=new HashSet<>();allowed.forEach(e->ids.add(e.getAsString()));Stage1Exception.require(path.stream().anyMatch(r->ids.contains(id(r,"id"))),422,"CATEGORY_OUTSIDE_STORE_SCOPE");return path;
    }
    public Object save(String type,String id,Object body){
        context.require(SessionDomain.MANAGER);boolean category=type.equals("CATEGORY");var f=new Fields(body).only(category?new String[]{"requestId","expectedVersion","name","parentId","sort","qualificationTypes","reason"}:new String[]{"requestId","expectedVersion","name","reason"});String name=f.text("name",1,category?30:10,true),reason=f.text("reason",1,500,true);String table=category?"li_category":"li_goods_unit";
        return mutations.run("catalog.save:"+type+":"+id,body,null,()->{
            var old=id==null?null:raw(type,id);String parent=category&&f.has("parentId")&&!"0".equals(f.text("parentId",1,19,false))?f.id("parentId",true):"0";int level=1;
            if(category&&!parent.equals("0")){var ancestors=lineage(parent,false);Stage1Exception.require(ancestors.size()<3&&ancestors.stream().noneMatch(r->Objects.equals(id,id(r,"id"))),422,"CATEGORY_TREE_INVALID");level=ancestors.size()+1;}
            long version=id==null?0:f.version();if(old!=null){Stage1Exception.require(number(old,"version")==version,409,"VERSION_CONFLICT");if(category&&!parent.equals(id(old,"parent_id"))){Stage1Exception.require(db.count("SELECT COUNT(*) FROM li_p0_goods_control WHERE category_id=?",id)==0&&db.count("SELECT COUNT(*) FROM li_category WHERE parent_id=? AND delete_flag=0",id)==0,409,"CATEGORY_REFERENCED_OR_HAS_CHILDREN");}if(!category&&!name.equals(id(old,"name")))Stage1Exception.require(db.count("SELECT COUNT(*) FROM li_p0_goods_control WHERE unit_id=?",id)==0,409,"UNIT_REFERENCED_IMMUTABLE");}
            String target=id==null?db.id():id;long sort=category?(f.has("sort")?f.integer("sort",0,9999,true):0):0;
            if(id==null){var values=map("id",target,"name",name,"delete_flag",false,"create_by",context.current().id(),"create_time",clock.instant());if(category)values.putAll(map("parent_id",parent,"level",level,"sort_order",sort));db.nativeInsert(table,values);db.update("INSERT INTO li_p0_catalog_control(object_type,object_id,normalized_name,parent_id) VALUES(?,?,?,?)",type,target,normalized(name),parent);}
            else{db.cas(db.update("UPDATE li_p0_catalog_control SET normalized_name=?,parent_id=?,version=version+1 WHERE object_type=? AND object_id=? AND version=?",normalized(name),parent,type,id,version));if(category)db.update("UPDATE li_category SET name=?,parent_id=?,level=?,sort_order=?,update_by=?,update_time=? WHERE id=?",name,parent,level,sort,context.current().id(),clock.instant(),id);else db.update("UPDATE li_goods_unit SET name=?,update_by=?,update_time=? WHERE id=?",name,context.current().id(),clock.instant(),id);}
            if(category&&f.has("qualificationTypes")){Set<String> types=new HashSet<>();for(var entry:f.list("qualificationTypes",0,30,true)){String code=new Fields(map("code",entry)).text("code",1,64,true);Stage1Exception.require(types.add(code),422,"QUALIFICATION_DUPLICATE");db.required("SELECT code FROM li_p0_qualification_type WHERE code=? AND enabled=1 AND scope='CATEGORY'",code);}db.update("DELETE FROM li_p0_category_qualification WHERE category_id=?",target);for(String code:types)db.update("INSERT INTO li_p0_category_qualification(category_id,qualification_type,required,version) VALUES(?,?,1,?)",target,code,id==null?0:version+1);}
            history(type,target,reason);mutations.audit("catalog.save",null,target,f.requestId(),id==null?null:version,id==null?0:version+1,reason);mutations.invalidated(null,target,"CATALOG_POLICY",id==null?0:version+1);return view(type,raw(type,target));
        });
    }
    public Object command(String type,String id,Object body,boolean enabled){context.require(SessionDomain.MANAGER);var f=new Fields(body).only("requestId","expectedVersion","reason");String reason=f.text("reason",1,500,true);return mutations.run("catalog."+(enabled?"enable":"disable")+":"+type+":"+id,body,null,()->{var row=raw(type,id);if(enabled&&type.equals("CATEGORY")){var path=lineage(id,false);for(var parent:path)if(!id.equals(id(parent,"id")))Stage1Exception.require(flag(parent,"enabled"),422,"PARENT_CATEGORY_DISABLED");}db.cas(db.update("UPDATE li_p0_catalog_control SET enabled=?,version=version+1 WHERE object_type=? AND object_id=? AND version=?",enabled,type,id,f.version()));history(type,id,reason);mutations.audit("catalog."+(enabled?"enable":"disable"),null,id,f.requestId(),f.version(),f.version()+1,reason);mutations.invalidated(null,id,"CATALOG_POLICY",f.version()+1);return view(type,raw(type,id));});}
    private Map<String,Object> raw(String type,String id){String table=type.equals("CATEGORY")?"li_category":"li_goods_unit";return db.required("SELECT n.*,c.version,c.enabled,c.parent_id AS control_parent_id FROM "+table+" n JOIN li_p0_catalog_control c ON c.object_type=? AND c.object_id=n.id WHERE n.id=? AND n.delete_flag=0",type,id);}
    private void history(String type,String id,String reason){var row=raw(type,id);db.update("INSERT INTO li_p0_catalog_history(object_type,object_id,version,snapshot,actor_id,reason,created_at) VALUES(?,?,?,?,?,?,?)",type,id,number(row,"version"),write(view(type,row)),context.current().id(),reason,clock.instant());}
    public Object detail(String type,String id,int page,int size){
        context.require(SessionDomain.MANAGER);cn.lili.modules.community.p0.agreement.AgreementService.page(page,size);
        return map("item",view(type,raw(type,id)),"history",db.rows("SELECT version,snapshot,reason,created_at FROM li_p0_catalog_history WHERE object_type=? AND object_id=? ORDER BY version DESC",type,id).stream().map(r->map("version",r.get("version"),"snapshot",object(r.get("snapshot")),"reason",r.get("reason"),"at",instant(r.get("created_at")))).toList(),"impact",type.equals("CATEGORY")?impact(id,page,size):null);
    }
    /** Current references only; approved historical snapshots are never rewritten. */
    private Object impact(String category,int page,int size){
        var descendants=new TreeSet<String>();descendants.add(category);
        var tree=db.rows("SELECT id,parent_id FROM li_category WHERE delete_flag=0");
        for(int level=0;level<3;level++)for(var row:tree)if(descendants.contains(id(row,"parent_id")))descendants.add(id(row,"id"));
        var relevant=new TreeSet<>(descendants);lineage(category,false).forEach(row->relevant.add(id(row,"id")));
        String storeFrom=" FROM li_p0_store_control c JOIN li_store s ON s.id=c.store_id WHERE c.admission_state='APPROVED' AND JSON_OVERLAPS(c.category_ids,CAST(? AS JSON))";
        String goodsFrom=" FROM li_p0_goods_control c JOIN li_p0_goods_draft d ON d.goods_id=c.goods_id WHERE JSON_CONTAINS(CAST(? AS JSON),JSON_QUOTE(CAST(c.category_id AS CHAR)))";
        String stores=write(relevant),goods=write(descendants);int offset=(page-1)*size;
        return map("categoryId",category,"policyVersion",raw("CATEGORY",category).get("version"),"storeTotal",db.count("SELECT COUNT(*)"+storeFrom,stores),"goodsTotal",db.count("SELECT COUNT(*)"+goodsFrom,goods),"pageNumber",page,"pageSize",size,
            "stores",db.rows("SELECT CAST(c.store_id AS CHAR) AS storeId,s.store_name AS storeName,c.version"+storeFrom+" ORDER BY c.store_id LIMIT ? OFFSET ?",stores,size,offset),
            "goods",db.rows("SELECT CAST(c.goods_id AS CHAR) AS id,CAST(c.store_id AS CHAR) AS storeId,JSON_UNQUOTE(JSON_EXTRACT(d.payload,'$.name')) AS name,c.version,c.review_status AS reviewStatus"+goodsFrom+" ORDER BY c.goods_id LIMIT ? OFFSET ?",goods,size,offset));
    }
    public Object list(String type){var actor=context.current();Stage1Exception.require(actor.domain()==SessionDomain.MANAGER||actor.domain()==SessionDomain.STORE,403,"FORBIDDEN");String table=type.equals("CATEGORY")?"li_category":"li_goods_unit";return db.rows("SELECT n.*,c.version,c.enabled,c.parent_id AS control_parent_id FROM "+table+" n JOIN li_p0_catalog_control c ON c.object_type=? AND c.object_id=n.id WHERE n.delete_flag=0 ORDER BY "+(type.equals("CATEGORY")?"n.sort_order,":"")+"n.id",type).stream().filter(r->{if(actor.domain()==SessionDomain.MANAGER)return true;if(!flag(r,"enabled"))return false;if(type.equals("CATEGORY"))try{forProduct(id(r,"id"),actor.store());}catch(Stage1Exception denied){return false;}return true;}).map(r->view(type,r)).toList();}
    public Object qualificationTypes(){context.require(SessionDomain.MANAGER);return db.rows("SELECT code,scope,required_fields,enabled FROM li_p0_qualification_type ORDER BY code").stream().map(r->map("code",r.get("code"),"scope",r.get("scope"),"requiredFields",array(r.get("required_fields")),"enabled",flag(r,"enabled"))).toList();}
    public Object admissionOptions(){var actor=context.current();Stage1Exception.require(actor.domain()==SessionDomain.APPLICANT||actor.domain()==SessionDomain.STORE,403,"FORBIDDEN");return map("categories",db.rows("SELECT n.*,c.version,c.enabled FROM li_category n JOIN li_p0_catalog_control c ON c.object_type='CATEGORY' AND c.object_id=n.id WHERE n.delete_flag=0 AND c.enabled=1 ORDER BY n.sort_order,n.id").stream().filter(r->{try{lineage(id(r,"id"),true);return true;}catch(Stage1Exception unavailable){return false;}}).map(r->view("CATEGORY",r)).toList(),"qualificationTypes",db.rows("SELECT code,scope,required_fields FROM li_p0_qualification_type WHERE enabled=1 ORDER BY code").stream().map(r->map("code",r.get("code"),"scope",r.get("scope"),"requiredFields",array(r.get("required_fields")))).toList());}
    private Object view(String type,Map<String,Object> row){String id=id(row,"id");return map("id",id,"name",row.get("name"),"enabled",flag(row,"enabled"),"version",row.get("version"),"parentId",type.equals("CATEGORY")?id(row,"parent_id"):null,"level",row.get("level"),"sort",row.get("sort_order"),"qualificationTypes",type.equals("CATEGORY")?db.rows("SELECT qualification_type FROM li_p0_category_qualification WHERE category_id=? AND required=1 ORDER BY qualification_type",id).stream().map(r->r.get("qualification_type")).toList():List.of(),"referencedGoods",db.count("SELECT COUNT(*) FROM li_p0_goods_control WHERE "+(type.equals("CATEGORY")?"category_id":"unit_id")+"=?",id));}
}
