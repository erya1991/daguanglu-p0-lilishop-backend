package cn.lili.modules.connect.wechat;

import cn.lili.common.properties.JWTTokenProperties;
import cn.lili.common.security.session.*;
import cn.lili.common.security.session.http.*;
import cn.lili.common.security.token.SecretKeyUtil;
import cn.lili.common.utils.SnowFlake;
import cn.lili.modules.connect.entity.WechatIdentity;
import cn.lili.modules.member.entity.dos.*;
import cn.lili.modules.member.mapper.MerchantGrantMapper;
import cn.lili.modules.member.merchant.*;
import cn.lili.modules.member.service.*;
import cn.lili.modules.member.token.StoreTokenGenerate;
import cn.lili.modules.permission.service.*;
import cn.lili.modules.store.entity.dos.Store;
import cn.lili.modules.store.service.StoreService;
import cn.lili.modules.system.token.ManagerTokenGenerate;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real shared mapping/transaction logic, merchant grants, verifier and Session core; no external resources.
 * REQ-S0-M01-001..011 / S03-001..009. Synthetic Store proofs are test-only, not production approval. */
@SuppressWarnings("unchecked")
class MerchantWechatLoginTest {
    static Object previousIds;
    @BeforeAll static void ids() {
        previousIds=org.springframework.test.util.ReflectionTestUtils.getField(SnowFlake.class,"snowflake");
        SnowFlake.initialize(31,31);
    }
    @AfterAll static void restoreIds() {org.springframework.test.util.ReflectionTestUtils.setField(SnowFlake.class,"snowflake",previousIds);}

    final ResidentWechatLoginTest.Model db=new ResidentWechatLoginTest.Model();
    final SessionApplications apps=new SessionApplications();
    final ResidentWechatProperties resident=new ResidentWechatProperties();
    final MerchantWechatProperties merchant=new MerchantWechatProperties();
    final WechatCodeGateway gateway=mock(WechatCodeGateway.class);
    final MemberService members=mock(MemberService.class);
    final ClerkService clerks=mock(ClerkService.class);
    final StoreService stores=mock(StoreService.class);
    final MerchantGrantMapper grantMapper=mock(MerchantGrantMapper.class);
    final ObjectProvider<MerchantOwnershipProof> proofProviders=mock(ObjectProvider.class);
    final RedisSessionStore redis=mock(RedisSessionStore.class);
    final Map<String,SessionRecord> records=new ConcurrentHashMap<>();
    final WechatIdentityService mappings;
    final MerchantAuthorizationService grants;
    final SessionService sessions;
    final SecretKeyUtil keys;
    final MerchantWechatLoginService login;

    MerchantWechatLoginTest() {
        apps.setResidentApplication("wx0000000000000001"); apps.setMerchantApplication("wx0000000000000002");
        apps.setManagerApplication("synthetic-manager");
        resident.setSchemaReady(true);
        merchant.setSchemaReady(true);merchant.setEnabled(true);
        merchant.setAppSecret(UUID.randomUUID().toString().replace("-",""));
        mappings=new WechatIdentityService(db.mappings,db.members,resident,merchant,db);
        grants=new MerchantAuthorizationService(apps,members,clerks,stores,grantMapper,proofProviders);
        when(members.getById(anyString())).thenAnswer(i->db.committedMembers.get(i.getArgument(0)));
        when(clerks.list(any(QueryWrapper.class))).thenReturn(List.of());
        when(stores.list(any(QueryWrapper.class))).thenReturn(List.of());
        when(gateway.authenticate(anyString(),eq(SessionDomain.APPLICANT))).thenReturn(
                new WechatCodeGateway.Identity(apps.getMerchantApplication(),"synthetic-merchant-open",null));
        var verifier=new WechatSessionIdentityVerifier(mappings,apps,null,null,merchant);
        ObjectProvider<SessionIdentityVerifier> verifiers=mock(ObjectProvider.class);
        when(verifiers.getIfUnique()).thenReturn(verifier);
        var authorization=new CurrentSessionAuthorization(apps,verifiers,members,clerks,stores,
                mock(AdminUserService.class),mock(MenuService.class),mock(StoreMenuService.class),
                new ManagerTokenGenerate(),new StoreTokenGenerate());
        authorization.setMerchantAuthorization(grants);
        ObjectProvider<SessionAuthorization> authorizations=mock(ObjectProvider.class);
        when(authorizations.getIfUnique()).thenReturn(authorization);
        byte[] raw=new byte[32];new SecureRandom().nextBytes(raw);
        var jwt=new JWTTokenProperties();jwt.setSecretKey(Base64.getEncoder().encodeToString(raw));keys=new SecretKeyUtil(jwt);
        when(redis.create(any())).thenAnswer(i->{
            assertNull(db.pending.get(),"Mapping transaction must have committed before Session creation");
            SessionRecord record=i.getArgument(0);
            assertTrue(db.committedMembers.containsKey(record.identity().subject()));
            return records.putIfAbsent(record.sid(),record)==null;
        });
        when(redis.load(any(),anyString(),anyString())).thenAnswer(i->records.get(i.getArgument(2)));
        when(redis.rotate(any(),any())).thenAnswer(i->{SessionRecord before=i.getArgument(0);return records.replace(before.sid(),before,i.getArgument(1));});
        when(redis.revoke(any())).thenAnswer(i->{SessionRecord before=i.getArgument(0);return records.computeIfPresent(before.sid(),(k,v)->
                new SessionRecord(v.protocolVersion(),v.sid(),v.identity(),SessionRecord.Status.REVOKED,v.generation(),
                        v.accessDigest(),v.refreshDigest(),v.createdAt(),v.accessExpiresAt(),v.absoluteExpiresAt()))!=null;});
        sessions=new SessionService(redis,keys,authorizations);
        login=new MerchantWechatLoginService(gateway,mappings,sessions,apps,merchant,grants);
    }
    @AfterEach void cleanup() {keys.destroy();TransactionSynchronizationManager.clear();}

