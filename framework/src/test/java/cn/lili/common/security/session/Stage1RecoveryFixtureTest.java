package cn.lili.common.security.session;
import cn.lili.modules.community.p0.support.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
/** Explicitly selected preparation, not a standalone recovery PASS. The separate runner must restart and replay. */
@EnabledIfSystemProperty(named="s1.recovery.prepare",matches="owned-run")
class Stage1RecoveryFixtureTest {
    @BeforeAll static void start()throws Exception{initialize();assertEquals("STOPPED",object(Files.readString(run.resolve("runtime/apis.json"))).getAsJsonObject("manager").get("state").getAsString());}
    @AfterAll static void end()throws Exception{recoveryCapture=null;finish();}
    @Test void prepareRealCommittedFactsWithoutDeliveringResponsesToTheRecoveryClient()throws Exception{
        recoveryCapture=new ArrayList<>();var owner=ownerFixture();String agreement=agreementFixture(owner,0,LocalDate.now(Stage1Rules.ZONE),null);String rule=ruleFixture(owner,agreement,0);deliveryFixture(owner);
        String channelConfig=channelConfigFixture(1000),channelRelation=Stage1ChannelCoverageTest.readyRelation(owner,channelConfig);String channelQuery="SELECT JSON_OBJECT('relationId',CAST(r.id AS CHAR),'version',r.version,'mode',r.mode,'enabled',r.enabled,'lastSync',r.last_sync_state,'source',r.capability_evidence,'applicationState',a.state,'outRequestNo',a.out_request_no,'applymentId',CAST(a.applyment_id AS CHAR)) FROM li_p0_submerchant_relation r JOIN li_p0_channel_application a ON a.id=r.application_id WHERE r.id='"+channelRelation+"';";String channelBefore=String.valueOf(db.required(channelQuery).values().iterator().next());
        var applicant=login("restart_staff_"+req());String role=id(db.required("SELECT id FROM li_store_role WHERE store_id=? AND name='READ_ONLY'",owner.store()),"id");var invitation=call(post(Stage1Routes.STORE+"/staff-invitations"),owner.login().session(),map("requestId",req(),"roleIds",List.of(role),"reason","S1_TEST重启复核"),200);String invitationId=invitation.get("id").getAsString();call(post(Stage1Routes.APPLICANT+"/invitations/accept"),applicant.session(),map("requestId",req(),"expectedVersion",0,"token",invitation.get("invitationToken").getAsString()),200);call(post(Stage1Routes.STORE+"/staff-invitations/"+invitationId+"/confirm"),owner.login().session(),map("requestId",req(),"expectedVersion",1,"reason","S1_TEST重启确认"),200);
        var goods=call(post(Stage1Routes.STORE+"/goods"),owner.login().session(),map("requestId",req(),"payload",goodsPayload(unitFixture(),upload(owner.login().session(),Stage1Routes.STORE,"GOODS"),10)),200);String goodsId=goods.getAsJsonObject("goods").get("id").getAsString(),sku=goods.getAsJsonArray("skus").get(0).getAsJsonObject().get("id").getAsString();
        call(put(Stage1Routes.STORE+"/skus/"+sku+"/stock"),owner.login().session(),map("requestId",req(),"expectedVersion",1,"targetQuantity",11,"reason","S1_TEST响应丢失库存"),200);
        call(post(Stage1Routes.STORE+"/goods/"+goodsId+"/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",0),200);call(post(Stage1Routes.MANAGER+"/goods/"+goodsId+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"contentVersion",0),200);call(post(Stage1Routes.STORE+"/goods/"+goodsId+"/list"),owner.login().session(),map("requestId",req(),"expectedVersion",2),200);call(post(Stage1Routes.MANAGER+"/goods/"+goodsId+"/force-off"),operator,map("requestId",req(),"expectedVersion",3,"reason","S1_TEST失效投递中断"),200);
        var targets=Map.of("admission",Stage1Routes.MANAGER+"/applications/","roleConfirmation",Stage1Routes.STORE+"/staff-invitations/"+invitationId+"/confirm","goodsSave",Stage1Routes.STORE+"/goods","inventory",Stage1Routes.STORE+"/skus/"+sku+"/stock","rulePublish",Stage1Routes.MANAGER+"/commission-rules/"+rule+"/publish");var receipts=new ArrayList<Map<String,Object>>();
        for(var entry:targets.entrySet()){var selected=recoveryCapture.stream().filter(r->entry.getKey().equals("admission")?id(r,"route").startsWith(entry.getValue())&&id(r,"route").endsWith("/approve"):entry.getValue().equals(id(r,"route"))).findFirst().orElseThrow();selected.put("name",entry.getKey());receipts.add(selected);}assertEquals(5,receipts.size());
        String eventType="S1_TEST_RECOVERY",key=eventType+":"+owner.store()+":"+goodsId;
        // Synthetic out-of-order envelope tests the real Redis delivery algorithm; business safety
        // assertions above use the actual committed regulation event and native goods facts.
        for(int version:new int[]{9,8})db.update("INSERT INTO li_p0_outbox(id,store_id,object_id,event_type,version,status,next_attempt_at,created_at) VALUES(?,?,?, ?,?,'PENDING',?,?)",db.id(),owner.store(),goodsId,eventType,version,clock.instant(),clock.instant());
        var outbox=db.rows("SELECT id FROM li_p0_outbox WHERE store_id=? AND status='PENDING' ORDER BY next_attempt_at,id",owner.store()).stream().map(r->id(r,"id")).toList();assertFalse(outbox.isEmpty());
        String factQuery="SELECT (SELECT COUNT(*) FROM li_store WHERE id='"+owner.store()+"'),(SELECT COUNT(*) FROM li_clerk WHERE store_id='"+owner.store()+"'),(SELECT COUNT(*) FROM li_p0_grant_evidence WHERE store_id='"+owner.store()+"'),(SELECT COUNT(*) FROM li_goods WHERE store_id='"+owner.store()+"'),(SELECT COUNT(*) FROM li_goods_sku WHERE store_id='"+owner.store()+"'),(SELECT COUNT(*) FROM li_p0_inventory_adjustment WHERE store_id='"+owner.store()+"'),(SELECT COUNT(*) FROM li_p0_commission_rule WHERE store_id='"+owner.store()+"');";
        var facts=db.required(factQuery);String before=facts.values().stream().map(String::valueOf).collect(java.util.stream.Collectors.joining("\t"));
        Files.writeString(run.resolve("private/recovery-fixtures.json"),write(map("runId",run.getFileName().toString(),"testId",testId,"createdAt",clock.instant(),"storeId",owner.store(),"goodsId",goodsId,"ownerAccess",owner.login().session().accessToken(),"receipts",receipts,"outboxIds",outbox,"monotonicKey",key,"factQuery",factQuery,"factsBefore",before,"channelQuery",channelQuery,"channelBefore",channelBefore,"channelRelation",channelRelation,"channelAccess",channel.accessToken())));
        observations.add(map("case","recovery-fixture-prepared","receipts",5,"outboxRows",outbox.size(),"completion","PREPARED_ONLY","realWeChat",false));
    }
}
