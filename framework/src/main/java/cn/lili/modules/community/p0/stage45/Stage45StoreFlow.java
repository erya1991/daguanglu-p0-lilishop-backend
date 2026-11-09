package cn.lili.modules.community.p0.stage45;
import cn.lili.modules.community.p0.support.*;import org.springframework.stereotype.Service;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import static cn.lili.modules.community.p0.support.Stage1Json.*;
/** Native StoreFlow is a derived projection of authenticated amounts, including known partial receiver success. */
@Service @ConditionalOnProperty(name="p0.stage45.enabled",havingValue="true")
public class Stage45StoreFlow {
 private final Stage45Data d;private final Stage45Funds funds;
 public Stage45StoreFlow(Stage45Data d,Stage45Funds funds){this.d=d;this.funds=funds;}
 public boolean one(){
  var payment=d.db.one("SELECT s.order_id FROM li_p0_order_snapshot s JOIN li_p0_payment p ON p.trade_id=s.trade_id WHERE p.status='SUCCEEDED' AND (NOT EXISTS(SELECT 1 FROM li_store_flow f WHERE f.p0_source_key=CONCAT('PAY:',s.order_id)) OR EXISTS(SELECT 1 FROM li_store_flow f WHERE f.p0_source_key=CONCAT('PAY:',s.order_id) AND f.p0_projected_at IS NULL)) ORDER BY s.order_id LIMIT 1");
  if(payment!=null){var order=d.orders.row(id(payment,"order_id"));funds.original(order,false);d.locked(order,()->{project(order,"PAY:"+id(order,"id"),"P0_PAY",number(order,"amount_cent"),0,number(order,"amount_cent"),null);return null;});return true;}
  String amount="CAST(JSON_UNQUOTE(JSON_EXTRACT(t.result_json,'$.succeededCent')) AS SIGNED)";
  var task=d.db.one("SELECT t.* FROM li_p0_s45_task t LEFT JOIN li_store_flow f ON f.p0_source_key=CONCAT('TASK:',t.id) WHERE t.result_json IS NOT NULL AND ("+amount+">0 OR (t.kind='FINISH' AND t.state='SUCCEEDED')) AND (f.id IS NULL OR f.p0_projected_at IS NULL OR f.final_price<>IF(t.kind='REFUND',-"+amount+",0)/100.0 OR f.commission_price<>(CASE WHEN t.kind='SHARE' THEN "+amount+" WHEN t.kind='RETURN' THEN -"+amount+" ELSE 0 END)/100.0) ORDER BY t.id LIMIT 1");
  if(task==null)return false;var result=object(task.get("result_json"));Stage1Exception.require(funds.channel.trusted(result),409,"CHANNEL_SUCCESS_NOT_PROVEN");long value=new Fields(result).integer("succeededCent",0,Long.MAX_VALUE,true);var order=d.orders.row(id(task,"order_id"));String kind=id(task,"kind");long gross=kind.equals("REFUND")?-value:0,commission=kind.equals("SHARE")?value:kind.equals("RETURN")?-value:0;
  d.locked(order,()->{project(order,"TASK:"+id(task,"id"),"P0_"+kind,gross,commission,gross-commission,id(task,"request_no"));return null;});return true;
 }
 private void project(java.util.Map<String,Object> order,String source,String kind,long gross,long commission,long net,String request){
  d.db.update("INSERT INTO li_store_flow(id,create_time,final_price,commission_price,bill_price,flow_type,sn,store_id,store_name,member_id,order_sn,transaction_id,p0_source_key,p0_projected_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE final_price=VALUES(final_price),commission_price=VALUES(commission_price),bill_price=VALUES(bill_price),p0_projected_at=VALUES(p0_projected_at)",d.db.id(),d.clock.instant(),money(gross),money(commission),money(net),kind,source,order.get("store_id"),order.get("store_name"),order.get("member_id"),order.get("sn"),request,source,d.clock.instant());
 }
}
