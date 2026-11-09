package cn.lili.modules.community.p0.stage45;

import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Component @ConditionalOnProperty(name={"p0.stage45.enabled","p0.stage45.workers-enabled"},havingValue="true")
public class Stage45Worker {
    private volatile long cursor;
    private final Stage45StoreFlow flows;
    private final Stage45Data d;private final Stage45Funds funds;private final Stage45Inventory inventory;
    public Stage45Worker(Stage45Data d,Stage45Funds funds,Stage45Inventory inventory,Stage45StoreFlow flows){this.flows=flows;this.d=d;this.funds=funds;this.inventory=inventory;}
    @Scheduled(fixedDelayString="${p0.stage45.worker-delay-ms:1500}")
    public void tick(){
        try{d.gate.channel();}catch(Stage1Exception disabled){return;}
        var candidates=d.db.rows("SELECT s.order_id FROM li_p0_order_snapshot s JOIN li_p0_trade_state t ON t.trade_id=s.trade_id WHERE s.order_id>? AND (t.payment_status='SUCCEEDED' OR EXISTS(SELECT 1 FROM li_p0_s45_obligation x WHERE x.order_id=s.order_id)) ORDER BY s.order_id LIMIT 50",cursor);if(candidates.isEmpty())cursor=0;
        for(var row:candidates){cursor=number(row,"order_id");
            try{funds.plan(d.orders.row(id(row,"order_id")));}catch(RuntimeException failure){String code=failure instanceof Stage1Exception e?e.code():"FUNDS_PLAN_RETRY";d.issue(id(row,"order_id"),"ORDER:"+id(row,"order_id"),code,map("orderId",id(row,"order_id")));}
        }
        for(int i=0;i<20;i++){try{if(!funds.executeOne())break;}catch(RuntimeException retry){break;}}
        for(int i=0;i<50;i++){try{if(!flows.one())break;}catch(RuntimeException retry){break;}}
        for(int i=0;i<20;i++){try{if(!inventory.one())break;}catch(RuntimeException retry){break;}}
        for(int i=0;i<20;i++){try{if(!inventory.projectOne())break;}catch(RuntimeException retry){break;}}
    }
}
