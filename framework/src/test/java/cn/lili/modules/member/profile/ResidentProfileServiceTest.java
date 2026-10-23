package cn.lili.modules.member.profile;

import cn.lili.cache.*;
import cn.lili.common.security.session.*;
import cn.lili.modules.connect.wechat.*;
import com.google.gson.Gson;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static cn.lili.modules.member.profile.ResidentProfileException.Code.*;

@SuppressWarnings("unchecked")
class ResidentProfileServiceTest {
    final OfflineResidentStore db=new OfflineResidentStore();
    final ResidentProfileProperties props=new ResidentProfileProperties();
    final ResidentWechatProperties wechat=new ResidentWechatProperties();
    final WechatIdentityService identities;
    final ResidentAvatarService avatars=mock(ResidentAvatarService.class);
    final WechatPhoneGateway phones=mock(WechatPhoneGateway.class);
    final Cache<List<String>> cache=mock(Cache.class);
    final ResidentProfileService service;
    final SessionIdentity a=new SessionIdentity("123",SessionDomain.RESIDENT,OfflineResidentStore.APP,"789",1L);
    ResidentProfileServiceTest() {
        props.setSchemaReady(true);wechat.setSchemaReady(true);
        identities=new WechatIdentityService(db.mappings,db.members,wechat,db);
        when(cache.get(CachePrefix.SENSITIVE.getPrefix())).thenReturn(List.of("synthetic-blocked"));
        service=new ResidentProfileService(db.profiles,identities,props,new ResidentTransactions(db),avatars,phones,cache);
    }
    @Test void onlyMinimalOwnProjectionAndUnboundState() {
        var p=service.read(a);assertEquals("123",p.memberId());assertFalse(p.phoneBound());assertNull(p.maskedMobile());
        db.people.get("123").setMobile("13900000001");var json=new Gson().toJson(service.read(a));
        assertTrue(json.contains("139****0001"));assertFalse(json.contains("13900000001"));assertFalse(json.contains("password"));assertFalse(json.contains("phoneRequest"));
    }
    @Test void editAndReadBackNeverTouchUnrelatedFields() {
        db.people.get("123").setMobile("13900000001");when(avatars.ownedUrl("123","999")).thenReturn("https://synthetic.invalid/avatar.png");
        var p=service.edit(a,0,"新昵称","999");assertEquals("新昵称",p.nickname());assertEquals(1,p.version());
        assertEquals("13900000001",db.people.get("123").getMobile());assertEquals(0,db.people.get("456").getVersion());
        assertEquals(p,service.read(a));verify(avatars).ownedUrl("123","999");
    }
    @ParameterizedTest @ValueSource(strings={"", "<script>","bad\nname","a"+"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","synthetic-blocked"})
    void nicknameValidationPreservesOldData(String nickname) {assertThrows(ResidentProfileException.class,()->service.edit(a,0,nickname,null));assertEquals(0,db.edits.get());}
    @Test void missingContentPolicyAndForeignAvatarFailClosed() {
        when(cache.get(anyString())).thenReturn(null);assertEquals(CONTENT_POLICY_PENDING,assertThrows(ResidentProfileException.class,()->service.edit(a,0,"住户",null)).getCode());
        when(cache.get(anyString())).thenReturn(Arrays.asList(null," "));assertEquals(CONTENT_POLICY_PENDING,assertThrows(ResidentProfileException.class,()->service.edit(a,0,"住户",null)).getCode());
        when(avatars.ownedUrl("123","999")).thenThrow(new ResidentProfileException(AVATAR_REJECTED));
        assertThrows(ResidentProfileException.class,()->service.edit(a,0,null,"999"));assertEquals(0,db.edits.get());
    }
    @Test void concurrentEditsAndLateRequestsCannotOverwriteWinner() throws Exception {
        var executor=Executors.newFixedThreadPool(2);try {
            var start=new CyclicBarrier(2);var results=new ArrayList<Future<Boolean>>();
            for(String name:List.of("甲","乙")) results.add(executor.submit(()->{start.await();try{service.edit(a,0,name,null);return true;}catch(ResidentProfileException e){return false;}}));
            int successes=0;for(var f:results)if(f.get())successes++;assertEquals(1,successes);assertEquals(1,db.edits.get());
            assertEquals(PROFILE_CONFLICT,assertThrows(ResidentProfileException.class,()->service.edit(a,0,"迟到",null)).getCode());
        }finally{executor.shutdownNow();}
    }
    @Test void bindUpdateAndIdempotentRetryDoNotReconsumeCode() {
        when(phones.verify("phone-one")).thenReturn("13900000001");var first=service.phone(a,0,"request-phone-0001","phone-one");
        assertTrue(first.phoneBound());assertEquals(first,service.phone(a,0,"request-phone-0001","phone-one"));verify(phones,times(1)).verify("phone-one");
        when(phones.verify("phone-two")).thenReturn("13900000002");assertEquals("139****0002",service.phone(a,1,"request-phone-0002","phone-two").maskedMobile());
        assertEquals(2,db.phoneWrites.get());assertEquals("123",service.read(a).memberId());
        assertThrows(ResidentProfileException.class,()->service.phone(a,1,"request-phone-0002","different-code"));
    }
    @Test void phoneConflictIsNeutralAndDoesNotTakeOverB() {
        db.people.get("456").setMobile("13900000002");when(phones.verify("phone-code")).thenReturn("13900000002");
        var e=assertThrows(ResidentProfileException.class,()->service.phone(a,0,"request-phone-0001","phone-code"));assertEquals(LINK_REQUIRED,e.getCode());
        assertFalse(e.toString().contains("456"));assertNull(db.people.get("123").getMobile());assertEquals("123",db.identities.get("789").getMemberId());
    }
    @Test void twoMembersCannotClaimSameVerifiedPhoneConcurrently() throws Exception {
        var mapping=OfflineResidentStore.copy(db.identities.get("789"),cn.lili.modules.connect.entity.WechatIdentity.class);
        mapping.setId("790");mapping.setOpenId("synthetic-other-open");mapping.setMemberId("456");db.identities.put("790",mapping);
        var b=new SessionIdentity("456",SessionDomain.RESIDENT,OfflineResidentStore.APP,"790",1L);
        var bothVerified=new CyclicBarrier(2);
        when(phones.verify(anyString())).thenAnswer(i->{bothVerified.await(5,TimeUnit.SECONDS);return "13900000003";});
        var executor=Executors.newFixedThreadPool(2);
        try {
            var results=new ArrayList<Future<Boolean>>();
            for(var identity:List.of(a,b)) results.add(executor.submit(()->{
                try {service.phone(identity,0,"request-phone-"+identity.subject(),"code-"+identity.subject());return true;}
                catch(ResidentProfileException e){assertEquals(LINK_REQUIRED,e.getCode());return false;}
            }));
            int winners=0;for(var result:results)if(result.get(10,TimeUnit.SECONDS))winners++;
            assertEquals(1,winners);assertEquals(1,db.phoneWrites.get());
            assertEquals(1,db.people.values().stream().filter(p->"13900000003".equals(p.getMobile())).count());
        } finally {executor.shutdownNow();}
    }
    @ParameterizedTest @ValueSource(strings={"expired","invalid","consumed"})
    void failedOrCancelledPhoneAuthorizationLeavesBasicIdentityValid(String code) {
        when(phones.verify(code)).thenThrow(new ResidentProfileException(PHONE_REJECTED));
        assertThrows(ResidentProfileException.class,()->service.phone(a,0,"request-phone-0001",code));
        assertThrows(ResidentProfileException.class,()->service.phone(a,0,"request-phone-0001",""));
        assertFalse(service.read(a).phoneBound());assertEquals(0,db.phoneWrites.get());
    }
    @ParameterizedTest @ValueSource(strings={"edit","phone","commit"})
    void writeOrCommitFailurePreservesPreviousProfile(String failure) {
        db.fail=failure;when(phones.verify("phone-code")).thenReturn("13900000001");
        if(failure.equals("phone")) assertThrows(ResidentProfileException.class,()->service.phone(a,0,"request-phone-0001","phone-code"));
        else assertThrows(ResidentProfileException.class,()->service.edit(a,0,"新名",null));
        assertEquals("住户",db.people.get("123").getNickname());assertNull(db.people.get("123").getMobile());assertEquals(0,db.people.get("123").getVersion());
    }
    @Test void stalePhoneRequestDoesNotConsumeNewOfficialCode() {
        service.edit(a,0,"新名",null);assertThrows(ResidentProfileException.class,()->service.phone(a,0,"request-phone-0001","code"));verifyNoInteractions(phones);
    }
    @Test void schemaDisabledRevokedMappingAndOtherDomainsReject() {
        props.setSchemaReady(false);assertEquals(B04_PENDING,assertThrows(ResidentProfileException.class,()->service.read(a)).getCode());props.setSchemaReady(true);
        for(var domain:List.of(SessionDomain.STORE,SessionDomain.APPLICANT,SessionDomain.MANAGER))assertThrows(ResidentProfileException.class,()->service.read(new SessionIdentity("123",domain,OfflineResidentStore.APP,"789",1L)));
        db.identities.get("789").setBindingVersion(2L);assertThrows(ResidentProfileException.class,()->service.read(a));
    }
}
