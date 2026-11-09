package cn.lili.modules.community.p0.stage45;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.stage3.Stage3Authorization;
import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Stage45 templates and permission projection on the existing Stage3 outbox/native message receipts. */
@Service @ConditionalOnProperty(name="p0.stage45.enabled",havingValue="true")
public class Stage45Messages {
    private final Stage45Data d;private final Stage3Authorization stage3;
    public Stage45Messages(Stage45Data d,Stage3Authorization stage3){this.d=d;this.stage3=stage3;}
    private static final Map<String,String> TITLES=Map.ofEntries(Map.entry("S45_AS_APPLY","收到新的售后申请"),Map.entry("S45_AS_UPDATE","售后处理进度更新"),Map.entry("S45_FEE_READY","配送费已进入独立退款处理"),Map.entry("S45_REFUND_SUCCESS","本项退款已成功"),Map.entry("S45_FUNDS_UPDATE","本订单资金处理更新"),Map.entry("S45_EVALUATION","新增商品评价"),Map.entry("S45_EVALUATION_STATUS","评价展示状态已更新"));
    public String title(String type){return TITLES.get(type);}
    public boolean include(String event,String domain){var link=d.db.required("SELECT resident_visible FROM li_p0_s45_event_link WHERE event_id=?",event);return !domain.equals("RESIDENT")||flag(link,"resident_visible");}
    public String content(Map<String,Object> event){
        var payload=object(event.get("payload_json"));
        if("S45_REFUND_SUCCESS".equals(id(event,"event_type"))&&payload.has("amountCent"))return (payload.has("kind")&&"FEE".equals(payload.get("kind").getAsString())?"本项配送费退款 ¥":"本项商品退款 ¥")+money(payload.get("amountCent").getAsLong()).toPlainString()+" 已取得成功结果，请进入详情核对原退款及各项进度。";
        if("S45_FEE_READY".equals(id(event,"event_type")))return "商品退款已完成，配送费退款单独处理；请查看原订单最新进度。";
        return "请进入对应详情核对当前事实及下一步；审核同意、提交退回或资金受理均不等于退款成功。";
    }
    private String conditions(Stage1Context.Actor actor,List<Object> args){
        boolean store=actor.domain()==SessionDomain.STORE;Stage1Exception.require(store||actor.domain()==SessionDomain.RESIDENT,403,"FORBIDDEN");
        var permissions=d.auth.permissions(actor.user());Stage1Exception.require(permissions.contains("message.read"),403,"FORBIDDEN");
        args.add(store?"STORE":"RESIDENT");args.add(store?actor.store():actor.id());
        String where="l.recipient_domain=? AND l.recipient_id=?";
        if(store){
            boolean old=stage3.storePermissions(actor.user()).containsAll(Set.of("message.read","order.read"));var actions=new TreeSet<>(permissions);
            where+=" AND ((a.event_id IS NULL AND "+(old?"1=1":"1=0")+") OR a.required_store_action IN ("+String.join(",",Collections.nCopies(actions.size(),"?"))+"))";args.addAll(actions);
        }else where+=" AND (a.event_id IS NULL OR a.resident_visible=1)";
        return where;
    }
    public Object list(Map<String,String> query){
        var actor=d.context.current();int page=Stage45Data.page(query,"pageNumber",1,1000000),size=Stage45Data.page(query,"pageSize",20,50);var args=new ArrayList<Object>();String where=conditions(actor,args),nativeTable=actor.domain()==SessionDomain.STORE?"li_store_message":"li_member_message";
        String from=" FROM li_p0_s3_message_link l JOIN "+nativeTable+" r ON r.id=l.native_message_id JOIN li_message m ON m.id=r.message_id JOIN li_order o ON o.id=l.order_id LEFT JOIN li_p0_s45_event_link a ON a.event_id=l.event_id WHERE "+where;
        long total=d.db.count("SELECT COUNT(*)"+from,args.toArray());args.add(size);args.add((long)(page-1)*size);
        var records=d.db.rows("SELECT l.id,l.order_id,l.template,l.created_at,r.status,m.title,m.content,o.sn,o.store_name,a.subject_type,a.subject_id,a.required_store_action"+from+" ORDER BY l.id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(r->map("id",id(r,"id"),"orderId",id(r,"order_id"),"orderSn",r.get("sn"),"storeName",r.get("store_name"),"type",r.get("template"),"at",instant(r.get("created_at")),"read",!"UN_READY".equals(id(r,"status")),"title",r.get("title"),"content",r.get("content"),"subjectType",actor.domain()==SessionDomain.STORE&&"funds.read".equals(r.get("required_store_action"))?"FUNDS":r.get("subject_type")==null?"ORDER":r.get("subject_type"),"subjectId",r.get("subject_id")==null?id(r,"order_id"):id(r,"subject_id"))).toList();
        var countArgs=new ArrayList<Object>();String scope=d.auth.scope(actor,"x",countArgs);
        var counts=d.auth.permissions(actor.user()).contains("aftersale.read")?d.db.rows("SELECT x.state,COUNT(*) AS n FROM li_p0_s45_aftersale x WHERE "+scope+" AND (x.state IN ('APPLY','WAIT_RETURN','WAIT_RECEIVE','WAIT_FUNDS') OR x.dispute IN ('OPEN','NEED_EVIDENCE')) GROUP BY x.state",countArgs.toArray()):List.<Map<String,Object>>of();
        var todos=new ArrayList<Object>();counts.forEach(r->todos.add(map("state",r.get("state"),"count",r.get("n"))));
        if(actor.domain()==SessionDomain.STORE&&d.auth.permissions(actor.user()).contains("funds.read")){
            long pending=d.db.count("SELECT COUNT(*) FROM li_p0_s45_task t JOIN li_order o ON o.id=t.order_id WHERE o.store_id=? AND t.applied=0 AND t.state<>'ABANDONED'",actor.store());
            long issues=d.db.count("SELECT COUNT(*) FROM li_p0_s45_exception x JOIN li_order o ON o.id=x.order_id WHERE o.store_id=? AND x.state='OPEN'",actor.store());
            if(pending>0)todos.add(map("state","FUNDS_PENDING","count",pending));if(issues>0)todos.add(map("state","FUNDS_EXCEPTION","count",issues));
        }
        var result=Stage45Data.page(records,total,page,size);result.put("todos",todos);d.context.recheck(actor);return result;
    }
    public Object read(String id,Object body){
        new Fields(body).only();var actor=d.context.current();var args=new ArrayList<Object>();String where=conditions(actor,args);args.add(id);
        var row=d.db.required("SELECT l.* FROM li_p0_s3_message_link l LEFT JOIN li_p0_s45_event_link a ON a.event_id=l.event_id WHERE "+where+" AND l.id=?",args.toArray());var order=d.orders.row(id(row,"order_id"));
        return d.locked(order,()->{d.context.recheck(actor);var verifyArgs=new ArrayList<Object>();String verify=conditions(actor,verifyArgs);verifyArgs.add(id);d.db.required("SELECT l.id FROM li_p0_s3_message_link l LEFT JOIN li_p0_s45_event_link a ON a.event_id=l.event_id WHERE "+verify+" AND l.id=?",verifyArgs.toArray());
            String table=actor.domain()==SessionDomain.STORE?"li_store_message":"li_member_message";d.db.update("UPDATE "+table+" SET status='ALREADY_READY' WHERE id=?",row.get("native_message_id"));return map("id",id,"read",true);
        });
    }
}
