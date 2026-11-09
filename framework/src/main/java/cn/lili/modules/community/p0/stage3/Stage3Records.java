package cn.lili.modules.community.p0.stage3;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service @ConditionalOnProperty(name="p0.stage3.enabled",havingValue="true")
public class Stage3Records {
    private final Stage1Db db;private final Stage1Context context;private final Stage3Authorization auth;
    private static final String UNION="(SELECT v.id,v.order_id,v.store_id,v.point_id,v.operator_id AS actor_id,v.completed_at AS at_time,'SUCCESS' AS result,LEFT(v.credential_hash,16) AS credential_ref,v.point_snapshot FROM li_p0_s3_verification v UNION ALL SELECT a.id,a.order_id,a.store_id,a.point_id,a.actor_id,a.created_at AS at_time,a.result,a.credential_ref,NULL AS point_snapshot FROM li_p0_s3_verification_audit a) v";
    public Stage3Records(Stage1Db db,Stage1Context context,Stage3Authorization auth){this.db=db;this.context=context;this.auth=auth;}
    private void manager(){var actor=context.require(SessionDomain.MANAGER);Stage1Exception.require(auth.managerPermissions(actor.id()).contains("verification.read"),403,"FORBIDDEN");}
    public Object list(Map<String,String> query){
        manager();Stage1Exception.require(Set.of("pageNumber","pageSize","storeId","pointId","operatorId","pointName","operatorName","result","q","timeFrom","timeTo").containsAll(query.keySet()),422,"QUERY_INVALID");
        int page=number(query.getOrDefault("pageNumber","1"),1,1000000),size=number(query.getOrDefault("pageSize","20"),1,100);
        StringBuilder from=new StringBuilder(" FROM "+UNION+" LEFT JOIN li_order o ON o.id=v.order_id LEFT JOIN li_store s ON s.id=v.store_id LEFT JOIN li_member n ON n.id=v.actor_id LEFT JOIN li_store_address a ON a.id=v.point_id AND a.store_id=v.store_id WHERE 1=1");List<Object> args=new ArrayList<>();
        for(String key:List.of("storeId","pointId","operatorId"))if(query.containsKey(key)){String field=key.equals("operatorId")?"actor_id":key.equals("pointId")?"point_id":"store_id";from.append(" AND v.").append(field).append("=?");args.add(new Fields(map("id",query.get(key))).id("id",true));}
        for(String key:List.of("pointName","operatorName"))if(query.containsKey(key)){String value=query.get(key);Stage1Exception.require(value.length()<=50,422,"QUERY_INVALID");from.append(key.equals("pointName")?" AND (JSON_UNQUOTE(JSON_EXTRACT(v.point_snapshot,'$.name')) LIKE ? OR a.address_name LIKE ?)":" AND n.nick_name LIKE ?");args.add("%"+value+"%");if(key.equals("pointName"))args.add("%"+value+"%");}
        if(query.containsKey("result")){Stage1Exception.require(Set.of("SUCCESS","REJECTED").contains(query.get("result")),422,"QUERY_INVALID");from.append(" AND v.result=?");args.add(query.get("result"));}
        if(query.containsKey("q")){String q=query.get("q");Stage1Exception.require(q.length()<=64,422,"QUERY_INVALID");from.append(" AND (o.sn LIKE ? OR s.store_name LIKE ?)");args.add("%"+q+"%");args.add("%"+q+"%");}
        for(String key:List.of("timeFrom","timeTo"))if(query.containsKey(key)){try{from.append(key.equals("timeFrom")?" AND v.at_time>=?":" AND v.at_time<?");args.add(Instant.parse(query.get(key)));}catch(DateTimeException e){throw new Stage1Exception(422,"QUERY_INVALID");}}
        long total=db.count("SELECT COUNT(*)"+from,args.toArray());var pageArgs=new ArrayList<>(args);pageArgs.add(size);pageArgs.add((long)(page-1)*size);
        var records=db.rows("SELECT v.*,o.sn,s.store_name,n.nick_name AS operator_name,a.address_name AS point_name"+from+" ORDER BY v.at_time DESC,v.id DESC LIMIT ? OFFSET ?",pageArgs.toArray()).stream().map(Stage3Records::project).toList();
        return map("records",records,"total",total,"pageNumber",page,"pageSize",size);
    }
    public Object detail(String id){
        manager();var row=db.required("SELECT v.*,o.sn,s.store_name,n.nick_name AS operator_name,a.address_name AS point_name FROM "+UNION+" LEFT JOIN li_order o ON o.id=v.order_id LEFT JOIN li_store s ON s.id=v.store_id LEFT JOIN li_member n ON n.id=v.actor_id LEFT JOIN li_store_address a ON a.id=v.point_id AND a.store_id=v.store_id WHERE v.id=?",id);var result=project(row);
        if("SUCCESS".equals(id(row,"result"))){
            var verification=db.required("SELECT operation_id,operator_id,store_id,order_id FROM li_p0_s3_verification WHERE id=?",id);
            var operation=db.required("SELECT response_json FROM li_p0_s3_operation WHERE identity_domain='STORE' AND actor_id=? AND store_scope=? AND operation_id=? AND action='verify' AND order_id=?",verification.get("operator_id"),verification.get("store_id"),verification.get("operation_id"),verification.get("order_id"));
            var stored=object(operation.get("response_json"));
            Stage1Exception.require(stored.has("orderId")&&stored.has("verificationId")&&stored.has("phase")&&stored.has("version")&&stored.has("completedAt")
                &&id.equals(stored.get("verificationId").getAsString())&&id(verification,"order_id").equals(stored.get("orderId").getAsString())&&"COMPLETED".equals(stored.get("phase").getAsString()),503,"ORIGINAL_VERIFICATION_RESULT_UNAVAILABLE");
            result.put("originalOperation",map("operationId",id(verification,"operation_id"),"result",map("orderId",stored.get("orderId"),"verificationId",stored.get("verificationId"),"phase",stored.get("phase"),"version",stored.get("version"),"completedAt",stored.get("completedAt"))));
        }
        return result;
    }
    private static Map<String,Object> project(Map<String,Object> r){var point=r.get("point_snapshot")==null?null:object(r.get("point_snapshot"));return map("id",id(r,"id"),"orderId",id(r,"order_id"),"orderSn",r.get("sn"),"storeId",id(r,"store_id"),"storeName",r.get("store_name"),"pointId",id(r,"point_id"),"operatorId",id(r,"actor_id"),"operatorName",r.get("operator_name"),"pointName",point==null?r.get("point_name"):point.get("name"),"at",instant(r.get("at_time")),"result",r.get("result"),"credentialRef",r.get("credential_ref"),"point",point);}
    private static int number(String input,int min,int max){try{int n=Integer.parseInt(input);if(n<min||n>max)throw new NumberFormatException();return n;}catch(NumberFormatException e){throw new Stage1Exception(422,"QUERY_INVALID");}}
}
