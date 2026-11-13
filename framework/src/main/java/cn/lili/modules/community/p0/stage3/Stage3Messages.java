package cn.lili.modules.community.p0.stage3;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.support.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service @ConditionalOnProperty(name="p0.stage3.enabled",havingValue="true")
public class Stage3Messages {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private org.springframework.beans.factory.ObjectProvider<cn.lili.modules.community.p0.stage45.Stage45Messages> stage45;
    private String title(String type){var extension=stage45==null?null:stage45.getIfAvailable();return TITLES.containsKey(type)?TITLES.get(type):extension==null?null:extension.title(type);}
    private final Stage1Db db;private final Stage1Context context;private final Stage3Authorization auth;private final Stage3Orders orders;private final Clock clock;private final TransactionTemplate tx;
    private static final Map<String,String> TITLES=Map.of("READY","订单已可履约","PREPARE","订单已备货","DISPATCH","商户开始配送","DELIVERED","订单已送达，请核实收货","SHIP","订单已发货","COMPLETED","订单已完成","EXCEPTION","订单有履约异常记录");
    public Stage3Messages(Stage1Db db,Stage1Context context,Stage3Authorization auth,Stage3Orders orders,@Qualifier("stage1Clock")Clock clock,PlatformTransactionManager manager){this.db=db;this.context=context;this.auth=auth;this.orders=orders;this.clock=clock;this.tx=new TransactionTemplate(manager);}
    public boolean deliverOne(){
        String[] target={null};
        try{return Boolean.TRUE.equals(tx.execute(status->{var event=db.one("SELECT * FROM li_p0_s3_event WHERE status='PENDING' AND next_attempt_at<=? ORDER BY next_attempt_at,id LIMIT 1 FOR UPDATE SKIP LOCKED",clock.instant());if(event==null)return false;target[0]=id(event,"id");
            String type=id(event,"event_type");Stage1Exception.require(title(type)!=null,409,"MESSAGE_TEMPLATE_UNAVAILABLE");
            deliver(event,"RESIDENT",id(event,"member_id"));deliver(event,"STORE",id(event,"store_id"));
            db.update("UPDATE li_p0_s3_event SET status='DONE',attempts=attempts+1,last_reason=NULL WHERE id=?",event.get("id"));return true;}));}
        catch(RuntimeException failure){if(target[0]!=null)try{tx.execute(status->{db.update("UPDATE li_p0_s3_event SET attempts=attempts+1,last_reason=\'DELIVERY_RETRY\',next_attempt_at=? WHERE id=? AND status=\'PENDING\'",clock.instant().plusSeconds(30),target[0]);return null;});}catch(RuntimeException unavailable){/* Pending row remains the durable recovery fact while storage is unavailable. */}return false;}
    }
    private void deliver(Map<String,Object> event,String domain,String recipient){
        String type=id(event,"event_type");var extension=stage45==null?null:stage45.getIfAvailable();if(type.startsWith("S45_")&&(extension==null||!extension.include(id(event,"id"),domain)))return;if(db.count("SELECT COUNT(*) FROM li_p0_s3_message_link WHERE event_id=? AND recipient_domain=? AND recipient_id=? AND template=?",event.get("id"),domain,recipient,type)>0)return;
        String message=db.id(),receipt=db.id();String title=title(type),content=type.startsWith("S45_")?extension.content(event):"请进入订单详情核对最新状态；通知不代替订单事实。";
        db.update("INSERT INTO li_message(id,create_time,title,content,delete_flag) VALUES(?,?,?,?,0)",message,clock.instant(),title,content);
        if(domain.equals("RESIDENT"))db.update("INSERT INTO li_member_message(id,create_time,member_id,title,content,message_id,status,delete_flag) VALUES(?,?,?,?,?,?,'UN_READY',0)",receipt,clock.instant(),recipient,title,content,message);
        else db.update("INSERT INTO li_store_message(id,create_time,message_id,store_id,status) VALUES(?,?,?,?,'UN_READY')",receipt,clock.instant(),message,recipient);
        db.update("INSERT INTO li_p0_s3_message_link VALUES(?,?,?,?,?,?,?,?)",db.id(),event.get("id"),event.get("order_id"),domain,recipient,type,receipt,clock.instant());
    }
    public Object list(int page,int size){
        var actor=context.current();Stage1Exception.require(page>=1&&size>=1&&size<=100,422,"QUERY_INVALID");
        boolean store=actor.domain()==SessionDomain.STORE;Stage1Exception.require(store||actor.domain()==SessionDomain.RESIDENT,403,"FORBIDDEN");
        if(store)Stage1Exception.require(auth.storePermissions(actor.user()).contains("message.read"),403,"FORBIDDEN");
        String domain=store?"STORE":"RESIDENT",recipient=store?actor.store():actor.id(),table=store?"li_store_message":"li_member_message";
        String from=" FROM li_p0_s3_message_link l JOIN "+table+" r ON r.id=l.native_message_id JOIN li_message m ON m.id=r.message_id JOIN li_order o ON o.id=l.order_id WHERE l.recipient_domain=? AND l.recipient_id=? AND l.template NOT LIKE 'S45_%'";
        long total=db.count("SELECT COUNT(*)"+from,domain,recipient);
        var records=db.rows("SELECT l.id,l.order_id,l.event_id,l.template,l.created_at,r.status,m.title,m.content,o.sn,o.store_name"+from+" ORDER BY l.id DESC LIMIT ? OFFSET ?",domain,recipient,size,(long)(page-1)*size).stream().map(r->map("id",id(r,"id"),"orderId",id(r,"order_id"),"orderSn",r.get("sn"),"storeName",r.get("store_name"),"eventId",id(r,"event_id"),"type",r.get("template"),"at",instant(r.get("created_at")),"read",!"UN_READY".equals(id(r,"status")),"title",r.get("title"),"content",r.get("content"))).toList();
        context.recheck(actor);return map("records",records,"total",total,"pageNumber",page,"pageSize",size,"serverTime",clock.instant());
    }
    public Object read(String id,Object body){
        new Fields(body).only();var actor=context.current();boolean store=actor.domain()==SessionDomain.STORE;Stage1Exception.require(store||actor.domain()==SessionDomain.RESIDENT,403,"FORBIDDEN");
        if(store)Stage1Exception.require(auth.storePermissions(actor.user()).contains("message.read"),403,"FORBIDDEN");
        String domain=store?"STORE":"RESIDENT",recipient=store?actor.store():actor.id();
        return tx.execute(status->{db.required("SELECT id FROM li_p0_consistency_guard WHERE id=1 FOR SHARE");context.recheck(actor);var link=db.required("SELECT * FROM li_p0_s3_message_link WHERE id=? AND recipient_domain=? AND recipient_id=? FOR UPDATE",id,domain,recipient);
            Stage1Exception.require(!id(link,"template").startsWith("S45_"),404,"RESOURCE_NOT_FOUND");orders.authorized(id(link,"order_id"),"order.read");String table=store?"li_store_message":"li_member_message";db.update("UPDATE "+table+" SET status='ALREADY_READY' WHERE id=?",link.get("native_message_id"));context.recheck(actor);return map("id",id,"read",true);});
    }
}
