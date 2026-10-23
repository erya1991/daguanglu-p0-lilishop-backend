package cn.lili.modules.connect.wechat;

import cn.lili.common.properties.JWTTokenProperties;
import cn.lili.common.security.session.*;
import cn.lili.common.security.session.http.*;
import cn.lili.common.security.token.SecretKeyUtil;
import cn.lili.common.utils.SnowFlake;
import cn.lili.modules.connect.entity.WechatIdentity;
import cn.lili.modules.member.profile.*;
import cn.lili.modules.member.service.*;
import cn.lili.modules.member.token.StoreTokenGenerate;
import cn.lili.modules.permission.service.*;
import cn.lili.modules.store.service.StoreService;
import cn.lili.modules.system.token.ManagerTokenGenerate;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static cn.lili.modules.member.profile.ResidentProfileException.Code.*;

/** Actual Session codec/core/current authorization + mapping verifier + link service.
 * Only TEST fixtures provide successful independent proof. This does not approve a real proof source. */
@SuppressWarnings("unchecked")
class MemberLinkServiceTest {
    static Object oldSnowflake;
    @BeforeAll static void ids(){oldSnowflake=org.springframework.test.util.ReflectionTestUtils.getField(SnowFlake.class,"snowflake");SnowFlake.initialize(31,29);}
    @AfterAll static void restore(){org.springframework.test.util.ReflectionTestUtils.setField(SnowFlake.class,"snowflake",oldSnowflake);}
    final OfflineResidentStore db=new OfflineResidentStore();
    final ResidentProfileProperties props=new ResidentProfileProperties();
    final ResidentWechatProperties wechat=new ResidentWechatProperties();
    final SessionApplications apps=new SessionApplications();
    final WechatIdentityService identities;
    final WechatSessionIdentityVerifier verifier;
    final ObjectProvider<MemberOwnershipProof> proofProviders=mock(ObjectProvider.class);
    final MemberOwnershipProof proof=mock(MemberOwnershipProof.class);
    final RedisSessionStore redis=mock(RedisSessionStore.class);
    final Map<String,SessionRecord> records=new ConcurrentHashMap<>();
    final SecretKeyUtil keys;
    final SessionService sessions;
    final MemberLinkService links;
    final SessionIdentity a=new SessionIdentity("123",SessionDomain.RESIDENT,OfflineResidentStore.APP,"789",1L);
    final SessionTokens old;
    final SessionService.VerifiedSession verified;
    MemberLinkServiceTest(){
        props.setSchemaReady(true);props.setHistoryReady(true);wechat.setSchemaReady(true);
        apps.setResidentApplication(OfflineResidentStore.APP);apps.setMerchantApplication("wx0000000000000002");apps.setManagerApplication("synthetic-manager");
        identities=new WechatIdentityService(db.mappings,db.members,wechat,db);
        verifier=new WechatSessionIdentityVerifier(identities,apps,db.links,props);
        ObjectProvider<SessionIdentityVerifier> verifiers=mock(ObjectProvider.class);when(verifiers.getIfUnique()).thenReturn(verifier);
        MemberService memberService=mock(MemberService.class);when(memberService.getById(anyString())).thenAnswer(i->db.members.selectById((String)i.getArgument(0)));
        var auth=new CurrentSessionAuthorization(apps,verifiers,memberService,mock(ClerkService.class),mock(StoreService.class),
                mock(AdminUserService.class),mock(MenuService.class),mock(StoreMenuService.class),new ManagerTokenGenerate(),new StoreTokenGenerate());
        ObjectProvider<SessionAuthorization> providers=mock(ObjectProvider.class);when(providers.getIfUnique()).thenReturn(auth);
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);var jwt=new JWTTokenProperties();jwt.setSecretKey(Base64.getEncoder().encodeToString(bytes));keys=new SecretKeyUtil(jwt);
        when(redis.create(any())).thenAnswer(i->{SessionRecord r=i.getArgument(0);return records.putIfAbsent(r.sid(),r)==null;});
        when(redis.load(any(),anyString(),anyString())).thenAnswer(i->records.get(i.getArgument(2)));
        when(redis.rotate(any(),any())).thenAnswer(i->{SessionRecord r=i.getArgument(0);return records.replace(r.sid(),r,i.getArgument(1));});
        when(redis.revoke(any())).thenAnswer(i->{SessionRecord r=i.getArgument(0);records.remove(r.sid());return true;});
        sessions=new SessionService(redis,keys,providers);
        links=new MemberLinkService(db.links,db.mappings,identities,db.profiles,props,new ResidentTransactions(db),proofProviders,sessions);
        old=sessions.create(a);verified=sessions.verifyAccess(old.accessToken(),SessionDomain.RESIDENT,OfflineResidentStore.APP);
        when(proofProviders.getIfUnique()).thenReturn(proof);
        when(proof.verify(eq(a),anyString(),anyString())).thenReturn(new MemberOwnershipProof.Verified("456","synthetic-audited-reference",MemberOwnershipProof.Kind.APPROVED_EVIDENCE,System.currentTimeMillis()+60_000));
    }
    @AfterEach void close(){keys.destroy();}
    MemberLinkService.Result link(){return links.link(verified,"link-request-0001","synthetic-proof-ref",MemberLinkService.CONSENT_VERSION,true);}
    @Test void qualifiedProofAndConsentKeepBHistoryAndRejectAllOldBindingSessions(){
        db.histories.put("456",5L);var secondA=sessions.create(a);
        var other=new WechatIdentity();other.setId("790");other.setAppId(OfflineResidentStore.APP);other.setOpenId("other-wechat");other.setMemberId("456");other.setBindingVersion(1L);other.setBindingState("ACTIVE");db.identities.put("790",other);
        var existingB=sessions.create(new SessionIdentity("456",SessionDomain.RESIDENT,OfflineResidentStore.APP,"790",1L));
        var result=link();assertEquals("DONE",result.state());assertNotNull(result.session());assertEquals(1,db.switches.get());
        assertEquals("456",sessions.verifyAccess(result.session().accessToken(),SessionDomain.RESIDENT,OfflineResidentStore.APP).identity().subject());
        assertThrows(SessionException.class,()->sessions.verifyAccess(old.accessToken(),SessionDomain.RESIDENT,OfflineResidentStore.APP));
        assertThrows(SessionException.class,()->sessions.refresh(secondA.refreshToken(),SessionDomain.RESIDENT,OfflineResidentStore.APP));
        assertEquals("456",sessions.verifyAccess(existingB.accessToken(),SessionDomain.RESIDENT,OfflineResidentStore.APP).identity().subject());
        assertEquals(5,db.histories.get("456"));assertEquals(2,db.people.size());assertEquals(2,db.identities.get("789").getBindingVersion());
    }
    @Test void missingProofSourceAndPhoneOnlyEvidenceNeverSwitchMapping(){
        when(proofProviders.getIfUnique()).thenReturn(null);assertEquals(PROOF_SOURCE_PENDING,assertThrows(ResidentProfileException.class,this::link).getCode());
        assertTrue(db.attempts.isEmpty());assertEquals("123",db.identities.get("789").getMemberId());
    }
    @ParameterizedTest @ValueSource(strings={"default-password","weak-password","mobile-reset-password","unknown-provenance","expired-proof"})
    void unqualifiedProofIsRejectedByRequiredProvider(String fault){
        doThrow(new IllegalArgumentException(fault)).when(proof).verify(any(),anyString(),anyString());
        assertEquals(PROOF_REJECTED,assertThrows(ResidentProfileException.class,this::link).getCode());assertEquals(0,db.switches.get());
    }
    @Test void missingConsentRejectsBeforeProof(){
        assertEquals(CONSENT_REQUIRED,assertThrows(ResidentProfileException.class,()->links.link(verified,"link-request-0001","synthetic-proof-ref","wrong-version",false)).getCode());verifyNoInteractions(proof);
    }
    @Test void bothHistoriesAndAOnlyHistoryCannotMerge(){
        db.histories.put("123",1L);db.histories.put("456",1L);assertEquals(HISTORY_CONFLICT,assertThrows(ResidentProfileException.class,this::link).getCode());assertTrue(db.attempts.isEmpty());
        db.histories.put("456",0L);assertThrows(ResidentProfileException.class,this::link);assertEquals("123",sessions.verifyAccess(old.accessToken(),SessionDomain.RESIDENT,OfflineResidentStore.APP).identity().subject());
    }
    @ParameterizedTest @ValueSource(strings={"history","switch","commit"})
    void missingHistoryTableOrFirstTransactionFailurePreservesOriginalIdentity(String failure){db.fail=failure;assertThrows(ResidentProfileException.class,this::link);assertTrue(db.attempts.isEmpty());assertEquals("ACTIVE",db.identities.get("789").getBindingState());assertEquals("123",db.identities.get("789").getMemberId());}
    @ParameterizedTest @ValueSource(strings={"revoke","issue","done"})
    void pendingHandoffFailsClosedAndFreshWechatCanRecover(String failure){
        if(failure.equals("revoke"))doThrow(new SessionException(SessionException.Code.STORE_UNAVAILABLE)).when(redis).revoke(any());
        if(failure.equals("issue"))doThrow(new SessionException(SessionException.Code.STORE_UNAVAILABLE)).when(redis).create(any());
        if(failure.equals("done"))db.fail="done";
        assertEquals(LINK_PENDING,assertThrows(ResidentProfileException.class,this::link).getCode());
        assertEquals("PENDING",db.identities.get("789").getBindingState());assertEquals("PENDING",db.attempts.values().iterator().next().getState());
        assertThrows(SessionException.class,()->sessions.verifyAccess(old.accessToken(),SessionDomain.RESIDENT,OfflineResidentStore.APP));
        assertThrows(SessionException.class,()->sessions.create(new SessionIdentity("456",SessionDomain.RESIDENT,OfflineResidentStore.APP,"789",2L)));
        db.fail=null;doAnswer(i->{SessionRecord r=i.getArgument(0);records.remove(r.sid());return true;}).when(redis).revoke(any());
        doAnswer(i->{SessionRecord r=i.getArgument(0);return records.putIfAbsent(r.sid(),r)==null;}).when(redis).create(any());
        var recovered=links.recover(new WechatCodeGateway.Identity(OfflineResidentStore.APP,"synthetic-open",null));
        assertEquals("456",recovered.memberId());assertEquals("ACTIVE",db.identities.get("789").getBindingState());
        sessions.verifyAccess(recovered.session().accessToken(),SessionDomain.RESIDENT,OfflineResidentStore.APP);
    }
    @Test void duplicateAndLostResponseReturnReloginWithoutReplayingBearer(){var first=link();var repeat=link();assertNotNull(first.session());assertNull(repeat.session());assertEquals("WECHAT_RELOGIN",repeat.nextAction());assertEquals(1,db.attempts.size());assertEquals(1,db.switches.get());}
    @Test void concurrentConfirmationsSwitchOnce()throws Exception{
        var executor=Executors.newFixedThreadPool(2);try{var futures=List.of(executor.submit(()->{try{return link().state();}catch(ResidentProfileException e){return e.getCode().name();}}),executor.submit(()->{try{return link().state();}catch(ResidentProfileException e){return e.getCode().name();}}));
            for(var f:futures)assertNotNull(f.get());assertEquals(1,db.switches.get());assertEquals(1,db.attempts.size());assertEquals("456",db.identities.get("789").getMemberId());
        }finally{executor.shutdownNow();}
    }
    @Test void forgedHandoffCannotBypassProofMappingOrSourceSid(){
        assertThrows(SessionException.class,()->sessions.handoff(new SessionHandoff(a,new SessionIdentity("456",SessionDomain.RESIDENT,OfflineResidentStore.APP,"789",2L),verified.sid(),"link-request-0001")));
        assertEquals(1,records.size());verify(redis,never()).revoke(any());
    }
    @Test void recoveryRequiresFreshOfficialCodeAndMatchingServerApplication() {
        db.fail="done";assertThrows(ResidentProfileException.class,this::link);db.fail=null;
        var gateway=mock(WechatCodeGateway.class);
        ObjectProvider<WechatLinkRecovery> recoveries=mock(ObjectProvider.class);when(recoveries.getIfUnique()).thenReturn(links);
        wechat.setEnabled(true);wechat.setAppSecret(UUID.randomUUID().toString().replace("-",""));
        var login=new ResidentWechatLoginService(gateway,identities,sessions,apps,wechat,recoveries);
        when(gateway.authenticate("invalid-code")).thenThrow(new ResidentLoginException(ResidentLoginException.Code.WECHAT_REJECTED));
        assertThrows(ResidentLoginException.class,()->login.login("invalid-code"));
        when(gateway.authenticate("wrong-app-code")).thenReturn(new WechatCodeGateway.Identity("wx0000000000000002","synthetic-open",null));
        assertThrows(ResidentLoginException.class,()->login.login("wrong-app-code"));
        assertEquals("PENDING",db.identities.get("789").getBindingState());
        when(gateway.authenticate("fresh-code")).thenReturn(new WechatCodeGateway.Identity(OfflineResidentStore.APP,"synthetic-open",null));
        var recovered=login.login("fresh-code");assertEquals("456",recovered.memberId());
        assertEquals("ACTIVE",db.identities.get("789").getBindingState());
        assertEquals("456",sessions.verifyAccess(recovered.session().accessToken(),SessionDomain.RESIDENT,OfflineResidentStore.APP).identity().subject());
        verify(gateway).authenticate("fresh-code");
    }
    @Test void expiredProofNeverCreatesPendingMapping() {
        when(proof.verify(any(),anyString(),anyString())).thenReturn(new MemberOwnershipProof.Verified("456","synthetic-expired",MemberOwnershipProof.Kind.APPROVED_EVIDENCE,0));
        assertEquals(PROOF_REJECTED,assertThrows(ResidentProfileException.class,this::link).getCode());
        assertTrue(db.attempts.isEmpty());assertEquals("123",db.identities.get("789").getMemberId());
    }
    @Test void disabledHistoryGateAndInvalidBReject(){props.setHistoryReady(false);assertEquals(HISTORY_PENDING,assertThrows(ResidentProfileException.class,this::link).getCode());props.setHistoryReady(true);db.people.get("456").setDisabled(false);assertThrows(ResidentProfileException.class,this::link);assertTrue(db.attempts.isEmpty());}
}
