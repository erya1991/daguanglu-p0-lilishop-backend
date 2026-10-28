package cn.lili.common.security.session;

import cn.lili.modules.community.p0.support.*;
import cn.lili.modules.community.p0.agreement.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfSystemProperty(named="s1.integration",matches="owned-run")
@Timeout(value=5,unit=java.util.concurrent.TimeUnit.MINUTES)
class Stage1CooperationBoundaryTest {
    static final Gson GSON=new Gson();
    @BeforeAll static void start()throws Exception{initialize();}
    @AfterAll static void end()throws Exception{finish();}
    @BeforeEach void refresh(){new Stage1PersistenceTest().freshManagerSessions();}
    @Test void bnd002IncompleteContractsCannotConfirmAndDatesAreShanghaiHalfOpen()throws Exception{
        var owner=ownerFixture();LocalDate today=LocalDate.now(Stage1Rules.ZONE);var agreement=createAgreement(owner,333,today,null);String id=agreement.get("id").getAsString();var base=agreement.getAsJsonObject("payload");long version=0;
        for(String missing:List.of("name","platformLegalName","merchantLegalName","merchantLegalCode","categoryIds","startDate","endDate","fileIds","confirmationReference","rateBps","baseCode","roundCode","commissionClause","refundSummary")){
            var incomplete=base.deepCopy();incomplete.remove(missing);call(put(Stage1Routes.MANAGER+"/agreements/"+id+"/draft"),operator,map("requestId",req(),"expectedVersion",version++,"payload",incomplete),200);
            call(post(Stage1Routes.MANAGER+"/agreements/"+id+"/confirm"),operator,map("requestId",req(),"expectedVersion",version),422);assertNull(db.required("SELECT confirmed_at FROM li_p0_agreement WHERE id=?",id).get("confirmed_at"));
        }
        var mismatch=base.deepCopy();mismatch.addProperty("merchantLegalName","S1_TEST其他主体");call(put(Stage1Routes.MANAGER+"/agreements/"+id+"/draft"),operator,map("requestId",req(),"expectedVersion",version,"payload",mismatch),422);
        var backwards=base.deepCopy();backwards.addProperty("endDate",today.minusDays(1).toString());call(put(Stage1Routes.MANAGER+"/agreements/"+id+"/draft"),operator,map("requestId",req(),"expectedVersion",version,"payload",backwards),422);
        var sameDay=base.deepCopy();sameDay.addProperty("endDate",today.toString());call(put(Stage1Routes.MANAGER+"/agreements/"+id+"/draft"),operator,map("requestId",req(),"expectedVersion",version++,"payload",sameDay),200);
        var confirmBody=map("requestId",req(),"expectedVersion",version);var confirmed=call(post(Stage1Routes.MANAGER+"/agreements/"+id+"/confirm"),operator,confirmBody,200);assertEquals(confirmed,call(post(Stage1Routes.MANAGER+"/agreements/"+id+"/confirm"),operator,confirmBody,200));
        Instant boundary=today.plusDays(1).atStartOfDay(Stage1Rules.ZONE).toInstant();assertEquals(boundary,Instant.parse(confirmed.get("actualTo").getAsString()));assertEquals(confirmed.get("confirmedAt"),confirmed.get("actualFrom"));assertEquals(today.toString(),confirmed.getAsJsonObject("payload").get("startDate").getAsString());
        String adjacent=agreementFixture(owner,333,today.plusDays(1),null);var next=db.required("SELECT * FROM li_p0_agreement WHERE id=?",adjacent);assertEquals(boundary,CooperationWindows.agreement(next).from());assertTrue((Boolean)eligibility.evaluate(owner.store(),category,boundary).get("BASIC_ELIGIBLE"));
        call(post(Stage1Routes.MANAGER+"/agreements/"+id+"/terminate"),operator,map("requestId",req(),"expectedVersion",version+1,"reason","S1_TEST禁止倒签","terminatedAt",today.minusDays(1).toString()),422);
        call(post(Stage1Routes.MANAGER+"/agreements/"+id+"/terminate"),operator,map("requestId",req(),"expectedVersion",version+1,"reason","S1_TEST当前终止"),200);assertFalse((Boolean)eligibility.evaluate(owner.store(),category,null).get("BASIC_ELIGIBLE"));
        observations.add(map("case","BND-002","missingConfirmFields",14,"sameDayInclusive",true,"adjacentAllowed",true,"noBackdating",true));
    }
    @Test void bnd004RuleRateMatrixAndPurePreviewNeverPersistMoneyFacts()throws Exception{
        var owner=ownerFixture();String agreement=agreementFixture(owner,333,LocalDate.now(Stage1Rules.ZONE),null);
        var base=object(map("name","S1_TEST比例规则","rateBps",333,"baseCode",AgreementService.BASE,"roundCode",AgreementService.ROUND,"startDate",LocalDate.now(Stage1Rules.ZONE).toString(),"endDate",LocalDate.now(Stage1Rules.ZONE).plusDays(90).toString(),"commissionClause","S1_TEST明确333基点条款","triggerBasis","ORDER_COMPLETED_NO_BLOCKING_AFTERSALE","executionIntent",true));
        for(Object rate:List.of(-1,10001,0.5)){var invalid=base.deepCopy();invalid.add("rateBps",GSON.toJsonTree(rate));call(post(Stage1Routes.MANAGER+"/commission-rules"),channel,map("requestId",req(),"storeId",owner.store(),"agreementId",agreement,"businessType","MALL","payload",invalid),422);}
        for(String name:List.of("","字".repeat(61))){var invalid=base.deepCopy();invalid.addProperty("name",name);call(post(Stage1Routes.MANAGER+"/commission-rules"),channel,map("requestId",req(),"storeId",owner.store(),"agreementId",agreement,"businessType","MALL","payload",invalid),422);}
        var missing=base.deepCopy();missing.remove("rateBps");var draft=call(post(Stage1Routes.MANAGER+"/commission-rules"),channel,map("requestId",req(),"storeId",owner.store(),"agreementId",agreement,"businessType","MALL","payload",missing),200);String incomplete=draft.get("id").getAsString();call(post(Stage1Routes.MANAGER+"/commission-rules/"+incomplete+"/publish"),channel,map("requestId",req(),"expectedVersion",0),422);assertNull(db.required("SELECT rate_bps FROM li_p0_commission_rule WHERE id=?",incomplete).get("rate_bps"));
        for(int rate:new int[]{0,1,9999,10000}){var valid=base.deepCopy();valid.addProperty("rateBps",rate);valid.addProperty("name",rate==0?"规":"字".repeat(60));var row=call(post(Stage1Routes.MANAGER+"/commission-rules"),channel,map("requestId",req(),"storeId",owner.store(),"agreementId",agreement,"businessType","MALL","payload",valid),200);assertEquals(rate,db.count("SELECT rate_bps FROM li_p0_commission_rule WHERE id=?",row.get("id").getAsString()));call(post(Stage1Routes.MANAGER+"/commission-rules/"+row.get("id").getAsString()+"/publish"),channel,map("requestId",req(),"expectedVersion",0),422);}
        String id=ruleFixture(owner,agreement,333);long ops=db.count("SELECT COUNT(*) FROM li_p0_operation"),audits=db.count("SELECT COUNT(*) FROM li_p0_audit"),events=db.count("SELECT COUNT(*) FROM li_p0_outbox");
        forbidSqlWrites.set(true);try{
            var preview=call(get(Stage1Routes.MANAGER+"/commission-rules/"+id+"/preview").param("productPaidCent","10001").param("shippingCent","500"),channel,null,200);assertEquals(333,preview.get("commissionCent").getAsLong());assertEquals(9668,preview.get("merchantProductCent").getAsLong());assertFalse(preview.get("executionAllowed").getAsBoolean());assertTrue(preview.get("illustrationOnly").getAsBoolean());
            call(get(Stage1Routes.MANAGER+"/commission-rules/"+id+"/preview").param("productPaidCent","-1").param("shippingCent","0"),channel,null,422);call(get(Stage1Routes.MANAGER+"/commission-rules/"+id+"/preview").param("productPaidCent","1.5").param("shippingCent","0"),channel,null,400);call(get(Stage1Routes.MANAGER+"/commission-rules/"+id+"/preview").param("productPaidCent","1").param("shippingCent","0"),reviewer,null,403);
        }finally{forbidSqlWrites.remove();}
        assertEquals(ops,db.count("SELECT COUNT(*) FROM li_p0_operation"));assertEquals(audits,db.count("SELECT COUNT(*) FROM li_p0_audit"));assertEquals(events,db.count("SELECT COUNT(*) FROM li_p0_outbox"));
        observations.add(map("case","BND-004","bpsMatrix",true,"missingNotZero",true,"previewReadOnly",true,"agreementClauseEnforced",true));
    }
    @Test void bnd015QualificationWarningsAndExpiryAreCurrentFactsNotCachedApprovals()throws Exception{
        var owner=ownerFixture();agreementFixture(owner,0,LocalDate.now(Stage1Rules.ZONE),null);var versions=call(get(Stage1Routes.STORE+"/qualifications"),owner.login().session(),null,200).getAsJsonArray("versions");var original=versions.get(0).getAsJsonObject();Instant end=Instant.parse(original.get("validTo").getAsString());LocalDate through=end.atZone(Stage1Rules.ZONE).toLocalDate().minusDays(1);
        try{
            for(int days:new int[]{31,30,8,7,1,0,-1}){Instant at=through.minusDays(days).atStartOfDay(Stage1Rules.ZONE).toInstant();ReflectionTestUtils.setField(admission,"clock",Clock.fixed(at,ZoneOffset.UTC));var current=call(get(Stage1Routes.STORE+"/qualifications"),owner.login().session(),null,200).getAsJsonArray("versions").get(0).getAsJsonObject();if(days==31)assertTrue(current.get("warning").isJsonNull());else assertEquals(days<0?"EXPIRED":days<=7?"DUE_7":"DUE_30",current.get("warning").getAsString());var warnings=object(eligibility.evaluate(owner.store(),null,at)).getAsJsonArray("qualificationWarnings");if(days==31)assertTrue(warnings.isEmpty());else {assertEquals(current.get("warning"),warnings.get(0).getAsJsonObject().get("warning"));assertEquals(original.get("id"),warnings.get(0).getAsJsonObject().get("id"));assertFalse(warnings.toString().contains("fileIds"));}}
        }finally{ReflectionTestUtils.setField(admission,"clock",clock);}
        assertTrue((Boolean)eligibility.evaluate(owner.store(),category,end.minusMillis(1)).get("BASIC_ELIGIBLE"));var expired=object(eligibility.evaluate(owner.store(),category,end));assertFalse(expired.get("BASIC_ELIGIBLE").getAsBoolean());assertTrue(expired.getAsJsonObject("reasons").getAsJsonArray("BASIC").toString().contains("LICENSE_EXPIRED"));
        var renewed=original.getAsJsonObject("details").deepCopy();renewed.addProperty("longTerm",true);renewed.remove("validThrough");var draft=call(post(Stage1Routes.STORE+"/qualifications"),owner.login().session(),map("requestId",req(),"payload",map("qualifications",List.of(renewed))),200);String id=draft.get("id").getAsString();call(post(Stage1Routes.STORE+"/qualifications/"+id+"/submit"),owner.login().session(),map("requestId",req(),"expectedVersion",0),200);assertFalse((Boolean)eligibility.evaluate(owner.store(),category,end).get("BASIC_ELIGIBLE"));
        call(post(Stage1Routes.MANAGER+"/applications/"+id+"/approve"),reviewer,map("requestId",req(),"expectedVersion",1,"revision",1),200);var latest=call(get(Stage1Routes.STORE+"/qualifications"),owner.login().session(),null,200).getAsJsonArray("versions").get(0).getAsJsonObject();assertTrue(latest.get("validTo").isJsonNull());assertTrue(latest.get("warning").isJsonNull());assertTrue(latest.get("longTerm").getAsBoolean());
        var reasons=object(eligibility.evaluate(owner.store(),category,end)).getAsJsonObject("reasons").getAsJsonArray("BASIC").toString();assertFalse(reasons.contains("LICENSE_EXPIRED"));
        observations.add(map("case","BND-015","warnings",List.of(31,30,8,7,1,0,-1),"expiryExclusiveMidnight",true,"pendingDoesNotExtend",true,"approvedLongTermNoExpiry",true));
    }
}
