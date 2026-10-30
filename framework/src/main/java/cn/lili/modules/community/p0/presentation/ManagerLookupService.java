package cn.lili.modules.community.p0.presentation;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.support.*;
import cn.lili.modules.community.p0.agreement.AgreementService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static cn.lili.modules.community.p0.presentation.ManagerPresentationViews.*;

@Service
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
@org.springframework.context.annotation.Conditional(ManagerPresentationCondition.class)
@Transactional(readOnly=true)
public class ManagerLookupService {
    private final Stage1Db db;private final Stage1Context context;private final Stage1Access access;private final Stage1Settings settings;
    public ManagerLookupService(Stage1Db db,Stage1Context context,Stage1Access access,Stage1Settings settings){this.db=db;this.context=context;this.access=access;this.settings=settings;}
    public void require(String... actions){var actor=context.require(SessionDomain.MANAGER);var granted=access.managerPermissions(actor.id());Stage1Exception.require(Arrays.stream(actions).anyMatch(granted::contains),403,"FORBIDDEN");}
    public List<Map<String,Object>> categoryRows(){return db.rows("SELECT CAST(n.id AS CHAR) AS id,CAST(n.parent_id AS CHAR) AS parentId,n.name,n.level,n.sort_order AS sort,c.enabled,c.version FROM li_category n JOIN li_p0_catalog_control c ON c.object_type='CATEGORY' AND c.object_id=n.id WHERE n.delete_flag=0 ORDER BY n.sort_order,n.id");}
    public List<String> categoryIds(String category){identifier(category);var rows=categoryRows();var result=new LinkedHashSet<String>();result.add(category);for(int i=0;i<3;i++)for(var row:rows)if(result.contains(id(row,"parentId")))result.add(id(row,"id"));return List.copyOf(result);}
    public String categoryPath(String category,List<Map<String,Object>> rows){if(category==null)return null;var names=new ArrayList<String>();var seen=new HashSet<String>();String current=category;while(current!=null&&!current.equals("0")&&seen.add(current)&&seen.size()<=3){String target=current;var row=rows.stream().filter(r->target.equals(id(r,"id"))).findFirst().orElse(null);if(row==null){names.add("名称未提供（"+current+"）");break;}names.add(id(row,"name"));current=id(row,"parentId");}Collections.reverse(names);return String.join(" / ",names);}
    public Object options(String type,String q,String selected,String store,String config,String purpose,int page,int size){
        AgreementService.page(page,size);q=search(q);List<String> selectedIds=selected==null?List.of():Arrays.stream(selected.split(",",-1)).distinct().toList();Stage1Exception.require(selectedIds.size()<=100,422,"LOOKUP_LIMIT_EXCEEDED");selectedIds.forEach(ManagerPresentationViews::identifier);if(store!=null)identifier(store);if(config!=null)identifier(config);
        if(type.equals("categories")){
            if("catalog".equals(purpose))require("catalog.configure");
            else if("admission".equals(purpose))require("admission.review");
            else if("goods".equals(purpose))require("goods.read","goods.review");
            else if("cooperation".equals(purpose))require("agreement.read","rule.read");
            else if("eligibility".equals(purpose))require("store.read");
            else throw new Stage1Exception(422,"LOOKUP_PURPOSE_REQUIRED");
            var rows=categoryRows();Set<String> scope=null;if(store!=null){var row=db.required("SELECT category_ids FROM li_p0_store_control WHERE store_id=? AND admission_state='APPROVED'",store);scope=new HashSet<>();for(var e:array(row.get("category_ids")))scope.addAll(categoryIds(e.getAsString()));}
            List<Map<String,Object>> values=new ArrayList<>();for(var row:rows){String id=id(row,"id");if(scope!=null&&!scope.contains(id))continue;String name=id(row,"name"),path=categoryPath(id,rows);if(!selectedIds.isEmpty()&&!selectedIds.contains(id)||q!=null&&!path.toLowerCase(Locale.ROOT).contains(q.toLowerCase(Locale.ROOT)))continue;values.add(map("id",id,"name",name,"path",path,"parentId",id(row,"parentId"),"level",row.get("level"),"enabled",flag(row,"enabled"),"version",row.get("version"),"label",path));}return slice(values,page,size);
        }
        String from,columns,condition="",order="n.id DESC";List<Object> args=new ArrayList<>();
        switch(type){
            case "stores" -> {require("store.read");from=" FROM li_store n JOIN li_p0_store_control c ON c.store_id=n.id JOIN li_store_detail d ON d.store_id=n.id WHERE c.admission_state='APPROVED' AND n.delete_flag=0";columns="CAST(n.id AS CHAR) AS id,n.store_name AS name,n.store_disable AS status,c.version,d.company_name AS legalName";if(q!=null){condition+=" AND (n.store_name LIKE ? ESCAPE '!' OR d.company_name LIKE ? ESCAPE '!')";args.add(like(q));args.add(like(q));}}
            case "agreements" -> {require("agreement.read");Stage1Exception.require(store!=null,422,"STORE_REQUIRED");from=" FROM li_p0_agreement n WHERE n.store_id=?";args.add(store);columns="CAST(n.id AS CHAR) AS id,JSON_UNQUOTE(JSON_EXTRACT(n.terms,'$.name')) AS name,n.agreement_no AS number,n.revision,n.version,n.status,CAST(n.store_id AS CHAR) AS storeId,JSON_UNQUOTE(JSON_EXTRACT(n.terms,'$.startDate')) AS startDate,JSON_UNQUOTE(JSON_EXTRACT(n.terms,'$.endDate')) AS endDate";if(q!=null){condition+=" AND (JSON_UNQUOTE(JSON_EXTRACT(n.terms,'$.name')) LIKE ? ESCAPE '!' OR n.agreement_no LIKE ? ESCAPE '!')";args.add(like(q));args.add(like(q));}}
            case "units" -> {require("catalog.configure","goods.read");from=" FROM li_goods_unit n JOIN li_p0_catalog_control c ON c.object_type='UNIT' AND c.object_id=n.id WHERE n.delete_flag=0";columns="CAST(n.id AS CHAR) AS id,n.name,c.enabled,c.version";if(q!=null){condition+=" AND n.name LIKE ? ESCAPE '!'";args.add(like(q));}}
            case "channel-configs" -> {require("channel.configure","channel.onboard","channel.receiver");from=" FROM li_p0_channel_config n WHERE n.environment=?";args.add(settings.getEnvironment());columns="CAST(n.id AS CHAR) AS id,n.name,n.mode,n.environment,n.enabled,n.config_version AS version,n.check_state AS status";if(q!=null){condition+=" AND n.name LIKE ? ESCAPE '!'";args.add(like(q));}}
            case "receivers" -> {require("channel.receiver");Stage1Exception.require(config!=null,422,"CONFIG_REQUIRED");from=" FROM li_p0_receiver_relation n JOIN li_p0_channel_config c ON c.id=n.config_id WHERE n.config_id=? AND c.environment=?";args.add(config);args.add(settings.getEnvironment());columns="CAST(n.id AS CHAR) AS id,n.display_name AS name,n.mode,n.enabled,n.state AS status,n.version,CAST(n.config_id AS CHAR) AS configId";if(q!=null){condition+=" AND n.display_name LIKE ? ESCAPE '!'";args.add(like(q));}}
            default -> throw new Stage1Exception(404,"RESOURCE_NOT_FOUND");
        }
        if(!selectedIds.isEmpty()){condition+=" AND n.id IN ("+String.join(",",Collections.nCopies(selectedIds.size(),"?"))+")";args.addAll(selectedIds);}String where=from+condition;long count=db.count("SELECT COUNT(*)"+where,args.toArray());args.add(size);args.add((page-1)*size);return ManagerPresentationViews.page(count,page,size,db.rows("SELECT "+columns+where+" ORDER BY "+order+" LIMIT ? OFFSET ?",args.toArray()));
    }
    private Object slice(List<Map<String,Object>> values,int page,int size){return ManagerPresentationViews.page(values.size(),page,size,values.stream().skip((long)(page-1)*size).limit(size).toList());}
    public Object regionNames(String codes){context.require(SessionDomain.MANAGER);Stage1Exception.require(codes!=null&&!codes.isBlank(),422,"REGION_REQUIRED");var values=Arrays.stream(codes.split(",",-1)).distinct().toList();Stage1Exception.require(values.size()<=200&&values.stream().allMatch(c->c.matches("[0-9]{6}")),422,"REGION_INVALID");String placeholders=String.join(",",Collections.nCopies(values.size(),"?"));return db.rows("SELECT d.ad_code AS code,d.name,c.name AS cityName,p.name AS provinceName FROM li_region d LEFT JOIN li_region c ON c.id=d.parent_id LEFT JOIN li_region p ON p.id=c.parent_id WHERE d.delete_flag=0 AND d.ad_code IN ("+placeholders+")",values.toArray()).stream().map(r->map("code",r.get("code"),"name",r.get("name"),"cityName",r.get("cityName"),"provinceName",r.get("provinceName"))).toList();}
}
