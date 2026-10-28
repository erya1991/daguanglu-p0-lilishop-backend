package cn.lili.modules.community.p0.admission;

import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.time.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Component
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
public class AdmissionRules {
    private final Stage1Db db;private final Stage1Regions regions;private final Stage1Files files;private final Clock clock;
    public AdmissionRules(Stage1Db db,Stage1Regions regions,Stage1Files files,@Qualifier("stage1Clock") Clock clock){this.db=db;this.regions=regions;this.files=files;this.clock=clock;}
    public JsonObject validate(Object input,boolean complete,String actor,String store){
        var f=new Fields(input).only("subjectType","legalName","legalCode","legalPerson","storeName","contactName","phone","provinceCode","cityCode","districtCode","address","description","categoryIds","qualifications","businessHours");
        f.choice("subjectType",Set.of("ENTERPRISE","INDIVIDUAL_BUSINESS"),complete);
        f.text("legalName",2,100,complete);f.text("legalPerson",2,50,complete);f.text("storeName",2,30,complete);f.text("contactName",2,30,complete);
        var result=f.json();String code=f.text("legalCode",18,18,complete);if(code!=null)result.addProperty("legalCode",Stage1Rules.legalCode(code));
        String phone=f.text("phone",7,20,complete);if(phone!=null)Stage1Rules.phone(phone);
        f.text("address",5,200,complete);f.text("description",0,500,complete);f.text("businessHours",0,200,false);regions.address(f,complete);
        List<String> categories=f.ids("categoryIds",complete?1:0,100,complete);
        for(String category:categories) {
            var row=db.one("SELECT c.parent_id,c.delete_flag,c.commission_rate FROM li_category c JOIN li_p0_catalog_control p ON p.object_id=c.id AND p.object_type='CATEGORY' WHERE c.id=? AND c.delete_flag=0 AND p.enabled=1",category);
            Stage1Exception.require(row!=null,422,"CATEGORY_UNAVAILABLE");
            String parent=id(row,"parent_id");Set<String> seen=new HashSet<>();
            while(parent!=null&&!parent.equals("0")){
                Stage1Exception.require(seen.add(parent),409,"CATEGORY_CYCLE");
                var ancestor=db.one("SELECT c.parent_id FROM li_category c JOIN li_p0_catalog_control p ON p.object_id=c.id AND p.object_type='CATEGORY' WHERE c.id=? AND c.delete_flag=0 AND p.enabled=1",parent);
                Stage1Exception.require(ancestor!=null,422,"CATEGORY_UNAVAILABLE");parent=id(ancestor,"parent_id");
            }
        }
        JsonArray qualifications=f.list("qualifications",complete?1:0,30,complete);
        Set<String> supplied=new HashSet<>();
        for(var item:qualifications){
            var q=qualification(item,complete,actor,store,categories);String type=q.get("type").getAsString();
            Stage1Exception.require(supplied.add(type),422,"QUALIFICATION_DUPLICATE");
            if(complete&&type.equals("BUSINESS_LICENSE")){
                Stage1Exception.require(result.get("legalName").getAsString().trim().equals(q.get("holder").getAsString().trim())&&result.get("legalCode").getAsString().equals(Stage1Rules.legalCode(q.get("number").getAsString())),422,"QUALIFICATION_SUBJECT_MISMATCH");
            }
        }
        if(complete){
            Set<String> required=new HashSet<>();
            db.rows("SELECT code FROM li_p0_qualification_type WHERE scope='STORE' AND enabled=1").forEach(r->required.add(id(r,"code")));
            for(String category:categories)for(String ancestor:lineage(category))for(var rule:db.rows("SELECT qualification_type FROM li_p0_category_qualification WHERE category_id=? AND required=1",ancestor)){
                String type=id(rule,"qualification_type");required.add(type);boolean covered=qualifications.asList().stream().map(Fields::new).anyMatch(q->type.equals(q.text("type",1,64,true))&&q.ids("categoryIds",0,100,false).stream().anyMatch(lineage(category)::contains));Stage1Exception.require(covered,422,"QUALIFICATION_SCOPE_REQUIRED");
            }
            Stage1Exception.require(!required.isEmpty()&&supplied.containsAll(required),422,"QUALIFICATION_REQUIRED");
        }
        return result;
    }
    public JsonObject qualification(Object input,boolean complete,String actor,String store,List<String> categories){
        var q=new Fields(input).only("type","number","holder","issuer","validFrom","validThrough","longTerm","fileIds","categoryIds");
        String type=q.text("type",1,64,true);var rule=db.required("SELECT scope,required_fields FROM li_p0_qualification_type WHERE code=? AND enabled=1",type);
        Set<String> required=new HashSet<>();array(rule.get("required_fields")).forEach(v->required.add(v.getAsString()));
        for(String field:List.of("number","holder","issuer"))q.text(field,1,100,complete&&required.contains(field));
        LocalDate from=q.date("validFrom",complete),through=q.date("validThrough",complete&&!q.bool("longTerm",false));
        if(q.bool("longTerm",false)&&through!=null)throw Stage1Exception.field("validThrough","LONG_TERM_CONFLICT");
        if(from!=null&&through!=null)Stage1Rules.dates(from,through);
        if(complete){
            LocalDate today=LocalDate.now(clock.withZone(Stage1Rules.ZONE));
            boolean renewal=store!=null&&db.count("SELECT COUNT(*) FROM li_p0_store_control WHERE store_id=? AND admission_state='APPROVED'",store)==1;
            Stage1Exception.require((renewal||!from.isAfter(today))&&(through==null||!through.isBefore(today)),422,"QUALIFICATION_EXPIRED");
        }
        List<String> categoryIds=q.ids("categoryIds",0,100,false);
        if("CATEGORY".equals(id(rule,"scope")))Stage1Exception.require((!complete||!categoryIds.isEmpty())&&categoryIds.stream().allMatch(c->!Collections.disjoint(lineage(c),categories)),422,"QUALIFICATION_SCOPE_INVALID");
        else Stage1Exception.require(categoryIds.isEmpty(),422,"QUALIFICATION_SCOPE_INVALID");
        List<String> refs=q.ids("fileIds",complete?1:0,10,complete);if(!refs.isEmpty())files.validate(refs,"QUALIFICATION",actor,store);
        return q.json();
    }
    private List<String> lineage(String category){var ids=new ArrayList<String>();String current=category;while(current!=null&&!current.equals("0")){Stage1Exception.require(ids.size()<3&&!ids.contains(current),409,"CATEGORY_TREE_INVALID");ids.add(current);var row=db.required("SELECT c.parent_id FROM li_category c JOIN li_p0_catalog_control p ON p.object_type='CATEGORY' AND p.object_id=c.id WHERE c.id=? AND c.delete_flag=0 AND p.enabled=1",current);current=id(row,"parent_id");}return ids;}
    public void projectQualifications(JsonObject payload,String application,String store,String actor){
        for(JsonElement item:payload.getAsJsonArray("qualifications")){
            var q=new Fields(item);String type=q.text("type",1,64,true);
            var last=db.rows("SELECT details FROM li_p0_qualification WHERE store_id=? AND qualification_key=? AND status='APPROVED' AND revoked_at IS NULL ORDER BY revision DESC LIMIT 1",store,type);
            if(!last.isEmpty()&&hash(object(last.getFirst().get("details"))).equals(hash(q.json())))continue;
            long revision=db.count("SELECT COALESCE(MAX(revision),0)+1 FROM li_p0_qualification WHERE store_id=? AND qualification_key=?",store,type);
            String scope=id(db.required("SELECT scope FROM li_p0_qualification_type WHERE code=?",type),"scope");
            Instant from=q.date("validFrom",true).atStartOfDay(Stage1Rules.ZONE).toInstant();LocalDate through=q.date("validThrough",false);
            Instant until=through==null?null:through.plusDays(1).atStartOfDay(Stage1Rules.ZONE).toInstant();String id=db.id();
            Instant effective=from.isAfter(clock.instant())?from:clock.instant();
            db.update("UPDATE li_p0_qualification SET superseded_at=?,version=version+1 WHERE store_id=? AND qualification_key=? AND status='APPROVED' AND (superseded_at IS NULL OR superseded_at>?)",effective,store,type,effective);
            db.update("INSERT INTO li_p0_qualification(id,store_id,qualification_key,revision,type,scope,category_ids,status,valid_from,valid_to,long_term,attachment_refs,application_id,created_at,details,approved_at) VALUES(?,?,?,?,?,?,?,'APPROVED',?,?,?,?,?,?,?,?)",id,store,type,revision,type,scope,write(q.ids("categoryIds",0,100,false)),from,until,q.bool("longTerm",false),write(q.ids("fileIds",1,10,true)),application,clock.instant(),write(q.json()),clock.instant());
            files.bind(q.ids("fileIds",1,10,true),"QUALIFICATION",actor,store,id);
        }
    }
    public void bindSubmitted(JsonObject payload,String store,String actor,String application){
        for(var item:payload.getAsJsonArray("qualifications"))files.bind(new Fields(item).ids("fileIds",1,10,true),"QUALIFICATION",actor,store,application);
    }
}