    @Test void firstRepeatApplicantLoginIsStableAndCreatesNoOperatingObjects() {
        var first=login.login("first-code");var second=login.login("repeat-code");
        assertEquals(first.memberId(),second.memberId());assertEquals("APPLICANT",first.identity());assertNull(first.role());
        assertNotEquals(first.session().sid(),second.session().sid());
        assertEquals(1,db.committedMappings.size());assertEquals(1,db.committedMembers.size());
        Member person=db.committedMembers.get(first.memberId());
        assertNull(person.getMobile());assertNull(person.getGradeId());assertFalse(person.getHaveStore());
        var active=sessions.verifyAccess(first.session().accessToken(),SessionDomain.APPLICANT,apps.getMerchantApplication());
        assertEquals(first.memberId(),active.identity().subject());
        verify(clerks,never()).save(any(Clerk.class));verify(stores,never()).save(any(Store.class));
        verify(members,never()).findByMobile(anyString());verify(members,never()).autoRegister(any());
        verify(gateway,never()).authenticate(anyString());
    }
    @Test void concurrentFirstLoginUsesExistingUniqueMappingRollbackLogic() throws Exception {
        db.firstReadBarrier=new CyclicBarrier(6);
        try(var pool=Executors.newFixedThreadPool(6)) {
            List<Future<MerchantWechatLoginService.LoginResult>> calls=new ArrayList<>();
            for(int n=0;n<6;n++){String code="concurrent-"+n;calls.add(pool.submit(()->login.login(code)));}
            Set<String> ids=new HashSet<>();for(var call:calls)ids.add(call.get(15,TimeUnit.SECONDS).memberId());
            assertEquals(1,ids.size());assertEquals(1,db.committedMembers.size());assertEquals(1,db.committedMappings.size());
            assertEquals(5,db.rollbacks.get());assertEquals(6,records.size());
        }
    }
    @Test void crossAppAndCaseDifferencesDoNotMergeByUnionId() {
        when(gateway.authenticate(anyString(),eq(SessionDomain.APPLICANT))).thenAnswer(i->
                new WechatCodeGateway.Identity(apps.getMerchantApplication(),i.getArgument(0),"shared-union"));
        var one=login.login("same-open");var two=login.login("Same-open");assertNotEquals(one.memberId(),two.memberId());
        var residentIdentity=mappings.resolve(new WechatCodeGateway.Identity(apps.getResidentApplication(),"same-open","shared-union"));
        assertNotEquals(one.memberId(),residentIdentity.subject());assertEquals(3,db.committedMembers.size());
        assertThrows(SessionException.class,()->sessions.verifyAccess(one.session().accessToken(),SessionDomain.RESIDENT,apps.getResidentApplication()));
    }
    @Test void unqualifiedLegacyRelationshipNeverGetsOwnerOrApplicantFallback() {
        var first=login.login("first-code");configureStore(first.memberId(),true,false);
        assertEquals(MerchantAccessException.Code.PROOF_SOURCE_PENDING,
                assertThrows(MerchantAccessException.class,()->login.login("next-code")).getCode());
        assertEquals(1,records.size());
        assertThrows(SessionException.class,()->sessions.verifyAccess(first.session().accessToken(),SessionDomain.APPLICANT,apps.getMerchantApplication()));
        assertThrows(SessionException.class,()->sessions.refresh(first.session().refreshToken(),SessionDomain.APPLICANT,apps.getMerchantApplication()));
    }
    @ParameterizedTest @ValueSource(booleans={true,false})
    void syntheticIndependentProofOnlyAllowsExactOwnerOrStaffAndRevocationIsCurrent(boolean owner) {
        var first=login.login("first-code");var clerk=configureStore(first.memberId(),owner,true);
        var operating=login.login("operating-code");assertEquals("STORE",operating.identity());assertEquals(owner?"OWNER":"STAFF",operating.role());
        sessions.verifyAccess(operating.session().accessToken(),SessionDomain.STORE,apps.getMerchantApplication());
        assertThrows(SessionException.class,()->sessions.verifyAccess(operating.session().accessToken(),SessionDomain.APPLICANT,apps.getMerchantApplication()));
        clerk.setStatus(false);
        assertThrows(SessionException.class,()->sessions.verifyAccess(operating.session().accessToken(),SessionDomain.STORE,apps.getMerchantApplication()));
        assertThrows(SessionException.class,()->sessions.refresh(operating.session().refreshToken(),SessionDomain.STORE,apps.getMerchantApplication()));
    }
    @Test void refreshAndLogoutOnlyAffectCurrentApplicantSid() {
        var one=login.login("first-code");var two=login.login("second-device");
        var rotated=sessions.refresh(one.session().refreshToken(),SessionDomain.APPLICANT,apps.getMerchantApplication());
        assertThrows(SessionException.class,()->sessions.verifyAccess(one.session().accessToken(),SessionDomain.APPLICANT,apps.getMerchantApplication()));
        assertThrows(SessionException.class,()->sessions.refresh(one.session().refreshToken(),SessionDomain.APPLICANT,apps.getMerchantApplication()));
        assertThrows(SessionException.class,()->sessions.verifyAccess(rotated.refreshToken(),SessionDomain.APPLICANT,apps.getMerchantApplication()));
        assertThrows(SessionException.class,()->sessions.refresh(rotated.accessToken(),SessionDomain.APPLICANT,apps.getMerchantApplication()));
        sessions.revoke(sessions.verifyAccess(rotated.accessToken(),SessionDomain.APPLICANT,apps.getMerchantApplication()));
        assertThrows(SessionException.class,()->sessions.refresh(rotated.refreshToken(),SessionDomain.APPLICANT,apps.getMerchantApplication()));
        sessions.verifyAccess(two.session().accessToken(),SessionDomain.APPLICANT,apps.getMerchantApplication());
        sessions.refresh(two.session().refreshToken(),SessionDomain.APPLICANT,apps.getMerchantApplication());
    }
    @ParameterizedTest @ValueSource(strings={"clerk-query","owned-query","multi-clerk","disabled-member","deleted-member","pending-map","version"})
    void staleAndUnavailableIdentityNeverContinuesAsNormalApplicant(String fault) {
        var first=login.login("first-code");var member=db.committedMembers.get(first.memberId());
        var mapping=db.committedMappings.values().iterator().next();
        switch(fault){
            case "clerk-query" -> when(clerks.list(any(QueryWrapper.class))).thenThrow(new IllegalStateException("private-sql-cause"));
            case "owned-query" -> when(stores.list(any(QueryWrapper.class))).thenThrow(new IllegalStateException("private-sql-cause"));
            case "multi-clerk" -> when(clerks.list(any(QueryWrapper.class))).thenReturn(List.of(new Clerk(),new Clerk()));
            case "disabled-member" -> member.setDisabled(false);
            case "deleted-member" -> member.setDeleteFlag(true);
            case "pending-map" -> mapping.setBindingState("PENDING");
            case "version" -> mapping.setBindingVersion(2L);
        }
        assertThrows(SessionException.class,()->sessions.verifyAccess(first.session().accessToken(),SessionDomain.APPLICANT,apps.getMerchantApplication()));
        assertThrows(SessionException.class,()->sessions.refresh(first.session().refreshToken(),SessionDomain.APPLICANT,apps.getMerchantApplication()));
        if(!fault.equals("version")) {
            RuntimeException error=assertThrows(RuntimeException.class,()->login.login("new-code"));
            assertNull(error.getCause());assertFalse(error.toString().contains("private-sql-cause"));
            assertEquals(1,records.size());
        }
    }
    @ParameterizedTest @ValueSource(strings={"member-write","mapping-write","commit","collision-no-winner"})
    void failedDatabaseCommitDoesNotIssueSessionOrLeaveNewMember(String fault) {
        db.fault=fault;assertThrows(ResidentLoginException.class,()->login.login("synthetic-code"));
        assertTrue(db.committedMembers.isEmpty());assertTrue(db.committedMappings.isEmpty());verifyNoInteractions(redis);
    }
    @Test void appMismatchAndBadCodeNeverReachPersistence() {
        when(gateway.authenticate(anyString(),eq(SessionDomain.APPLICANT))).thenReturn(
                new WechatCodeGateway.Identity(apps.getResidentApplication(),"synthetic-open",null));
        assertEquals(ResidentLoginException.Code.WECHAT_REJECTED,assertThrows(ResidentLoginException.class,()->login.login("wrong-app")).getCode());
        assertThrows(ResidentLoginException.class,()->login.login("bad?code"));verifyNoInteractions(db.members,db.mappings,redis);
    }
    @Test void merchantGatesAreIndependentAndPendingMappingsCannotUseResidentHandoff() {
        resident.setSchemaReady(false);
        var applicant=login.login("merchant-only");assertEquals("APPLICANT",applicant.identity());
        var record=records.get(applicant.session().sid());
        assertThrows(ResidentLoginException.class,()->mappings.verifyPending(record.identity()));
        merchant.setSchemaReady(false);
        assertEquals(ResidentLoginException.Code.B04_PENDING,assertThrows(ResidentLoginException.class,()->login.login("next-code")).getCode());
        assertThrows(SessionException.class,()->sessions.verifyAccess(applicant.session().accessToken(),SessionDomain.APPLICANT,apps.getMerchantApplication()));
    }
    @Test void disabledDefaultsAndOuterTransactionCannotReachGateway() {
        merchant.setSchemaReady(false);assertThrows(ResidentLoginException.class,()->login.login("code-one"));
        merchant.setSchemaReady(true);merchant.setEnabled(false);assertThrows(ResidentLoginException.class,()->login.login("code-two"));
        merchant.setEnabled(true);TransactionSynchronizationManager.setActualTransactionActive(true);
        assertThrows(ResidentLoginException.class,()->login.login("code-three"));verifyNoInteractions(gateway,db.members,db.mappings,redis);
    }
    private Clerk configureStore(String memberId,boolean owner,boolean proven) {
        Clerk clerk=new Clerk();clerk.setId("synthetic-clerk");clerk.setMemberId(memberId);clerk.setStoreId("synthetic-store");
        clerk.setStatus(true);clerk.setDeleteFlag(false);clerk.setShopkeeper(owner);clerk.setIsSuper(true);
        Store store=new Store();store.setId(clerk.getStoreId());store.setMemberId(owner?memberId:"another-owner");
        store.setStoreName("Synthetic Store");store.setStoreDisable("OPEN");store.setDeleteFlag(false);
        when(clerks.list(any(QueryWrapper.class))).thenReturn(List.of(clerk));when(stores.getById(store.getId())).thenReturn(store);
        when(stores.list(any(QueryWrapper.class))).thenReturn(owner?List.of(store):List.of());
        MerchantGrantMapper.Grant grant=new MerchantGrantMapper.Grant();grant.setStoreId(store.getId());
        grant.setRoleId("synthetic-role");grant.setMenuId("synthetic-menu");grant.setPermission("/store/member/user");grant.setWriteGrant(false);
        when(grantMapper.current(anyString(),anyString(),nullable(String.class))).thenReturn(List.of(grant));
        if(proven)when(proofProviders.getIfUnique()).thenReturn(identity->new MerchantOwnershipProof.Verified(
                identity.application(),identity.bindingId(),identity.bindingVersion(),identity.subject(),"synthetic-proof-reference",
                MerchantOwnershipProof.Kind.QUALIFIED_ACCOUNT_CREDENTIAL));
        return clerk;
    }
}
