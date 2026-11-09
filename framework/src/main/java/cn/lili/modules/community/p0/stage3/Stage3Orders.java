package cn.lili.modules.community.p0.stage3;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.fulfillment.FulfillmentService;
import cn.lili.modules.community.p0.stage2.*;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service @ConditionalOnProperty(name="p0.stage3.enabled",havingValue="true")
public class Stage3Orders {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private org.springframework.beans.factory.ObjectProvider<cn.lili.modules.community.p0.stage45.Stage45Fulfillment> stage45;
    public String aftersaleBlock(String order){var hook=stage45==null?null:stage45.getIfAvailable();return hook==null?null:hook.block(order);}
    public void requireNoAftersale(String order){String reason=aftersaleBlock(order);Stage1Exception.require(reason==null,409,reason==null?"FULFILLMENT_BLOCKED":reason);}
    public JsonArray currentItems(Map<String,Object> order){var hook=stage45==null?null:stage45.getIfAvailable();return hook==null?object(order.get("payload_json")).getAsJsonObject("order").getAsJsonArray("items"):hook.items(order);}
    private final Stage1Db db;private final Stage1Context context;private final Stage3Authorization auth;
    private final Stage3Eligibility eligibility;private final Stage3Gate gate;private final Stage2Transactions tx;
    private final FulfillmentService points;private final Clock clock;
    public Stage3Orders(Stage1Db db,Stage1Context context,Stage3Authorization auth,Stage3Eligibility eligibility,Stage3Gate gate,Stage2Transactions tx,FulfillmentService points,@Qualifier("stage1Clock")Clock clock){
        this.db=db;this.context=context;this.auth=auth;this.eligibility=eligibility;this.gate=gate;this.tx=tx;this.points=points;this.clock=clock;
    }
    public Map<String,Object> row(String order){return db.required("SELECT o.*,s.trade_id,s.payload_json,s.content_hash,s.amount_cent,s.goods_cent,s.freight_cent FROM li_order o JOIN li_p0_order_snapshot s ON s.order_id=o.id WHERE o.id=? AND o.delete_flag=0",order);}
    public Map<String,Object> authorized(String order,String action){var actor=context.current();var row=row(order);auth.order(actor,row,action);return row;}
    public List<String> stores(String trade){return db.rows("SELECT store_id FROM li_p0_order_snapshot WHERE trade_id=? ORDER BY store_id",trade).stream().map(r->id(r,"store_id")).toList();}
    public Object locked(Map<String,Object> order,Supplier<Object> work){return tx.historical(id(order,"member_id"),stores(id(order,"trade_id")),()->{db.required("SELECT trade_id FROM li_p0_trade_state WHERE trade_id=? FOR UPDATE",order.get("trade_id"));db.required("SELECT id FROM li_order WHERE id=? FOR UPDATE",order.get("id"));return work.get();});}
    @Transactional(readOnly=true)
    public Object detail(String order){var row=authorized(order,"order.read");return view(row,true);}
    public Map<String,Object> state(Map<String,Object> row){return db.one("SELECT * FROM li_p0_s3_fulfillment WHERE order_id=?",row.get("id"));}
    public String method(Map<String,Object> row){return object(row.get("payload_json")).getAsJsonObject("order").get("method").getAsString();}
    public boolean pointAvailable(Map<String,Object> row){
        var o=object(row.get("payload_json")).getAsJsonObject("order");if(o.get("point").isJsonNull())return true;
        try{return points.isAvailablePoint(points.point(o.getAsJsonObject("point").get("id").getAsString(),id(row,"store_id")),method(row));}catch(Stage1Exception e){if(e.status()>=500)throw e;return false;}
    }
    public Map<String,Object> view(Map<String,Object> row,boolean detail){
        var actor=context.current();auth.order(actor,row,"order.read");String trade=id(row,"trade_id"),method=method(row);
        var original=object(row.get("payload_json")).getAsJsonObject("order");var local=state(row);var checked=eligibility.inspect(trade);
        String phase=local==null?(checked.eligible()?Stage3Policy.initial(method):"BLOCKED"):id(local,"phase");
        String blocked=checked.eligible()?gate.writeBlock():checked.reason();if(blocked==null)blocked=aftersaleBlock(id(row,"id"));boolean point=pointAvailable(row);if(!point&&blocked==null)blocked="POINT_UNAVAILABLE";
        boolean resident=actor.domain()==SessionDomain.RESIDENT,store=actor.domain()==SessionDomain.STORE;
        Set<String> permissions=store?auth.storePermissions(actor.user()):Set.of();
        List<String> actions=blocked==null&&local!=null&&(resident||store&&permissions.contains("fulfillment.write"))?Stage3Policy.actions(method,phase,resident):List.of();
        JsonObject paid=null;try{paid=eligibility.trustedPaid(trade);}catch(Stage1Exception e){if(e.status()>=500)throw e;}
        JsonObject child=null;if(paid!=null)for(var raw:paid.getAsJsonArray("subOrders"))if(raw.getAsJsonObject().get("orderId").getAsString().equals(id(row,"id")))child=raw.getAsJsonObject();
        var contact=original.getAsJsonObject("contact").deepCopy();boolean sensitive=detail&&(resident||store&&permissions.contains("fulfillment.write")&&!phase.equals("COMPLETED"));
        if(!sensitive)contact.addProperty("mobile",mask(contact.get("mobile").getAsString()));
        JsonObject address=original.get("address").isJsonNull()?null:original.getAsJsonObject("address").deepCopy();
        if(address!=null&&!sensitive)address.addProperty("detail","详细地址仅向有权履约人员显示");
        var t=db.required("SELECT status,payment_status,close_status,protection,created_at FROM li_p0_trade_state WHERE trade_id=?",trade);
        var result=map("orderId",id(row,"id"),"orderSn",id(row,"sn"),"tradeId",trade,"tradeSn",id(row,"trade_sn"),"storeId",id(row,"store_id"),"storeName",original.get("storeName"),
            "method",method,"phase",phase,"version",local==null?0:number(local,"version"),"admitted",local!=null,"paymentStatus",t.get("payment_status"),"tradeStatus",t.get("status"),"closeStatus",t.get("close_status"),
            "protection",t.get("protection"),"blockedReason",blocked,"trustedPaid",paid!=null,"eligible",checked.eligible(),"pointAvailable",point,
            "goodsCent",row.get("goods_cent"),"freightCent",row.get("freight_cent"),"amountCent",row.get("amount_cent"),"paidAmountCent",child==null?null:child.get("amountCent"),"paidAt",child==null?null:child.get("paidAt"),
            "createdAt",instant(row.get("create_time")),"completedAt",local==null?null:instant(local.get("completed_at")),"items",currentItems(row),"originalItems",original.get("items"),"fulfillmentEnded", "FULFILLMENT_ENDED_REFUNDED".equals(aftersaleBlock(id(row,"id"))),
            "contact",contact,"address",address,"point",original.get("point"),"buyerRemark",original.get("remark"),"canRecordException",store&&permissions.contains("order.exception"),"allowedActions",actions,
            "logistics",map("company",row.get("logistics_name"),"code",row.get("logistics_code"),"number",row.get("logistics_no"),"shippedAt",instant(row.get("logistics_time"))),"mode","MOCK","serverTime",clock.instant());
        if(store&&detail)result.put("merchantRemark",row.get("seller_remark"));
        if(detail){result.put("timeline",db.rows("SELECT event_type,actor_domain,actor_id,created_at,payload_json FROM li_p0_s3_event WHERE order_id=? ORDER BY created_at,id",row.get("id")).stream().map(e->map("type",e.get("event_type"),"actorDomain",e.get("actor_domain"),"actorId",id(e,"actor_id"),"at",instant(e.get("created_at")))).toList());
            result.put("exceptions",db.rows("SELECT exception_type,reason,created_at FROM li_p0_s3_exception WHERE order_id=? ORDER BY id DESC",row.get("id")).stream().map(e->map("type",e.get("exception_type"),"reason",e.get("reason"),"at",instant(e.get("created_at")))).toList());}
        return result;
    }
    @Transactional(readOnly=true)
    public Object list(Map<String,String> query){
        var actor=context.current();int page=integer(query.getOrDefault("pageNumber","1"),1,1000000),size=integer(query.getOrDefault("pageSize","20"),1,100);
        Set<String> allowed=Set.of("pageNumber","pageSize","q","storeId","method","filter","phase","paymentStatus","timeFrom","timeTo");
        Stage1Exception.require(allowed.containsAll(query.keySet()),422,"QUERY_INVALID");
        StringBuilder sql=new StringBuilder("SELECT o.id FROM li_order o JOIN li_p0_order_snapshot s ON s.order_id=o.id JOIN li_p0_trade_state t ON t.trade_id=s.trade_id LEFT JOIN li_p0_s3_fulfillment f ON f.order_id=o.id WHERE o.delete_flag=0");
        List<Object> args=new ArrayList<>();if(actor.domain()==SessionDomain.RESIDENT){sql.append(" AND o.member_id=?");args.add(actor.id());}
        else if(actor.domain()==SessionDomain.STORE){Stage1Exception.require(auth.storePermissions(actor.user()).contains("order.read"),403,"FORBIDDEN");sql.append(" AND o.store_id=?");args.add(actor.store());}
        else Stage1Exception.require(auth.managerPermissions(actor.id()).contains("order.read"),403,"FORBIDDEN");
        if(query.containsKey("storeId")){Stage1Exception.require(actor.domain()==SessionDomain.MANAGER,422,"QUERY_INVALID");String store=new Fields(map("storeId",query.get("storeId"))).id("storeId",true);sql.append(" AND o.store_id=?");args.add(store);}
        String q=query.get("q");if(q!=null&&!q.isBlank()){Stage1Exception.require(q.length()<=64,422,"QUERY_INVALID");sql.append(" AND (o.sn LIKE ? OR o.trade_sn LIKE ? OR o.store_name LIKE ? OR JSON_UNQUOTE(JSON_EXTRACT(s.payload_json,'$.order.items')) LIKE ?)");for(int i=0;i<4;i++)args.add("%"+escape(q)+"%");}
        if(query.containsKey("method")){String method=query.get("method");Stage1Exception.require(Stage3Policy.METHODS.contains(method),422,"QUERY_INVALID");sql.append(" AND o.delivery_method=?");args.add(method);}
        for(String key:List.of("timeFrom","timeTo"))if(query.containsKey(key)){try{Instant value=Instant.parse(query.get(key));sql.append(key.equals("timeFrom")?" AND o.create_time>=?":" AND o.create_time<?");args.add(value);}catch(DateTimeException e){throw new Stage1Exception(422,"QUERY_INVALID");}}
        if(query.containsKey("paymentStatus")){String status=query.get("paymentStatus");Stage1Exception.require(Set.of("NOT_STARTED","UNPAID","UNKNOWN","SUCCEEDED","CLOSED","PARTIAL").contains(status),422,"QUERY_INVALID");sql.append(" AND t.payment_status=?");args.add(status);}
        sql.append(" ORDER BY o.create_time DESC,o.id DESC");
        String filter=query.getOrDefault("filter","ALL"),phase=query.get("phase");Stage1Exception.require(Set.of("ALL","WAIT_PAY","WAIT_MERCHANT","WAIT_RECEIVE","PICKUP","COMPLETED","EXCEPTION").contains(filter),422,"QUERY_INVALID");
        if(phase!=null)Stage1Exception.require(Set.of("WAIT_PREPARE","WAIT_DISPATCH","WAIT_SHIP","DELIVERING","DELIVERED","SHIPPED","WAIT_PICKUP","WAIT_VERIFY","COMPLETED","BLOCKED").contains(phase),422,"QUERY_INVALID");
        // Count and page after authoritative eligibility filtering, never only filter a downloaded first page.
        var records=db.rows(sql.toString(),args.toArray()).stream().map(r->view(row(id(r,"id")),false)).filter(v->phase==null||phase.equals(v.get("phase"))).filter(v->matches(v,filter)).toList();
        long offset=(long)(page-1)*size;int from=(int)Math.min(offset,records.size()),to=Math.min(from+size,records.size());
        if(actor.domain()==SessionDomain.MANAGER){var grouped=new LinkedHashMap<String,Map<String,Object>>();for(var record:records)grouped.putIfAbsent(String.valueOf(record.get("tradeId")),record);records=new ArrayList<>(grouped.values());from=(int)Math.min(offset,records.size());to=Math.min(from+size,records.size());}
        var selected=records.subList(from,to);if(actor.domain()==SessionDomain.MANAGER)for(var value:selected){String trade=String.valueOf(value.get("tradeId"));value.put("tradeAmountCent",db.required("SELECT amount_cent FROM li_p0_trade_state WHERE trade_id=?",trade).get("amount_cent"));value.put("relatedOrders",db.rows("SELECT order_id FROM li_p0_order_snapshot WHERE trade_id=? ORDER BY store_id",trade).stream().map(child->view(row(id(child,"order_id")),false)).toList());}
        context.recheck(actor);return map("records",selected,"total",records.size(),"pageNumber",page,"pageSize",size,"serverTime",clock.instant());
    }
    private static boolean matches(Map<String,Object> v,String filter){if(Boolean.TRUE.equals(v.get("fulfillmentEnded"))&&Set.of("WAIT_MERCHANT","WAIT_RECEIVE","PICKUP").contains(filter))return false;String phase=String.valueOf(v.get("phase"));return switch(filter){
        case "WAIT_PAY"->Set.of("WAIT_PAY","PAY_CONFIRMING","CLOSE_CONFIRMING").contains(v.get("tradeStatus"));
        case "WAIT_MERCHANT"->Boolean.TRUE.equals(v.get("eligible"))&&Set.of("WAIT_PREPARE","WAIT_DISPATCH","WAIT_SHIP").contains(phase);
        case "WAIT_RECEIVE"->Set.of("DELIVERING","DELIVERED","SHIPPED").contains(phase);
        case "PICKUP"->Set.of("WAIT_PICKUP","WAIT_VERIFY").contains(phase);
        case "COMPLETED"->phase.equals("COMPLETED");
        case "EXCEPTION"->v.get("protection")!=null||(!Boolean.TRUE.equals(v.get("eligible"))&&"SUCCEEDED".equals(v.get("paymentStatus")));
        default->true;};}
    public Map<String,Object> admitLocked(Map<String,Object> row){
        eligibility.require(id(row,"trade_id"));var state=state(row);if(state!=null)return state;
        String method=method(row);db.update("INSERT INTO li_p0_s3_fulfillment(order_id,trade_id,store_id,method,phase,admitted_at,updated_at) VALUES(?,?,?,?,?,?,?)",row.get("id"),row.get("trade_id"),row.get("store_id"),method,Stage3Policy.initial(method),clock.instant(),clock.instant());
        event(row,0,"READY",null,map("phase",Stage3Policy.initial(method)));return state(row);
    }
    public Object command(String order,String action,Object body){
        gate.requireWrites();var actor=context.require(action.equals("receive")?SessionDomain.RESIDENT:SessionDomain.STORE);
        var fields=new Fields(body).only("operationId","expectedVersion","company","number","code","note");String op=Stage2Transactions.operation(fields);long expected=fields.version();
        String company=fields.text("company",1,80,action.equals("ship")),number=fields.text("number",1,80,action.equals("ship")),code=fields.text("code",1,32,false),note=fields.text("note",0,500,false);
        if(action.equals("ship"))Stage1Exception.require(number.matches("[A-Za-z0-9-]{1,80}"),422,"WAYBILL_INVALID");else Stage1Exception.require(company==null&&number==null&&code==null,422,"FIELD_INVALID");
        var original=authorized(order,action.equals("receive")?"order.receive":"fulfillment.write");
        return locked(original,()->{context.recheck(actor);auth.order(actor,row(order),action.equals("receive")?"order.receive":"fulfillment.write");var prior=prior(actor,op,action,body,order);if(prior!=null)return prior;
            eligibility.require(id(original,"trade_id"));Stage1Exception.require(pointAvailable(original),409,"POINT_UNAVAILABLE");var current=admitLocked(original);if(number(current,"version")!=expected)throw new Stage1Exception(409,"VERSION_CONFLICT",map("currentVersion",number(current,"version")));
            requireNoAftersale(order);String next=Stage3Policy.next(method(original),id(current,"phase"),action);transition(original,current,next,action.equals("receive")?"COMPLETED":action.toUpperCase(Locale.ROOT),actor,map("note",note));
            if(action.equals("ship")){db.cas(db.update("UPDATE li_order SET logistics_name=?,logistics_no=?,logistics_code=?,logistics_time=?,deliver_status='DELIVERED' WHERE id=? AND logistics_time IS NULL",company,number,code,clock.instant(),order));}
            if(action.equals("delivered"))db.update("UPDATE li_order SET deliver_status='DELIVERED' WHERE id=?",order);
            var result=map("orderId",order,"phase",next,"version",expected+1,"at",clock.instant());saveOperation(actor,op,action,body,order,result);context.recheck(actor);return result;});
    }
    public Object exception(String order,Object body){
        var actor=context.require(SessionDomain.STORE);var fields=new Fields(body).only("operationId","expectedVersion","type","reason");String op=Stage2Transactions.operation(fields);long version=fields.version();
        String type=fields.choice("type",Set.of("DELIVERY_DISPUTE","DELIVERY_UNAVAILABLE","WAYBILL_ERROR","POINT_UNAVAILABLE","PICKUP_DISPUTE","OTHER"),true),reason=fields.text("reason",2,500,true);
        var original=authorized(order,"order.exception");return locked(original,()->{context.recheck(actor);auth.order(actor,row(order),"order.exception");var old=prior(actor,op,"exception",body,order);if(old!=null)return old;var state=state(original);Stage1Exception.require((state==null?0:number(state,"version"))==version,409,"VERSION_CONFLICT");
            String id=db.id();db.update("INSERT INTO li_p0_s3_exception VALUES(?,?,?,?,?,?,?)",id,order,actor.store(),actor.id(),type,reason,clock.instant());
            event(original,Long.parseLong(id),"EXCEPTION",actor,map("exceptionId",id,"type",type));
            var result=map("orderId",order,"exceptionId",id,"phaseUnchanged",true,"version",version);saveOperation(actor,op,"exception",body,order,result);return result;});
    }
    public void transition(Map<String,Object> row,Map<String,Object> state,String next,String eventType,Stage1Context.Actor actor,Object payload){
        long version=number(state,"version");db.cas(db.update("UPDATE li_p0_s3_fulfillment SET phase=?,version=version+1,updated_at=?,completed_at=IF(?='COMPLETED',?,completed_at) WHERE order_id=? AND version=?",next,clock.instant(),next,clock.instant(),row.get("id"),version));
        if(next.equals("COMPLETED"))db.cas(db.update("UPDATE li_order SET order_status='COMPLETED',complete_time=? WHERE id=? AND complete_time IS NULL",clock.instant(),row.get("id")));
        db.update("INSERT INTO li_order_log(id,order_sn,operator_id,operator_type,operator_name,message,create_time,delete_flag) VALUES(?,?,?,?,?,?,?,0)",db.id(),row.get("sn"),actor==null?null:actor.id(),actor==null?"SYSTEM":actor.domain().name(),"Stage3",eventType,clock.instant());
        event(row,version+1,eventType,actor,payload);
    }
    public void event(Map<String,Object> row,long version,String type,Stage1Context.Actor actor,Object payload){
        db.update("INSERT INTO li_p0_s3_event(id,order_id,trade_id,store_id,member_id,version,event_type,actor_id,actor_domain,payload_json,created_at,completion_order_id,next_attempt_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)",
            db.id(),row.get("id"),row.get("trade_id"),row.get("store_id"),row.get("member_id"),version,type,actor==null?null:actor.id(),actor==null?"SYSTEM":actor.domain().name(),write(payload),clock.instant(),type.equals("COMPLETED")?row.get("id"):null,clock.instant());
    }
    public Object prior(Stage1Context.Actor actor,String op,String action,Object body,String order){
        var old=db.one("SELECT * FROM li_p0_s3_operation WHERE identity_domain=? AND actor_id=? AND store_scope=? AND operation_id=?",actor.domain().name(),actor.id(),scope(actor),op);
        if(old==null)return null;Stage1Exception.require(operationHash(action,order,body).equals(id(old,"payload_hash")),409,"IDEMPOTENCY_CONFLICT");return object(old.get("response_json"));
    }
    static String operationHash(String action,String order,Object body){return hash(map("action",action,"orderId",order,"body",body));}
    public void saveOperation(Stage1Context.Actor actor,String op,String action,Object body,String order,Object result){db.update("INSERT INTO li_p0_s3_operation VALUES(?,?,?,?,?,?,?,?,?)",actor.domain().name(),actor.id(),scope(actor),op,order,action,operationHash(action,order,body),write(result),clock.instant());}
    public Object operation(String op){
        var actor=context.current();new Fields(map("operationId",op)).text("operationId",8,64,true);
        var result=db.one("SELECT order_id,response_json FROM li_p0_s3_operation WHERE identity_domain=? AND actor_id=? AND store_scope=? AND operation_id=?",actor.domain().name(),actor.id(),scope(actor),op);
        if(result==null)return map("status","NOT_FOUND");auth.order(actor,row(id(result,"order_id")),"order.read");return map("status","COMMITTED","result",object(result.get("response_json")));
    }
    @Transactional(readOnly=true)
    public Object workbench(){
        var actor=context.require(SessionDomain.STORE);Stage1Exception.require(auth.storePermissions(actor.user()).contains("order.read"),403,"FORBIDDEN");
        var now=clock.instant();var today=now.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate();Instant start=today.atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant(),end=today.plusDays(1).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
        long created=0;var turnover=new Stage3Turnover(actor.store(),today);Map<String,Long> counts=new LinkedHashMap<>();for(String phase:List.of("WAIT_PREPARE","WAIT_DISPATCH","WAIT_SHIP","WAIT_PICKUP","WAIT_VERIFY","DELIVERING","DELIVERED","SHIPPED"))counts.put(phase,0L);
        for(var item:db.rows("SELECT o.id FROM li_order o JOIN li_p0_order_snapshot s ON s.order_id=o.id WHERE o.store_id=? AND o.delete_flag=0",actor.store())){
            var row=row(id(item,"id"));Instant at=instant(row.get("create_time"));if(!at.isBefore(start)&&at.isBefore(end))created++;
            JsonObject paid=null;try{paid=eligibility.trustedPaid(id(row,"trade_id"));}catch(Stage1Exception e){if(e.status()>=500)throw e;}
            if(paid!=null)turnover.add(paid);
            var eligible=eligibility.inspect(id(row,"trade_id"));var state=state(row);if(eligible.eligible()&&pointAvailable(row)&&gate.writeBlock()==null&&aftersaleBlock(id(row,"id"))==null&&state!=null){String phase=id(state,"phase");if(counts.containsKey(phase))counts.put(phase,counts.get(phase)+1);}
        }
        context.recheck(actor);return map("storeId",actor.store(),"date",today.toString(),"timezone","Asia/Shanghai","todayOrders",created,"turnoverCent",Long.toString(turnover.amount()),"counts",counts,"countsAvailable",gate.writeBlock()==null,"canVerify",auth.storePermissions(actor.user()).containsAll(Set.of("verification.lookup","verification.confirm")),"canViewMessages",auth.storePermissions(actor.user()).contains("message.read"),"serverTime",now);
    }
    private static String scope(Stage1Context.Actor actor){return actor.domain()==SessionDomain.STORE?actor.store():"0";}
    private static int integer(String s,int min,int max){try{int v=Integer.parseInt(s);if(v<min||v>max)throw new NumberFormatException();return v;}catch(NumberFormatException e){throw new Stage1Exception(422,"QUERY_INVALID");}}
    private static String escape(String s){return s.replace("\\","\\\\").replace("%","\\%").replace("_","\\_");}
    private static String mask(String phone){return phone.length()<7?"****":phone.substring(0,3)+"****"+phone.substring(phone.length()-4);}
}
