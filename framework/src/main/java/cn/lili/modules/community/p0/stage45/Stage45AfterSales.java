package cn.lili.modules.community.p0.stage45;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.support.*;
import cn.lili.modules.community.p0.stage2.Stage2Money;
import com.google.gson.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Adapts native AfterSale. Application, goods receipt and channel settlement remain separate facts. */
@Service @ConditionalOnProperty(name="p0.stage45.enabled",havingValue="true")
public class Stage45AfterSales {
    private final Stage45Data d;private final Stage45Files files;
    public Stage45AfterSales(Stage45Data d,Stage45Files files){this.d=d;this.files=files;}
    public Map<String,Object> row(String id){return d.db.required("SELECT * FROM li_p0_s45_aftersale WHERE id=?",id);}
    public JsonObject line(Map<String,Object> order,Map<String,Object> item){
        for(var raw:object(order.get("payload_json")).getAsJsonObject("order").getAsJsonArray("items")){
            var line=raw.getAsJsonObject();if(line.get("skuId").getAsString().equals(id(item,"sku_id"))){
                Stage1Exception.require(line.get("quantity").getAsLong()==number(item,"num"),409,"ITEM_SNAPSHOT_CONFLICT");return line;
            }
        }throw new Stage1Exception(409,"ITEM_SNAPSHOT_MISSING");
    }
    private List<Map<String,Object>> shares(Map<String,Object> order,Map<String,Object> item,boolean create){
        var line=line(order,item);var amounts=Stage45Money.shares(Math.multiplyExact(line.get("unitPriceCent").getAsLong(),line.get("quantity").getAsInt()),line.get("quantity").getAsInt());
        if(create)for(int i=0;i<amounts.size();i++)d.db.update("INSERT IGNORE INTO li_p0_s45_share(item_id,share_no,amount_cent) VALUES(?,?,?)",item.get("id"),i+1,amounts.get(i));
        var stored=d.db.rows("SELECT * FROM li_p0_s45_share WHERE item_id=? ORDER BY share_no"+(create?" FOR UPDATE":""),item.get("id"));
        if(stored.isEmpty()){var preview=new ArrayList<Map<String,Object>>();for(int i=0;i<amounts.size();i++)preview.add(map("share_no",i+1,"amount_cent",amounts.get(i),"state","AVAILABLE"));return preview;}
        Stage1Exception.require(stored.size()==amounts.size(),409,"SHARE_ALLOCATION_CONFLICT");
        for(int i=0;i<amounts.size();i++)Stage1Exception.require(number(stored.get(i),"share_no")==i+1&&number(stored.get(i),"amount_cent")==amounts.get(i),409,"SHARE_ALLOCATION_CONFLICT");
        return stored;
    }
    @Transactional(readOnly=true)
    public Object eligibility(String orderId){
        var order=d.order(orderId,"aftersale.read");String reason=null;
        try{d.facts(order,true);}catch(Stage1Exception e){if(e.status()>=500)throw e;reason=e.code();}
        var state=d.orders.state(order);Instant completed=state==null?null:instant(state.get("completed_at")),deadline=Stage45Money.deadline(completed);
        if(reason==null&&!Stage45Money.withinDeadline(d.clock.instant(),completed))reason="AFTERSALE_DEADLINE";
        boolean delivered=d.delivered(order);var rows=new ArrayList<Object>();
        for(var item:d.db.rows("SELECT * FROM li_order_item WHERE order_sn=? AND delete_flag=0 ORDER BY id",order.get("sn"))){
            var line=line(order,item);var available=shares(order,item,false).stream().filter(s->"AVAILABLE".equals(id(s,"state"))).toList();
            long consumedBeforeCompletion=completed==null?0:d.db.count("SELECT COALESCE(SUM(a.quantity),0) FROM li_p0_s45_aftersale a JOIN li_p0_s45_obligation x ON x.after_sale_id=a.id AND x.kind='GOODS' AND x.state='SUCCEEDED' WHERE a.item_id=? AND a.state='COMPLETE' AND a.delivered=0 AND x.succeeded_at<=?",item.get("id"),completed);
            boolean evaluated=d.db.count("SELECT COUNT(*) FROM li_member_evaluation WHERE p0_order_item_id=?",item.get("id"))>0;
            rows.add(map("orderItemId",id(item,"id"),"skuId",id(item,"sku_id"),"goodsId",id(item,"goods_id"),"name",line.get("name"),"specifications",line.get("specifications"),"image",line.get("image"),"originalQuantity",item.get("num"),
                "availableQuantity",available.size(),"occupiedQuantity",shares(order,item,false).stream().filter(s->"HELD".equals(id(s,"state"))).count(),"consumedQuantity",shares(order,item,false).stream().filter(s->"CONSUMED".equals(id(s,"state"))).count(),"availableSharesCent",available.stream().map(s->number(s,"amount_cent")).toList(),"delivered",delivered,"types",delivered?List.of("REFUND","RETURN_REFUND"):List.of("REFUND"),
                "canEvaluate",reason==null&&completed!=null&&number(item,"num")>consumedBeforeCompletion&&!evaluated,"evaluated",evaluated));
        }
        // Evaluation has no aftersale deadline.
        for(var raw:rows){@SuppressWarnings("unchecked") var value=(Map<String,Object>)raw;if("AFTERSALE_DEADLINE".equals(reason)&&completed!=null&&!Boolean.TRUE.equals(value.get("evaluated"))) {
            var item=d.item(order,String.valueOf(value.get("orderItemId")));long before=d.db.count("SELECT COALESCE(SUM(a.quantity),0) FROM li_p0_s45_aftersale a JOIN li_p0_s45_obligation x ON x.after_sale_id=a.id AND x.kind='GOODS' AND x.state='SUCCEEDED' WHERE a.item_id=? AND a.state='COMPLETE' AND a.delivered=0 AND x.succeeded_at<=?",item.get("id"),completed);
            value.put("canEvaluate",number(item,"num")>before);
        }}
        return map("orderId",orderId,"orderSn",id(order,"sn"),"storeName",order.get("store_name"),"eligible",reason==null,"blockReason",reason,"delivered",delivered,"sent",d.sent(order),"completedAt",completed,"deadlineExclusive",deadline,"timezone","Asia/Shanghai","items",rows,"reasonOptions",map("REFUND",d.db.rows("SELECT DISTINCT reason FROM li_after_sale_reason WHERE service_type='RETURN_MONEY' AND delete_flag=0 ORDER BY reason").stream().map(r->id(r,"reason")).toList(),"RETURN_REFUND",d.db.rows("SELECT DISTINCT reason FROM li_after_sale_reason WHERE service_type='RETURN_GOODS' AND delete_flag=0 ORDER BY reason").stream().map(r->id(r,"reason")).toList()),"feePolicy","未发出、未交付且全部商品退款成功后，独立核定原配送费；本申请只含商品退款。","serverTime",d.clock.instant());
    }
    public Object apply(String orderId,Object body){
        d.context.require(SessionDomain.RESIDENT);var f=new Fields(body).only("operationId","orderItemId","quantity","amountCent","type","reason","description","files");
        String itemId=f.id("orderItemId",true),type=f.choice("type",Set.of("REFUND","RETURN_REFUND"),true),reason=f.text("reason",2,80,true),description=f.text("description",0,255,false);
        long quantity=f.integer("quantity",1,99,true),amount=f.integer("amountCent",1,Stage2Money.MAX_SAFE_CENT,true);var refs=f.ids("files",0,10,false);
        var order=d.order(orderId,"aftersale.apply");
        return d.command(order,"aftersale.apply",orderId,body,()->{
            var current=d.order(orderId,"aftersale.apply");d.facts(current,true);var state=d.orders.state(current);
            Stage1Exception.require(Stage45Money.withinDeadline(d.clock.instant(),state==null?null:instant(state.get("completed_at"))),409,"AFTERSALE_DEADLINE");
            Stage1Exception.require(d.db.count("SELECT COUNT(*) FROM li_after_sale_reason WHERE reason=? AND service_type=? AND delete_flag=0",reason,type.equals("REFUND")?"RETURN_MONEY":"RETURN_GOODS")>0,422,"AFTERSALE_REASON_INVALID");Stage1Exception.require(!reason.equals("其他")||description!=null&&description.strip().length()>=2,422,"AFTERSALE_DESCRIPTION_REQUIRED");
            boolean delivered=d.delivered(current);Stage1Exception.require(delivered||type.equals("REFUND"),409,"RETURN_NOT_DELIVERED");
            var item=d.item(current,itemId);var selected=shares(current,item,true).stream().filter(s->"AVAILABLE".equals(id(s,"state"))).limit(quantity).toList();
            Stage1Exception.require(selected.size()==quantity,409,"AFTERSALE_QUANTITY_OCCUPIED");
            long maximum=selected.stream().mapToLong(s->number(s,"amount_cent")).sum();
            Stage1Exception.require(amount<=maximum&&(delivered||amount==maximum),422,"REFUND_AMOUNT_INVALID");
            String sale=d.db.id();var line=line(current,item);var numbers=selected.stream().map(s->number(s,"share_no")).toList();
            d.db.update("INSERT INTO li_after_sale(id,create_time,delete_flag,apply_refund_price,goods_id,goods_image,goods_name,member_id,num,order_sn,problem_desc,reason,refund_way,store_id,store_name,service_status,service_type,sku_id,sn,specs,trade_sn,order_item_sn) VALUES(?,?,0,?,?,?,?,?,?,?,?,?,'ORIGINAL',?,?,'APPLY',?,?,?,?,?,?)",
                sale,d.clock.instant(),money(amount),item.get("goods_id"),line.get("image").getAsString(),line.get("name").getAsString(),current.get("member_id"),quantity,current.get("sn"),description,reason,current.get("store_id"),current.get("store_name"),type.equals("REFUND")?"RETURN_MONEY":"RETURN_GOODS",item.get("sku_id"),"S45"+sale,write(line.get("specifications")),current.get("trade_sn"),item.get("sn"));
            d.db.update("INSERT INTO li_p0_s45_aftersale(id,order_id,item_id,member_id,store_id,type,quantity,goods_cent,state,delivered,reason,evidence_json,share_numbers,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?,'APPLY',?,?,?,?,?,?)",
                sale,orderId,itemId,current.get("member_id"),current.get("store_id"),type,quantity,amount,delivered,reason+(description==null||description.isBlank()?"":"\n"+description),write(refs),write(numbers),d.clock.instant(),d.clock.instant());
            for(var s:selected)d.db.cas(d.db.update("UPDATE li_p0_s45_share SET state='HELD',after_sale_id=? WHERE item_id=? AND share_no=? AND state='AVAILABLE'",sale,itemId,s.get("share_no")));
            files.bind(refs,current,"AFTERSALE",sale);d.bump(orderId);
            d.audit(current,"AFTERSALE",sale,"APPLY",reason,null,map("quantity",quantity,"goodsCent",amount,"shares",numbers));
            d.event(current,"AFTERSALE",sale,"S45_AS_APPLY","aftersale.read",true,map("afterSaleId",sale));
            return view(row(sale),true);
        });
    }
    public Object act(String sale,String action,Object body){
        var f=new Fields(body).only("operationId","expectedVersion","reason","files","returnGuide","returnInfo","decision","feeCent");
        long expected=f.version();String reason=f.text("reason",action.equals("receive")?0:2,500,Set.of("reject","dispute","exception","resolve","evidence").contains(action));var refs=f.ids("files",0,10,false);
        String permission=switch(action){case "approve","reject"->"aftersale.review";case "receive","exception"->"aftersale.receive";case "resolve"->"aftersale.resolve";default->"aftersale."+action;};
        var original=row(sale);var order=d.order(id(original,"order_id"),permission);
        return d.command(order,permission+":"+action,sale,body,()->{
            var current=row(sale);d.version(current,expected);d.auth.order(d.context.current(),order,permission);
            String state=id(current,"state"),dispute=id(current,"dispute"),next=state,nextDispute=dispute;
            boolean hasObligation=d.db.count("SELECT COUNT(*) FROM li_p0_s45_obligation WHERE after_sale_id=?",sale)>0;
            switch(action){
                case "cancel" -> {
                    Stage1Exception.require((state.equals("APPLY")||state.equals("WAIT_RETURN")&&current.get("return_submission")==null)&&!hasObligation&&dispute.equals("NONE"),409,"AFTERSALE_CANNOT_CANCEL");
                    next="CANCELLED";release(current);
                }
                case "reject" -> {
                    Stage1Exception.require(state.equals("APPLY")&&dispute.equals("NONE")&&!hasObligation,409,"AFTERSALE_STATE_CONFLICT");next="REJECTED";release(current);
                }
                case "approve" -> {
                    Stage1Exception.require(state.equals("APPLY")&&dispute.equals("NONE"),409,"AFTERSALE_STATE_CONFLICT");
                    next=approve(order,current,f);
                }
                case "return" -> {
                    Stage1Exception.require(Set.of("WAIT_RETURN","WAIT_RECEIVE").contains(state)&&!hasObligation&&dispute.equals("NONE"),409,"RETURN_STATE_CONFLICT");
                    var guide=object(current.get("return_snapshot"));var info=new Fields(f.child("returnInfo",true)).only("method","company","number","note");
                    String method=info.choice("method",Set.of("LOGISTICS","OFFLINE"),true);
                    Stage1Exception.require(method.equals(guide.get("method").getAsString()),422,"RETURN_METHOD_CONFLICT");
                    if(method.equals("LOGISTICS")){info.text("company",1,80,true);String tracking=info.text("number",1,80,true);Stage1Exception.require(tracking.matches("[A-Za-z0-9-]{1,80}"),422,"WAYBILL_INVALID");}
                    else {Stage1Exception.require(!info.has("company")&&!info.has("number"),422,"FIELD_INVALID");info.text("note",2,500,true);}
                    d.db.update("UPDATE li_p0_s45_aftersale SET return_submission=? WHERE id=?",write(info.json()),sale);next="WAIT_RECEIVE";
                }
                case "receive" -> {
                    Stage1Exception.require(state.equals("WAIT_RECEIVE")&&dispute.equals("NONE"),409,"RETURN_STATE_CONFLICT");
                    obligation(order,current);next="WAIT_FUNDS";
                }
                case "exception" -> {
                    Stage1Exception.require(state.equals("WAIT_RECEIVE"),409,"RETURN_STATE_CONFLICT");nextDispute="OPEN";
                }
                case "dispute" -> {
                    Stage1Exception.require(Set.of("REJECTED","WAIT_RETURN","WAIT_RECEIVE","WAIT_FUNDS","COMPLETE").contains(state)&&dispute.equals("NONE"),409,"DISPUTE_STATE_CONFLICT");
                    if(state.equals("REJECTED"))reoccupy(current);nextDispute="OPEN";
                }
                case "evidence" -> Stage1Exception.require(Set.of("OPEN","NEED_EVIDENCE").contains(dispute),409,"DISPUTE_STATE_CONFLICT");
                case "resolve" -> {
                    Stage1Exception.require(Set.of("OPEN","NEED_EVIDENCE").contains(dispute),409,"DISPUTE_STATE_CONFLICT");
                    String decision=f.choice("decision",Set.of("APPROVE","MAINTAIN","NEED_EVIDENCE"),true);
                    if(decision.equals("NEED_EVIDENCE"))nextDispute="NEED_EVIDENCE";
                    else {
                        nextDispute="NONE";
                        if(decision.equals("MAINTAIN")&&state.equals("REJECTED"))release(current);
                        if(decision.equals("APPROVE")&&state.equals("REJECTED"))next=approve(order,current,f);
                        // An existing physical-return/finance condition is never bypassed by a platform decision.
                        if(f.has("feeCent")){
                            Stage1Exception.require(decision.equals("APPROVE"),422,"FEE_DECISION_REQUIRED");fee(order,sale,f.integer("feeCent",1,Stage2Money.MAX_SAFE_CENT,true),reason);
                        }
                    }
                }
                default -> throw new Stage1Exception(404,"RESOURCE_NOT_FOUND");
            }
            if(!refs.isEmpty()){files.bind(refs,order,"AFTERSALE",sale);var all=array(current.get("evidence_json"));var seen=new HashSet<String>();all.forEach(e->seen.add(e.getAsString()));for(String ref:refs)if(seen.add(ref))all.add(ref);Stage1Exception.require(all.size()<=30,422,"EVIDENCE_LIMIT");d.db.update("UPDATE li_p0_s45_aftersale SET evidence_json=? WHERE id=?",write(all),sale);}
            d.db.cas(d.db.update("UPDATE li_p0_s45_aftersale SET state=?,dispute=?,version=version+1,updated_at=? WHERE id=? AND version=?",next,nextDispute,d.clock.instant(),sale,expected));
            syncNative(sale,next);d.bump(id(order,"id"));
            var after=row(sale);d.audit(order,"AFTERSALE",sale,action,reason,map("state",state,"dispute",dispute,"returnSubmission",current.get("return_submission")),map("state",next,"dispute",nextDispute,"returnSubmission",after.get("return_submission"),"files",refs));
            d.db.update("INSERT INTO li_after_sale_log(id,create_time,message,operator_id,operator_name,operator_type,sn) VALUES(?,?,?,?,?,?,?)",d.db.id(),d.clock.instant(),action,d.context.current().id(),"Stage45",d.context.current().domain().name(),"S45"+sale);
            d.event(order,"AFTERSALE",sale,"S45_AS_UPDATE","aftersale.read",true,map("afterSaleId",sale,"state",next,"dispute",nextDispute));
            return view(after,true);
        });
    }
    private String approve(Map<String,Object> order,Map<String,Object> sale,Fields fields){
        if("REFUND".equals(id(sale,"type"))){d.db.update("UPDATE li_p0_s45_aftersale SET approved_at=? WHERE id=?",d.clock.instant(),sale.get("id"));obligation(order,sale);return "WAIT_FUNDS";}
        var g=new Fields(fields.child("returnGuide",true)).only("method","contact","phone","address","instructions");
        g.choice("method",Set.of("LOGISTICS","OFFLINE"),true);g.text("contact",1,40,true);String phone=g.text("phone",6,24,true);Stage1Exception.require(phone.matches("[+0-9 -]{6,24}"),422,"PHONE_INVALID");
        g.text("address",2,255,true);g.text("instructions",0,500,false);
        d.db.update("UPDATE li_p0_s45_aftersale SET return_snapshot=?,approved_at=? WHERE id=?",write(g.json()),d.clock.instant(),sale.get("id"));return "WAIT_RETURN";
    }
    private void obligation(Map<String,Object> order,Map<String,Object> sale){
        String source="GOODS:"+id(sale,"id");
        d.db.update("INSERT IGNORE INTO li_p0_s45_obligation(id,source_key,order_id,after_sale_id,kind,amount_cent,quantity,basis_json,created_at) VALUES(?,?,?,?,'GOODS',?,?,?,?)",
            d.db.id(),source,order.get("id"),sale.get("id"),sale.get("goods_cent"),sale.get("quantity"),write(map("snapshotHash",order.get("content_hash"),"shares",array(sale.get("share_numbers")))),d.clock.instant());
        var obligation=d.db.required("SELECT amount_cent,quantity FROM li_p0_s45_obligation WHERE source_key=?",source);
        Stage1Exception.require(number(obligation,"amount_cent")==number(sale,"goods_cent")&&number(obligation,"quantity")==number(sale,"quantity"),409,"OBLIGATION_CONFLICT");
    }
    private void fee(Map<String,Object> order,String sale,long amount,String reason){
        var snapshot=d.facts(order,true);long original=snapshot.getAsJsonObject("order").get("freightCent").getAsLong();
        long occupied=d.db.count("SELECT COALESCE(SUM(amount_cent),0) FROM li_p0_s45_obligation WHERE order_id=? AND kind='FEE'",order.get("id"));
        Stage1Exception.require(amount<=original-occupied,409,"FEE_LIMIT");
        d.db.update("INSERT INTO li_p0_s45_obligation(id,source_key,order_id,after_sale_id,kind,amount_cent,basis_json,created_at) VALUES(?,?,?,?,'FEE',?,?,?)",d.db.id(),"FEE_DISPUTE:"+sale,order.get("id"),sale,amount,write(map("afterSaleId",sale,"reason",reason,"originalFeeCent",original,"decisionActor",d.context.current().id())),d.clock.instant());
    }
    private void release(Map<String,Object> sale){d.db.update("UPDATE li_p0_s45_share SET state='AVAILABLE',after_sale_id=NULL WHERE after_sale_id=? AND state='HELD'",sale.get("id"));}
    private void reoccupy(Map<String,Object> sale){
        for(var n:array(sale.get("share_numbers"))){
            var share=d.db.required("SELECT * FROM li_p0_s45_share WHERE item_id=? AND share_no=? FOR UPDATE",sale.get("item_id"),n.getAsInt());
            Stage1Exception.require("AVAILABLE".equals(id(share,"state")),409,"ORIGINAL_SHARE_UNAVAILABLE");
            d.db.cas(d.db.update("UPDATE li_p0_s45_share SET state='HELD',after_sale_id=? WHERE item_id=? AND share_no=? AND state='AVAILABLE'",sale.get("id"),sale.get("item_id"),n.getAsInt()));
        }
    }
    public void syncNative(String sale,String state){
        String nativeState=switch(state){case "APPLY"->"APPLY";case "REJECTED"->"REFUSE";case "CANCELLED"->"BUYER_CANCEL";case "WAIT_RETURN"->"PASS";case "WAIT_RECEIVE"->"BUYER_RETURN";case "WAIT_FUNDS"->"WAIT_REFUND";case "COMPLETE"->"COMPLETE";default->throw new Stage1Exception(409,"AFTERSALE_STATE_CONFLICT");};
        d.db.update("UPDATE li_after_sale SET service_status=?,update_time=? WHERE id=?",nativeState,d.clock.instant(),sale);
    }
    @Transactional(readOnly=true)
    public Object detail(String sale){var row=row(sale);d.order(id(row,"order_id"),"aftersale.read");return view(row,true);}
    public Map<String,Object> view(Map<String,Object> sale,boolean detail){
        var order=d.order(id(sale,"order_id"),"aftersale.read");var item=d.item(order,id(sale,"item_id"));var line=line(order,item);
        var obligation=d.db.one("SELECT state,succeeded_at,amount_cent,batch_id FROM li_p0_s45_obligation WHERE after_sale_id=? AND kind='GOODS'",sale.get("id"));
        String funding=obligation==null?"NOT_READY":id(obligation,"state");
        if(obligation!=null&&obligation.get("batch_id")!=null){var task=d.db.one("SELECT state FROM li_p0_s45_task WHERE batch_id=? AND kind='REFUND'",obligation.get("batch_id"));funding=task==null?"WAIT_RETURN_FUNDS":id(task,"state");}
        var actor=d.context.current();var permissions=d.auth.permissions(actor.user());String state=id(sale,"state"),dispute=id(sale,"dispute");var actions=new ArrayList<String>();
        if(actor.domain()==SessionDomain.RESIDENT){
            if(dispute.equals("NONE")&&(state.equals("APPLY")||state.equals("WAIT_RETURN")&&sale.get("return_submission")==null))actions.add("cancel");
            if(dispute.equals("NONE")&&Set.of("WAIT_RETURN","WAIT_RECEIVE").contains(state))actions.add("return");
            if(dispute.equals("NONE")&&Set.of("REJECTED","WAIT_RETURN","WAIT_RECEIVE","WAIT_FUNDS","COMPLETE").contains(state))actions.add("dispute");
            if(Set.of("OPEN","NEED_EVIDENCE").contains(dispute))actions.add("evidence");
        }
        if(actor.domain()==SessionDomain.STORE&&dispute.equals("NONE")){
            if(state.equals("APPLY")&&permissions.contains("aftersale.review"))actions.addAll(List.of("approve","reject"));
            if(state.equals("WAIT_RECEIVE")&&permissions.contains("aftersale.receive"))actions.addAll(List.of("receive","exception"));
        }
        if(actor.domain()==SessionDomain.MANAGER&&Set.of("OPEN","NEED_EVIDENCE").contains(dispute)&&permissions.contains("aftersale.resolve"))actions.add("resolve");
        var view=map("id",id(sale,"id"),"sn","S45"+id(sale,"id"),"orderId",id(order,"id"),"orderSn",id(order,"sn"),"tradeSn",id(order,"trade_sn"),"orderItemId",id(item,"id"),"storeId",id(order,"store_id"),"storeName",order.get("store_name"),"goodsName",line.get("name"),"specifications",line.get("specifications"),"image",line.get("image"),
            "type",sale.get("type"),"quantity",sale.get("quantity"),"amountCent",sale.get("goods_cent"),"state",state,"dispute",dispute,"version",sale.get("version"),"reason",sale.get("reason"),"createdAt",instant(sale.get("created_at")),"approvedAt",instant(sale.get("approved_at")),"updatedAt",instant(sale.get("updated_at")),"fundingState",funding,"refundedCent",obligation!=null&&"SUCCEEDED".equals(id(obligation,"state"))?obligation.get("amount_cent"):0,"refundedAt",instant(sale.get("refunded_at")),"localAppliedAt",obligation==null?null:instant(obligation.get("succeeded_at")),"actions",actions);
        if(detail){
            view.put("files",array(sale.get("evidence_json")));view.put("returnGuide",sale.get("return_snapshot")==null?null:object(sale.get("return_snapshot")));view.put("returnInfo",sale.get("return_submission")==null?null:object(sale.get("return_submission")));
            view.put("timeline",d.db.rows("SELECT action,actor_domain,reason,created_at FROM li_p0_s45_audit WHERE subject_type='AFTERSALE' AND subject_id=? ORDER BY id",sale.get("id")).stream().map(r->map("action",r.get("action"),"actor",r.get("actor_domain"),"reason",r.get("reason"),"at",instant(r.get("created_at")))).toList());
            view.put("feeObligations",d.db.rows("SELECT id,amount_cent,state,succeeded_at FROM li_p0_s45_obligation WHERE order_id=? AND kind='FEE' ORDER BY id",order.get("id")).stream().map(r->map("id",id(r,"id"),"amountCent",r.get("amount_cent"),"state",r.get("state"),"succeededAt",instant(r.get("succeeded_at")))).toList());
            view.put("overdue",state.equals("APPLY")&&instant(sale.get("created_at")).plusSeconds(48*3600).isBefore(d.clock.instant())||state.equals("WAIT_RETURN")&&sale.get("approved_at")!=null&&instant(sale.get("approved_at")).plusSeconds(7*86400).isBefore(d.clock.instant()));
        }return view;
    }
    @Transactional(readOnly=true)
    public Object list(Map<String,String> query){
        var actor=d.context.current();Stage1Exception.require(d.auth.permissions(actor.user()).contains("aftersale.read"),403,"FORBIDDEN");
        int page=Stage45Data.page(query,"pageNumber",1,1000000),size=Stage45Data.page(query,"pageSize",20,50);var args=new ArrayList<Object>();
        String where=d.auth.scope(actor,"a",args);String status=query.get("state"),type=query.get("type"),q=query.get("q");
        if(query.containsKey("orderId")&&!query.get("orderId").isBlank()){String orderId=query.get("orderId");Stage1Exception.require(orderId.matches("[1-9][0-9]{0,18}"),422,"QUERY_INVALID");where+=" AND a.order_id=?";args.add(orderId);}
        if(status!=null&&!status.isBlank()){Stage1Exception.require(Set.of("APPLY","WAIT_RETURN","WAIT_RECEIVE","WAIT_FUNDS","COMPLETE","REJECTED","CANCELLED","DISPUTE").contains(status),422,"QUERY_INVALID");if(status.equals("DISPUTE"))where+=" AND a.dispute IN ('OPEN','NEED_EVIDENCE')";else{where+=" AND a.state=?";args.add(status);}}
        if(type!=null&&!type.isBlank()){Stage1Exception.require(Set.of("REFUND","RETURN_REFUND").contains(type),422,"QUERY_INVALID");where+=" AND a.type=?";args.add(type);}
        if(q!=null&&!q.isBlank()){Stage1Exception.require(q.length()<=80,422,"QUERY_INVALID");where+=" AND (n.sn LIKE ? OR n.order_sn LIKE ? OR n.store_name LIKE ? OR n.goods_name LIKE ?)";for(int i=0;i<4;i++)args.add("%"+q.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%");}
        for(String key:List.of("from","to"))if(query.containsKey(key)&&!query.get(key).isBlank()){try{Instant at=Instant.parse(query.get(key));where+=" AND a.created_at"+(key.equals("from")?">=":"<")+"?";args.add(at);}catch(DateTimeException e){throw new Stage1Exception(422,"QUERY_INVALID");}}
        String from=" FROM li_p0_s45_aftersale a JOIN li_after_sale n ON n.id=a.id WHERE "+where;
        long total=d.db.count("SELECT COUNT(*)"+from,args.toArray());args.add(size);args.add((long)(page-1)*size);
        var result=d.db.rows("SELECT a.*"+from+" ORDER BY a.id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(r->view(r,false)).toList();
        d.context.recheck(actor);return Stage45Data.page(result,total,page,size);
    }
}
