package cn.lili.modules.community.p0.stage45;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.math.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service @ConditionalOnProperty(name="p0.stage45.enabled",havingValue="true")
public class Stage45Evaluations {
    private final Stage45Data d;private final Stage45Files files;private final Stage45AfterSales afterSales;
    public Stage45Evaluations(Stage45Data d,Stage45Files files,Stage45AfterSales afterSales){this.d=d;this.files=files;this.afterSales=afterSales;}
    public Object submit(String orderId,Object body){
        d.context.require(SessionDomain.RESIDENT);var f=new Fields(body).only("operationId","orderItemId","star","content","files");String itemId=f.id("orderItemId",true),content=f.text("content",0,500,false);long star=f.integer("star",1,5,true);var refs=f.ids("files",0,10,false);
        var order=d.order(orderId,"evaluation.write");return d.command(order,"evaluation.write",orderId,body,()->{
            d.facts(order,true);var state=d.orders.state(order);Stage1Exception.require(state!=null&&"COMPLETED".equals(id(state,"phase"))&&state.get("completed_at")!=null,409,"EVALUATION_REQUIRES_COMPLETION");
            var item=d.item(order,itemId);long removed=d.db.count("SELECT COALESCE(SUM(a.quantity),0) FROM li_p0_s45_aftersale a JOIN li_p0_s45_obligation x ON x.after_sale_id=a.id AND x.kind='GOODS' AND x.state='SUCCEEDED' WHERE a.item_id=? AND a.state='COMPLETE' AND a.delivered=0 AND x.succeeded_at<=?",itemId,instant(state.get("completed_at")));
            Stage1Exception.require(number(item,"num")>removed,409,"EVALUATION_NO_COMPLETED_QUANTITY");
            Stage1Exception.require(d.db.count("SELECT COUNT(*) FROM li_member_evaluation WHERE p0_order_item_id=?",itemId)==0,409,"EVALUATION_ALREADY_SUBMITTED");
            var line=afterSales.line(order,item);var member=d.db.required("SELECT nick_name,face FROM li_member WHERE id=?",order.get("member_id"));String evaluation=d.db.id();
            d.db.update("INSERT INTO li_member_evaluation(id,create_time,delete_flag,content,goods_id,goods_image,goods_name,have_image,images,member_id,member_name,member_profile,order_no,store_id,sku_id,status,reply_status,top,store_name,star,p0_order_item_id) VALUES(?,?,0,?,?,?,?,?,?,?,?,?,?,?,?,'OPEN',0,0,?,?,?)",
                evaluation,d.clock.instant(),content,item.get("goods_id"),line.get("image").getAsString(),line.get("name").getAsString(),!refs.isEmpty(),write(refs),order.get("member_id"),member.get("nick_name"),member.get("face"),order.get("sn"),order.get("store_id"),item.get("sku_id"),order.get("store_name"),star,itemId);
            files.bind(refs,order,"EVALUATION",evaluation);d.audit(order,"EVALUATION",evaluation,"evaluation.submit",null,null,map("star",star,"status","OPEN"));d.event(order,"EVALUATION",evaluation,"S45_EVALUATION","evaluation.read",true,map("evaluationId",evaluation));return view(row(evaluation),false);
        });
    }
    private Map<String,Object> row(String id){return d.db.required("SELECT e.*,o.id AS order_id FROM li_member_evaluation e LEFT JOIN li_order o ON o.sn=e.order_no WHERE e.id=? AND e.delete_flag=0",id);}
    public Object moderate(String id,Object body){
        var f=new Fields(body).only("operationId","expectedVersion","status","reason");String next=f.choice("status",Set.of("OPEN","CLOSE"),true),reason=f.text("reason",2,500,true);long version=f.version();
        var before=row(id);Stage1Exception.require(before.get("order_id")!=null,409,"LEGACY_EVALUATION_ORDER_UNEXPLAINED");var order=d.order(id(before,"order_id"),"evaluation.moderate");
        return d.command(order,"evaluation.moderate",id,body,()->{
            var current=row(id);Stage1Exception.require(number(current,"p0_version")==version,409,"VERSION_CONFLICT");
            Stage1Exception.require(!next.equals(id(current,"status")),409,"EVALUATION_STATUS_UNCHANGED");
            d.db.cas(d.db.update("UPDATE li_member_evaluation SET status=?,p0_version=p0_version+1,update_time=?,update_by=? WHERE id=? AND p0_version=?",next,d.clock.instant(),d.context.current().id(),id,version));
            d.audit(order,"EVALUATION",id,"evaluation.moderate",reason,map("status",current.get("status")),map("status",next));d.event(order,"EVALUATION",id,"S45_EVALUATION_STATUS","evaluation.read",true,map("evaluationId",id,"status",next));return view(row(id),false);
        });
    }
    @Transactional(readOnly=true)
    public Object detail(String id){
        var row=row(id);if(row.get("order_id")!=null)d.order(id(row,"order_id"),"evaluation.read");else authorizeLegacy(row);
        var value=view(row,false);value.put("history",d.db.rows("SELECT action,reason,actor_domain,created_at FROM li_p0_s45_audit WHERE subject_type='EVALUATION' AND subject_id=? ORDER BY id",id).stream().map(r->map("action",r.get("action"),"reason",r.get("reason"),"actor",r.get("actor_domain"),"at",instant(r.get("created_at")))).toList());return value;
    }
    private void authorizeLegacy(Map<String,Object> row){
        var actor=d.context.current();Stage1Exception.require(d.auth.permissions(actor.user()).contains("evaluation.read"),403,"FORBIDDEN");
        if(actor.domain()==SessionDomain.RESIDENT)Stage1Exception.require(actor.id().equals(id(row,"member_id")),404,"RESOURCE_NOT_FOUND");
        if(actor.domain()==SessionDomain.STORE)Stage1Exception.require(actor.store().equals(id(row,"store_id")),404,"RESOURCE_NOT_FOUND");
    }
    private Map<String,Object> view(Map<String,Object> row,boolean publicView){
        String nickname=id(row,"member_name");nickname=nickname==null||nickname.isBlank()?"邻里用户":nickname.substring(0,1)+"**";
        var out=map("id",id(row,"id"),"goodsId",id(row,"goods_id"),"skuId",id(row,"sku_id"),"goodsName",row.get("goods_name"),"image",row.get("goods_image"),"storeName",row.get("store_name"),"star",row.get("star"),"content",row.get("content"),"files",images(row.get("images")),"memberName",nickname,"createdAt",instant(row.get("create_time")));
        if(!publicView)out.putAll(map("orderId",id(row,"order_id"),"orderSn",row.get("order_no"),"status",row.get("status"),"version",row.get("p0_version"),"orderItemId",id(row,"p0_order_item_id"),"canModerate",d.auth.permissions(d.context.current().user()).contains("evaluation.moderate")&&row.get("order_id")!=null));
        return out;
    }
    private static Object images(Object raw){try{return raw==null?List.of():array(raw);}catch(RuntimeException legacy){return List.of();}}
    @Transactional(readOnly=true)
    public Object list(Map<String,String> q,String publicGoods){
        var actor=d.context.current();Stage1Exception.require(d.auth.permissions(actor.user()).contains(publicGoods==null?"evaluation.read":"evaluation.public"),403,"FORBIDDEN");
        var args=new ArrayList<Object>();String where=publicGoods==null?d.auth.scope(actor,"e",args):"e.goods_id=? AND e.status='OPEN'";if(publicGoods!=null)args.add(publicGoods);where+=" AND e.delete_flag=0";if(publicGoods==null&&q.containsKey("orderId")&&!q.get("orderId").isBlank()){Stage1Exception.require(q.get("orderId").matches("[1-9][0-9]{0,18}"),422,"QUERY_INVALID");where+=" AND o.id=?";args.add(q.get("orderId"));}
        String star=q.get("star"),status=q.get("status"),search=q.get("q");
        if(star!=null&&!star.isBlank()){Stage1Exception.require(star.matches("[1-5]"),422,"QUERY_INVALID");where+=" AND e.star=?";args.add(Integer.parseInt(star));}
        if(publicGoods==null&&status!=null&&!status.isBlank()){Stage1Exception.require(Set.of("OPEN","CLOSE").contains(status),422,"QUERY_INVALID");where+=" AND e.status=?";args.add(status);}
        if(search!=null&&!search.isBlank()){Stage1Exception.require(search.length()<=80,422,"QUERY_INVALID");where+=" AND (e.goods_name LIKE ? OR e.store_name LIKE ? OR e.order_no=?)";String like="%"+search.replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";args.add(like);args.add(like);args.add(search);}
        int page=Stage45Data.page(q,"pageNumber",1,1000000),size=Stage45Data.page(q,"pageSize",20,50);String from=" FROM li_member_evaluation e LEFT JOIN li_order o ON o.sn=e.order_no WHERE "+where;
        long total=d.db.count("SELECT COUNT(*)"+from,args.toArray());args.add(size);args.add((long)(page-1)*size);
        var result=d.db.rows("SELECT e.*,o.id AS order_id"+from+" ORDER BY e.id DESC LIMIT ? OFFSET ?",args.toArray()).stream().map(r->view(r,publicGoods!=null)).toList();d.context.recheck(actor);return Stage45Data.page(result,total,page,size);
    }
    @Transactional(readOnly=true)
    public Object summary(String goods){
        var actor=d.context.current();var args=new ArrayList<Object>();String where;
        if(goods!=null){where="e.goods_id=?";args.add(goods);}else{Stage1Exception.require(d.auth.permissions(actor.user()).contains("evaluation.read"),403,"FORBIDDEN");where=d.auth.scope(actor,"e",args);}
        var result=d.db.required("SELECT COUNT(*) AS total,COUNT(star) AS rated,AVG(star) AS average FROM li_member_evaluation e WHERE "+where+" AND e.status='OPEN' AND e.delete_flag=0",args.toArray());
        Object average=result.get("average")==null?null:new BigDecimal(result.get("average").toString()).setScale(1,RoundingMode.HALF_UP).toPlainString();
        return map("visibleCount",result.get("total"),"ratedCount",result.get("rated"),"averageStar",average,"label",average==null?"暂无评分":average+"分","basis","公开且未删除的真实单星；历史无星不参与均值");
    }
}
