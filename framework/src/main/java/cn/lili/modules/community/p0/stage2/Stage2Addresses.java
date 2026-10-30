package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.support.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Uses native MemberAddress rows and one authoritative Member default pointer. */
@Service @ConditionalOnProperty(name="p0.stage2.enabled",havingValue="true")
public class Stage2Addresses {
    private final Stage1Db db;private final Stage2Transactions tx;private final Clock clock;
    public Stage2Addresses(Stage1Db db,Stage2Transactions tx,@Qualifier("stage1Clock")Clock clock){this.db=db;this.tx=tx;this.clock=clock;}
    public Object regions(String parentId){return new Stage1Regions(db).children(parentId);}
    public Object list(){return view(tx.member());}
    public Map<String,Object> view(String member){
        var book=db.one("SELECT * FROM li_p0_member_checkout WHERE member_id=?",member);
        return map("version",book==null?0:number(book,"version"),"defaultAddressId",book==null?null:id(book,"default_address_id"),"records",db.rows("SELECT a.id FROM li_member_address a JOIN li_p0_address_control c ON c.address_id=a.id WHERE a.member_id=? AND a.delete_flag=0 ORDER BY a.create_time DESC,a.id DESC",member).stream().map(r->snapshot(id(r,"id"),member)).toList());
    }
    public Map<String,Object> snapshot(String id,String member){
        var row=db.required("SELECT a.*,c.version,c.province_code,c.city_code,c.district_code FROM li_member_address a JOIN li_p0_address_control c ON c.address_id=a.id AND c.member_id=a.member_id WHERE a.id=? AND a.member_id=? AND a.delete_flag=0",id,member);
        return map("id",id,"name",row.get("name"),"mobile",row.get("mobile"),"provinceCode",row.get("province_code"),"cityCode",row.get("city_code"),"districtCode",row.get("district_code"),"region",row.get("consignee_address_path"),"detail",row.get("detail"),"alias",row.get("alias"),"version",row.get("version"),"isDefault",flag(row,"is_default"));
    }
    private Map<String,Object> book(String member,long expected){
        db.update("INSERT IGNORE INTO li_p0_member_checkout(member_id) VALUES(?)",member);
        var row=db.required("SELECT * FROM li_p0_member_checkout WHERE member_id=? FOR UPDATE",member);
        Stage1Exception.require(number(row,"version")==expected,409,"ADDRESS_BOOK_VERSION_CONFLICT");return row;
    }
    private void selectDefault(String member,String id){
        db.update("UPDATE li_p0_member_checkout SET default_address_id=?,version=version+1 WHERE member_id=?",id,member);
        db.update("UPDATE li_member_address SET is_default=0 WHERE member_id=?",member);
        if(id!=null)db.cas(db.update("UPDATE li_member_address SET is_default=1 WHERE id=? AND member_id=? AND delete_flag=0",id,member));
    }
    public Object save(String addressId,Object body){
        var f=new Fields(body).only("operationId","expectedVersion","addressBookVersion","name","mobile","provinceCode","cityCode","districtCode","detail","alias","isDefault");
        String name=f.text("name",1,30,true),mobile=f.text("mobile",11,11,true),detail=f.text("detail",5,200,true),alias=f.text("alias",0,30,false);
        Stage1Exception.require(mobile.matches("1[0-9]{10}"),422,"MOBILE_INVALID");
        String province=f.text("provinceCode",6,6,true),city=f.text("cityCode",6,6,true),district=f.text("districtCode",6,6,true),member=tx.member();
        return tx.command("address.save:"+addressId,body,List.of(),()->{
            var pointer=book(member,f.integer("addressBookVersion",0,Long.MAX_VALUE,true));
            var region=db.required("SELECT p.name province_name,c.name city_name,d.name district_name FROM li_region d JOIN li_region c ON c.id=d.parent_id JOIN li_region p ON p.id=c.parent_id WHERE p.ad_code=? AND c.ad_code=? AND d.ad_code=? AND d.level='district' AND p.delete_flag=0 AND c.delete_flag=0 AND d.delete_flag=0",province,city,district);
            String regionName=id(region,"province_name")+","+id(region,"city_name")+","+id(region,"district_name");
            long count=db.count("SELECT COUNT(*) FROM li_member_address WHERE member_id=? AND delete_flag=0",member);
            String target=addressId==null?db.id():addressId;
            if(addressId==null){Stage1Exception.require(count<20,409,"ADDRESS_CAPACITY");db.update("INSERT INTO li_member_address(id,member_id,name,mobile,consignee_address_path,consignee_address_id_path,detail,alias,is_default,delete_flag,create_time,update_time) VALUES(?,?,?,?,?,?,?,?,0,0,?,?)",target,member,name,mobile,regionName,province+","+city+","+district,detail,alias,clock.instant(),clock.instant());db.update("INSERT INTO li_p0_address_control VALUES(?,?,0,?,?,?)",target,member,province,city,district);}
            else {var prior=snapshot(target,member);Stage1Exception.require(((Number)prior.get("version")).longValue()==f.version(),409,"VERSION_CONFLICT");db.update("UPDATE li_member_address SET name=?,mobile=?,consignee_address_path=?,consignee_address_id_path=?,detail=?,alias=?,update_time=? WHERE id=? AND member_id=?",name,mobile,regionName,province+","+city+","+district,detail,alias,clock.instant(),target,member);db.update("UPDATE li_p0_address_control SET version=version+1,province_code=?,city_code=?,district_code=? WHERE address_id=?",province,city,district,target);}
            boolean set=count==0||f.bool("isDefault",false);
            String current=id(pointer,"default_address_id");if(set)selectDefault(member,target);else if(target.equals(current)&&f.has("isDefault"))selectDefault(member,null);else db.update("UPDATE li_p0_member_checkout SET version=version+1 WHERE member_id=?",member);
            return view(member);
        });
    }
    public Object remove(String id,Object body){
        var f=new Fields(body).only("operationId","expectedVersion","addressBookVersion");String member=tx.member();
        return tx.command("address.remove:"+id,body,List.of(),()->{var pointer=book(member,f.integer("addressBookVersion",0,Long.MAX_VALUE,true));var row=snapshot(id,member);Stage1Exception.require(((Number)row.get("version")).longValue()==f.version(),409,"VERSION_CONFLICT");db.update("UPDATE li_member_address SET delete_flag=1,is_default=0,update_time=? WHERE id=? AND member_id=?",clock.instant(),id,member);db.update("UPDATE li_p0_address_control SET version=version+1 WHERE address_id=?",id);if(id.equals(id(pointer,"default_address_id")))selectDefault(member,null);else db.update("UPDATE li_p0_member_checkout SET version=version+1 WHERE member_id=?",member);return view(member);});
    }
    public Object makeDefault(String id,Object body){
        var f=new Fields(body).only("operationId","expectedVersion","addressBookVersion");String member=tx.member();
        return tx.command("address.default:"+id,body,List.of(),()->{book(member,f.integer("addressBookVersion",0,Long.MAX_VALUE,true));if(id!=null){var row=snapshot(id,member);Stage1Exception.require(((Number)row.get("version")).longValue()==f.version(),409,"VERSION_CONFLICT");}selectDefault(member,id);return view(member);});
    }
}
