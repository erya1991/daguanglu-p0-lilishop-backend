package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.catalog.ConsumerGoodsService;
import cn.lili.modules.community.p0.support.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.time.Clock;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Persistent extension of the existing Cart domain. Redis TradeDTO is never a writer here. */
@Service @ConditionalOnProperty(name="p0.stage2.enabled",havingValue="true")
public class Stage2Cart {
    private final Stage1Db db;private final Stage2Transactions tx;private final ConsumerGoodsService goods;private final Clock clock;
    public Stage2Cart(Stage1Db db,Stage2Transactions tx,ConsumerGoodsService goods,@Qualifier("stage1Clock")Clock clock){this.db=db;this.tx=tx;this.goods=goods;this.clock=clock;}
    public Object read(){String member=tx.member();return view(member);}
    public Object view(String member){
        var items=new ArrayList<Object>();for(var row:db.rows("SELECT * FROM li_p0_cart_item WHERE member_id=? AND deleted=0 AND quantity>0 ORDER BY store_id,id",member)){
            var reasons=new ArrayList<String>();Map<String,Object> sku=null;Object detail=null;
            try{sku=sku(id(row,"sku_id"));detail=Stage2Catalog.enrich(db,object(goods.detail(id(sku,"goods_id"))));var d=object(detail);d.getAsJsonArray("reasonCodes").forEach(r->reasons.add(r.getAsString()));if(!flag(sku,"enabled"))reasons.add("SKU_DISABLED");if(number(row,"quantity")>number(sku,"quantity"))reasons.add("OUT_OF_STOCK");}catch(Stage1Exception e){if(e.status()>=500)throw e;reasons.add("SKU_UNAVAILABLE");}
            items.add(map("id",id(row,"id"),"skuId",id(row,"sku_id"),"storeId",id(row,"store_id"),"quantity",row.get("quantity"),"version",row.get("version"),"product",detail,"unitPriceCent",sku==null?null:cents(sku.get("price")),"available",reasons.isEmpty(),"reasonCodes",reasons.stream().distinct().toList()));
        }return map("items",items,"mode","MOCK");
    }
    public Map<String,Object> sku(String sku){return db.required("SELECT s.*,k.inventory_version,k.enabled,k.source_run,k.historical_reference FROM li_goods_sku s JOIN li_p0_sku_control k ON k.sku_id=s.id AND k.goods_id=s.goods_id AND k.store_id=s.store_id WHERE s.id=? AND s.delete_flag=0",sku);}
    private void purchasable(Map<String,Object> sku,long quantity){
        var detail=object(goods.detail(id(sku,"goods_id")));Stage1Exception.require(flag(sku,"enabled")&&detail.get("available").getAsBoolean(),409,"GOODS_UNAVAILABLE");
        Stage1Exception.require(quantity<=number(sku,"quantity"),409,"OUT_OF_STOCK");long price=cents(sku.get("price"));Stage1Exception.require(price>=1&&price<=99999999,409,"PRICE_INVALID");
    }
    public Object add(Object body){
        var f=new Fields(body).only("operationId","skuId","quantity");String skuId=f.id("skuId",true);long quantity=f.integer("quantity",1,99,true);var candidate=sku(skuId);String member=tx.member(),store=id(candidate,"store_id");
        return tx.command("cart.add",body,List.of(store),()->{
            // Every Cart writer holds the Member mutex, including consumption and cleanup.
            // An absent child FOR UPDATE would couple independent Members through a gap lock.
            var current=sku(skuId);var old=db.one("SELECT * FROM li_p0_cart_item WHERE member_id=? AND sku_id=?",member,skuId);
            long amount=quantity+(old==null||flag(old,"deleted")?0:number(old,"quantity"));Stage1Exception.require(amount<=99,422,"SKU_QUANTITY_LIMIT");purchasable(current,amount);
            if(old==null||flag(old,"deleted")||number(old,"quantity")==0)Stage1Exception.require(db.count("SELECT COUNT(*) FROM li_p0_cart_item WHERE member_id=? AND deleted=0 AND quantity>0",member)<200,409,"CART_CAPACITY");
            if(old==null)db.update("INSERT INTO li_p0_cart_item VALUES(?,?,?,?,?,0,0,?,?)",db.id(),member,store,skuId,amount,clock.instant(),clock.instant());
            else db.update("UPDATE li_p0_cart_item SET quantity=?,version=version+1,deleted=0,updated_at=? WHERE id=?",amount,clock.instant(),old.get("id"));
            return view(member);
        });
    }
    public Object update(String id,Object body){
        var f=new Fields(body).only("operationId","expectedVersion","quantity");long amount=f.integer("quantity",1,99,true);String member=tx.member();var row=db.required("SELECT store_id FROM li_p0_cart_item WHERE id=? AND member_id=?",id,member);
        return tx.command("cart.update:"+id,body,List.of(id(row,"store_id")),()->{var old=line(id,member,f.version());purchasable(sku(id(old,"sku_id")),amount);db.update("UPDATE li_p0_cart_item SET quantity=?,version=version+1,updated_at=? WHERE id=?",amount,clock.instant(),id);return view(member);});
    }
    public Object remove(Object body){
        var f=new Fields(body).only("operationId","items");var items=f.list("items",1,200,true);String member=tx.member();
        return tx.command("cart.remove",body,List.of(),()->{var seen=new HashSet<String>();for(var item:items){var line=new Fields(item).only("id","expectedVersion");String id=line.id("id",true);Stage1Exception.require(seen.add(id),422,"DUPLICATE_CART_ITEM");line(id,member,line.version());db.update("UPDATE li_p0_cart_item SET quantity=0,deleted=1,version=version+1,updated_at=? WHERE id=?",clock.instant(),id);}return view(member);});
    }
    public Map<String,Object> line(String id,String member,long version){var row=db.required("SELECT * FROM li_p0_cart_item WHERE id=? AND member_id=? AND deleted=0 AND quantity>0 FOR UPDATE",id,member);Stage1Exception.require(number(row,"version")==version,409,"VERSION_CONFLICT");return row;}
}
