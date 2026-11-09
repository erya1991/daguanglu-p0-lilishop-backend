package cn.lili.modules.community.p0.stage3;
import cn.lili.modules.community.p0.support.Stage1Exception;
import com.google.gson.JsonObject;
import java.time.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
/** Request-local D5 calculation; accepts only the caller's already authenticated full paid facts. */
public final class Stage3Turnover {
    private final String store;private final Instant start,end;private final Map<String,String> seen=new HashMap<>();private long amount;
    public Stage3Turnover(String store,LocalDate date){this.store=store;var zone=ZoneId.of("Asia/Shanghai");start=date.atStartOfDay(zone).toInstant();end=date.plusDays(1).atStartOfDay(zone).toInstant();}
    public void add(JsonObject fullPaid){
        for(var raw:fullPaid.getAsJsonArray("subOrders")){var sub=raw.getAsJsonObject();if(!store.equals(sub.get("storeId").getAsString()))continue;String id=sub.get("orderId").getAsString(),digest=hash(sub),prior=seen.putIfAbsent(id,digest);Stage1Exception.require(prior==null||prior.equals(digest),409,"PAID_FACTS_CONTRADICTORY");if(prior!=null)continue;
            Instant at=Instant.parse(sub.get("paidAt").getAsString());if(!at.isBefore(start)&&at.isBefore(end))amount=Math.addExact(amount,sub.get("amountCent").getAsLong());
        }
    }
    public long amount(){return amount;}
}
