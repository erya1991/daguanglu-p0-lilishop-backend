package cn.lili.modules.community.p0.stage2;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.support.*;
import cn.lili.modules.community.p0.agreement.AgreementService;
import cn.lili.modules.community.p0.catalog.GoodsManagementService;
import cn.lili.modules.community.p0.presentation.ManagerPresentationViews;
import com.google.gson.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Isolation;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static cn.lili.modules.community.p0.presentation.ManagerPresentationViews.*;

@Service
@ConditionalOnProperty(name={"p0.stage2.enabled","p0.stage2.web-ui-enabled"},havingValue="true")
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class Stage2ManagerUiService {
    private final Stage1Db db;private final Stage1Context context;private final Stage1Access access;private final GoodsManagementService source;
    public Stage2ManagerUiService(Stage1Db db,Stage1Context context,Stage1Access access,GoodsManagementService source){this.db=db;this.context=context;this.access=access;this.source=source;}
    private void require(){var actor=context.require(SessionDomain.MANAGER);Stage1Exception.require(access.managerPermissions(actor.id()).contains("goods.read"),403,"FORBIDDEN");}
    public Object goods(String store,String status,String q,Boolean forced,String market,String stock,int page,int size){require();if(store!=null)identifier(store);var result=object(source.list(store,status,q,forced,market,stock,page,size));for(var row:result.getAsJsonArray("records"))enrich(row.getAsJsonObject());result.addProperty("pageNumber",page);result.addProperty("pageSize",size);return result;}
    private void enrich(JsonObject row){String store=row.get("storeId").getAsString();var name=db.one("SELECT store_name FROM li_store WHERE id=? AND delete_flag=0",store);row.addProperty("storeName",name==null?null:id(name,"store_name"));String category=row.has("categoryId")&&!row.get("categoryId").isJsonNull()?row.get("categoryId").getAsString():null;var parts=new ArrayList<String>();var seen=new HashSet<String>();while(category!=null&&!category.equals("0")&&seen.add(category)&&seen.size()<=3){var c=db.one("SELECT CAST(parent_id AS CHAR) AS parentId,name FROM li_category WHERE id=? AND delete_flag=0",category);if(c==null){parts.add("名称未提供（"+category+"）");break;}parts.add(id(c,"name"));category=id(c,"parentId");}Collections.reverse(parts);row.addProperty("categoryPath",String.join(" / ",parts));var unit=row.has("unitId")&&!row.get("unitId").isJsonNull()?db.one("SELECT name FROM li_goods_unit WHERE id=? AND delete_flag=0",row.get("unitId").getAsString()):null;row.addProperty("unitName",unit==null?null:id(unit,"name"));}
    static JsonObject content(Object value){return pick(value,"name","subtitle","categoryId","unitId","brandId","galleryFileIds","detailBlocks","methods","note");}
    static JsonObject safeDetail(Object value){var raw=object(value);var result=pick(raw,"goods","skus","regulationHistory");result.add("payload",content(raw.get("payload")));var reviews=new JsonArray();for(var item:raw.getAsJsonArray("reviews")){var r=pick(item,"contentVersion","decision","reason","submittedAt","reviewedAt");var submitted=item.getAsJsonObject().getAsJsonObject("submitted");var snapshot=new JsonObject();snapshot.add("content",content(submitted.get("content")));snapshot.add("skus",submitted.get("skus").deepCopy());r.add("submitted",snapshot);reviews.add(r);}result.add("reviews",reviews);return result;}
    public Object detail(String id){require();identifier(id);var result=safeDetail(source.detail(id));enrich(result.getAsJsonObject("goods"));return result;}
    public Object stores(String q,String selected,int page,int size){require();AgreementService.page(page,size);q=search(q);if(selected!=null)identifier(selected);String from=" FROM li_store s WHERE s.delete_flag=0 AND EXISTS(SELECT 1 FROM li_p0_goods_control g WHERE g.store_id=s.id)";var args=new ArrayList<Object>();if(selected!=null){from+=" AND s.id=?";args.add(selected);}if(q!=null){from+=" AND s.store_name LIKE ? ESCAPE '!'";args.add(like(q));}long total=db.count("SELECT COUNT(*)"+from,args.toArray());args.add(size);args.add((page-1)*size);return ManagerPresentationViews.page(total,page,size,db.rows("SELECT CAST(s.id AS CHAR) AS id,s.store_name AS name,s.store_disable AS status"+from+" ORDER BY s.store_name,s.id LIMIT ? OFFSET ?",args.toArray()));}
}
