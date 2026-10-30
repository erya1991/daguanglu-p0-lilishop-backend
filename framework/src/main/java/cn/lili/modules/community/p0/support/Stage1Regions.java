package cn.lili.modules.community.p0.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service
@cn.lili.modules.community.p0.support.P0BusinessEnabled
public class Stage1Regions {
    private final Stage1Db db;public Stage1Regions(Stage1Db db){this.db=db;}
    public Map<String,Object> address(Fields f,boolean required){
        String province=f.text("provinceCode",6,6,required),city=f.text("cityCode",6,6,required),district=f.text("districtCode",6,6,required);
        if(province==null&&city==null&&district==null)return Map.of();
        if(province==null||city==null||district==null)throw Stage1Exception.field("districtCode","REGION_HIERARCHY_REQUIRED");
        var row=db.one("SELECT p.id AS province_id,c.id AS city_id,d.id AS district_id,p.name AS province_name,c.name AS city_name,d.name AS district_name FROM li_region p JOIN li_region c ON c.parent_id=p.id JOIN li_region d ON d.parent_id=c.id WHERE p.ad_code=? AND c.ad_code=? AND d.ad_code=? AND p.delete_flag=0 AND c.delete_flag=0 AND d.delete_flag=0 AND p.level='province' AND c.level='city' AND d.level='district'",province,city,district);
        Stage1Exception.require(row!=null,422,"REGION_INVALID");return row;
    }
    public Object children(String parent){
        if(parent!=null&&!parent.matches("[1-9][0-9]{0,18}"))throw Stage1Exception.field("parentId","ID_REQUIRED");
        return db.rows("SELECT id,ad_code,name,level FROM li_region WHERE parent_id=? AND delete_flag=0 ORDER BY order_num,id",parent==null?"0":parent).stream().map(r->map("id",id(r,"id"),"code",r.get("ad_code"),"name",r.get("name"),"level",r.get("level"))).toList();
    }
}
