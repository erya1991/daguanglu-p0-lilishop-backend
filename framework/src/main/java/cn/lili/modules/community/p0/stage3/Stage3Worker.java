package cn.lili.modules.community.p0.stage3;

import cn.lili.modules.community.p0.stage2.Stage2Settings;
import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Component @ConditionalOnProperty(name={"p0.stage3.enabled","p0.stage3.workers-enabled"},havingValue="true")
public class Stage3Worker {
    private final Stage1Db db;private final Stage3Orders orders;private final Stage3Eligibility eligibility;private final Stage3Verification verification;private final Stage3Messages messages;private final Stage3Gate gate;private final Stage2Settings resources;
    public Stage3Worker(Stage1Db db,Stage3Orders orders,Stage3Eligibility eligibility,Stage3Verification verification,Stage3Messages messages,Stage3Gate gate,Stage2Settings resources){this.db=db;this.orders=orders;this.eligibility=eligibility;this.verification=verification;this.messages=messages;this.gate=gate;this.resources=resources;}
    @Scheduled(fixedDelayString="${p0.stage3.worker-delay-ms:1500}")
    public void tick(){
        if(gate.writeBlock()!=null)return;
        for(var candidate:db.rows("SELECT s.order_id FROM li_p0_order_snapshot s JOIN li_p0_s2_member_scope m ON m.member_id=s.member_id LEFT JOIN li_p0_s3_fulfillment f ON f.order_id=s.order_id LEFT JOIN li_p0_s3_credential c ON c.order_id=s.order_id WHERE m.run_id=? AND m.permission='TRANSACT' AND (f.order_id IS NULL OR (c.order_id IS NULL AND f.phase IN ('WAIT_PICKUP','WAIT_VERIFY'))) ORDER BY s.order_id LIMIT 50",resources.getRunId())){
            try{var row=orders.row(id(candidate,"order_id"));if(!eligibility.inspect(id(row,"trade_id")).eligible()||!orders.pointAvailable(row))continue;
                orders.locked(row,()->{gate.requireWrites();Stage1Exception.require(orders.pointAvailable(row),409,"POINT_UNAVAILABLE");var state=orders.admitLocked(row);verification.issueLocked(row,state);return null;});
            }catch(Stage1Exception pending){/* Retry the unchanged authoritative order after its own gate is ready. */}
        }
        for(int i=0;i<50&&messages.deliverOne();i++){/* bounded owned-run outbox */}
    }
}
