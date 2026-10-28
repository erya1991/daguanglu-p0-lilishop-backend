package cn.lili.modules.community.p0.eligibility;

import cn.lili.modules.community.p0.support.*;
import cn.lili.common.security.session.http.SessionApplications;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** CONTRACT_TEST only: in-memory trusted evidence, no stored REAL qualification and no channel network. */
class ChannelEligibilityContractTest {
    final Instant now=Instant.parse("2026-10-27T00:00:00Z");
    final Stage1Db db=mock(Stage1Db.class);
    final Stage1Settings settings=new Stage1Settings();final SessionApplications apps=new SessionApplications();
    Map<String,Object> relation,evidence,receiver;EligibilityService service;
    @BeforeEach void setup(){
        settings.setEnvironment("TEST");settings.setMode("REAL");apps.setResidentApplication("wx1111111111111111");apps.setMerchantApplication("wx2222222222222222");
        evidence=map("sourceMode","REAL","paymentEnabled",true,"appIdLinked",true,"paymentAppId",apps.getResidentApplication(),"maxRateBps",500,"profitsharingEnabled",true);
        relation=map("id","11","version",2,"config_id","21","mode","REAL","config_mode","REAL","environment","TEST","config_version",3,"current_config_version",3,"config_enabled",true,"enabled",true,"application_state","COMPLETED","last_sync_state","SUCCESS","capability_state","VERIFIED","verified_at",now.minusSeconds(60),"sub_mchid","1900000001","payment_appid",apps.getResidentApplication(),"receiver_id","31","evidence_version",1);
        receiver=map("id","31","version",1,"enabled",true,"state","VERIFIED","source_kind","REAL","trust_source","API_REGISTERED_VERIFIED","config_version",3,"verified_at",now.minusSeconds(60));
        when(db.rows(anyString(),eq("1"),eq("REAL"))).thenAnswer(i->{relation.put("capability_evidence",write(evidence));return List.of(relation);});
        when(db.one(anyString(),eq("31"),eq("21"),eq("REAL"))).thenAnswer(i->receiver);
        service=new EligibilityService(db,null,null,settings,mock(PlatformTransactionManager.class),Clock.fixed(now,ZoneOffset.UTC),apps);
    }
    List<Map<String,Object>> reasons(long bps,Instant at){var reasons=new ArrayList<Map<String,Object>>();ReflectionTestUtils.invokeMethod(service,"channel","1","REAL",map("rate_bps",bps),at,reasons,new HashMap<String,Object>(),new ArrayList<Instant>());return reasons;}
    @Test void completeSyntheticEvidencePassesOnlyContractAndEveryMissingFactFails(){
        assertTrue(reasons(500,now).isEmpty());
        Map<String,Object> original=new LinkedHashMap<>(relation),proof=new LinkedHashMap<>(evidence),receive=new LinkedHashMap<>(receiver);
        List<Consumer<Map<String,Object>>> failures=List.of(r->r.put("application_state","PROCESSING"),r->r.put("application_state","NEED_USER_ACTION"),r->r.put("application_state","UNKNOWN"),r->r.put("config_mode","MOCK"),r->r.put("environment","DEV"),r->r.put("enabled",false),r->r.put("config_enabled",false),r->r.put("current_config_version",4),r->r.put("last_sync_state","UNKNOWN"),r->r.put("capability_state","UNVERIFIED"),r->r.put("sub_mchid",null),r->r.put("verified_at",now.minusSeconds(86400)),r->r.put("verified_at",now.plusSeconds(1)),r->r.put("payment_appid",apps.getMerchantApplication()));
        for(var fail:failures){relation=new LinkedHashMap<>(original);fail.accept(relation);assertFalse(reasons(500,now).isEmpty(),"Missing relation fact must fail closed");}relation=original;
        for(String field:List.of("sourceMode","paymentEnabled","appIdLinked","paymentAppId","maxRateBps","profitsharingEnabled")){evidence=new LinkedHashMap<>(proof);evidence.remove(field);assertFalse(reasons(500,now).isEmpty(),"Missing capability field "+field);}evidence=proof;
        for(String field:List.of("enabled","state","source_kind","trust_source","config_version","verified_at")){receiver=new LinkedHashMap<>(receive);receiver.remove(field);assertFalse(reasons(500,now).isEmpty(),"Missing receiver field "+field);}receiver=receive;
        for(String source:List.of("UNVERIFIED","MOCK_CONTRACT","MANUAL")){receiver.put("trust_source",source);assertFalse(reasons(500,now).isEmpty(),"Untrusted receiver "+source);}receiver.put("trust_source","DEFAULT_PLATFORM_VERIFIED");assertTrue(reasons(500,now).isEmpty());receiver.put("trust_source","API_REGISTERED_VERIFIED");
        evidence.put("maxRateBps",499);assertFalse(reasons(500,now).isEmpty());evidence.put("maxRateBps",500);assertTrue(reasons(500,now).isEmpty());
        assertTrue(reasons(500,now.plusSeconds(86340).minusMillis(1)).isEmpty());assertFalse(reasons(500,now.plusSeconds(86340)).isEmpty());
    }
    @Test void zeroRateStillNeedsPaymentButNeverInventsReceiverOrCapRequirements(){
        relation.put("receiver_id",null);evidence.remove("maxRateBps");evidence.remove("profitsharingEnabled");assertTrue(reasons(0,now).isEmpty());evidence.put("paymentEnabled",false);assertFalse(reasons(0,now).isEmpty());
    }
}
