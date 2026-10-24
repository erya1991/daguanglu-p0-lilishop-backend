package cn.lili.modules.member.merchant;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.common.security.session.SessionIdentity;
import cn.lili.common.security.session.SessionException;
import cn.lili.common.security.session.http.CurrentSessionAuthorization;
import cn.lili.common.security.session.http.SessionApplications;
import cn.lili.common.security.session.http.SessionIdentityVerifier;
import cn.lili.modules.member.entity.dos.Clerk;
import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.member.mapper.MerchantGrantMapper;
import cn.lili.modules.member.service.ClerkService;
import cn.lili.modules.member.service.MemberService;
import cn.lili.modules.member.service.StoreMenuService;
import cn.lili.modules.member.token.StoreTokenGenerate;
import cn.lili.modules.permission.service.AdminUserService;
import cn.lili.modules.permission.service.MenuService;
import cn.lili.modules.system.token.ManagerTokenGenerate;
import cn.lili.modules.store.entity.dos.Store;
import cn.lili.modules.store.service.StoreService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Date;
import java.util.List;

import static cn.lili.modules.member.merchant.MerchantAccessException.Code.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real resolver; all records/proofs are synthetic test-only collaborators, no database or network. */
@SuppressWarnings("unchecked")
class MerchantAuthorizationServiceTest {
    @Test void uninstalledResolverCannotUseOldConstructorAsMerchantBypass() {
        var f=new Fixture();var current=f.current(false);
        assertEquals(SessionException.Code.AUTHORIZATION_REQUIRED,
                assertThrows(SessionException.class,()->current.current(f.identity)).getCode());
        var storeIdentity=new SessionIdentity(f.identity.subject(),SessionDomain.STORE,f.identity.application(),f.identity.bindingId(),1L);
        assertThrows(SessionException.class,()->current.current(storeIdentity));
    }

    @Test void currentApplicantCannotBeSilentlyPromotedWhenAnOwnerGrantAppears() {
        var f=new Fixture();var current=f.current(true);
        assertNull(current.current(f.identity));
        f.owner();f.qualifiedProof();
        assertThrows(SessionException.class,()->current.current(f.identity));
        var storeIdentity=new SessionIdentity(f.identity.subject(),SessionDomain.STORE,f.identity.application(),f.identity.bindingId(),1L);
        assertEquals("test-store",current.current(storeIdentity).getStoreId());
        f.clerk.setStatus(false);
        assertThrows(SessionException.class,()->current.current(storeIdentity));
    }

    @Test void applicantHasOnlyPersonalIdentityAndNoStoreOrClerkWrites() {
        var f=new Fixture();
        var context=f.resolver.resolve(f.identity);
        assertEquals(SessionDomain.APPLICANT,context.domain());
        assertEquals(MerchantAuthorizationService.Role.APPLICANT,context.role());
        assertNull(context.authUser()); assertNull(context.clerkId()); assertNull(context.storeId());
        verifyNoInteractions(f.proofs,f.grants);
        verify(f.members,never()).save(any(Member.class));
        verify(f.stores,never()).save(any(Store.class));
        verify(f.clerks,never()).save(any(Clerk.class));
    }

    @Test void relationDoesNotBecomeOperatingPermissionWithoutIndependentProof() {
        var f=new Fixture(); f.owner();
        denied(PROOF_SOURCE_PENDING,()->f.resolver.resolve(f.identity));
        when(f.proofs.getIfUnique()).thenReturn(identity->null);
        denied(PROOF_SOURCE_PENDING,()->f.resolver.resolve(f.identity));
    }

    @Test void syntheticOwnerProofGivesOnlyCurrentStoreReadIdentityAndNeverSuperFlag() {
        var f=new Fixture(); f.owner(); f.qualifiedProof();
        f.clerk.setIsSuper(true);
        var context=f.resolver.resolve(f.identity);
        assertEquals(SessionDomain.STORE,context.domain());
        assertEquals(MerchantAuthorizationService.Role.OWNER,context.role());
        assertEquals("test-store",context.storeId()); assertEquals("test-clerk",context.clerkId());
        assertFalse(context.authUser().getIsSuper());
        assertFalse(context.toString().contains("test-store"));
    }

    @Test void staffUsesCurrentScopedGrantsAndCannotInheritLegacySuper() {
        var f=new Fixture(); f.staff(); f.qualifiedProof(); f.clerk.setIsSuper(true);
        var context=f.resolver.resolve(f.identity);
        assertEquals(MerchantAuthorizationService.Role.STAFF,context.role());
        assertFalse(context.authUser().getIsSuper());
        when(f.grants.current("test-clerk","test-store",null)).thenReturn(List.of());
        denied(DENIED,()->f.resolver.resolve(f.identity));
    }

    @ParameterizedTest @ValueSource(strings={"other-app","other-map","new-version","other-member","no-reference","no-kind"})
    void independentProofMustMatchExactAppMappingVersionAndMember(String invalid) {
        var f=new Fixture(); f.owner();
        var proof=new MerchantOwnershipProof.Verified(invalid.equals("other-app")?"other-app":f.identity.application(),
                invalid.equals("other-map")?"other-map":f.identity.bindingId(),invalid.equals("new-version")?2:1,
                invalid.equals("other-member")?"other-member":f.identity.subject(),invalid.equals("no-reference")?"":"approved-test-evidence",
                invalid.equals("no-kind")?null:MerchantOwnershipProof.Kind.APPROVED_EVIDENCE);
        when(f.proofs.getIfUnique()).thenReturn(identity->proof);
        denied(PROOF_SOURCE_PENDING,()->f.resolver.resolve(f.identity));
        assertFalse(proof.toString().contains("approved-test-evidence"));
    }

    @ParameterizedTest @ValueSource(strings={"member-disabled","member-deleted","clerk-disabled","clerk-deleted",
            "store-closed","store-deleted","store-expired","owner-mismatch","two-clerks","two-stores"})
    void currentRevocationAndAmbiguousRelationsDenyOldIdentity(String mutation) {
        var f=new Fixture(); f.owner(); f.qualifiedProof();
        assertEquals(SessionDomain.STORE,f.resolver.resolve(f.identity).domain());
        switch(mutation) {
            case "member-disabled" -> f.member.setDisabled(false);
            case "member-deleted" -> f.member.setDeleteFlag(true);
            case "clerk-disabled" -> f.clerk.setStatus(false);
            case "clerk-deleted" -> f.clerk.setDeleteFlag(true);
            case "store-closed" -> f.store.setStoreDisable("CLOSED");
            case "store-deleted" -> f.store.setDeleteFlag(true);
            case "store-expired" -> f.store.setStoreEndTime(new Date(System.currentTimeMillis()-1000));
            case "owner-mismatch" -> f.store.setMemberId("other-person");
            case "two-clerks" -> when(f.clerks.list(any(QueryWrapper.class))).thenReturn(List.of(f.clerk,f.clerk));
            case "two-stores" -> when(f.stores.list(any(QueryWrapper.class))).thenReturn(List.of(f.store,f.store));
            default -> throw new AssertionError();
        }
        assertThrows(MerchantAccessException.class,()->f.resolver.resolve(f.identity));
    }

    @Test void orphanStoreRelationshipIsNotPresentedAsNormalApplicant() {
        var f=new Fixture();
        f.member.setHaveStore(true);
        denied(RELATION_CONFLICT,()->f.resolver.resolve(f.identity));
        f.member.setHaveStore(false); f.member.setStoreId("unresolved-store");
        denied(RELATION_CONFLICT,()->f.resolver.resolve(f.identity));
        f.member.setStoreId(null); when(f.stores.list(any(QueryWrapper.class))).thenReturn(List.of(f.store));
        denied(RELATION_CONFLICT,()->f.resolver.resolve(f.identity));
    }

    @ParameterizedTest @ValueSource(strings={"member","clerk","store","grants","proof"})
    void queryOrProofFailureIsNotNoStoreAndDoesNotExposeUpstreamMessage(String failed) {
        var f=new Fixture();
        if(failed.equals("grants")){f.staff();f.qualifiedProof();}
        if(failed.equals("proof")){f.owner();f.qualifiedProof();}
        var cause=new IllegalStateException("synthetic-sensitive-upstream-content");
        switch(failed) {
            case "member" -> when(f.members.getById("test-member")).thenThrow(cause);
            case "clerk" -> when(f.clerks.list(any(QueryWrapper.class))).thenThrow(cause);
            case "store" -> when(f.stores.list(any(QueryWrapper.class))).thenThrow(cause);
            case "grants" -> when(f.grants.current("test-clerk","test-store",null)).thenThrow(cause);
            case "proof" -> when(f.proofs.getIfUnique()).thenReturn(identity->{throw cause;});
            default -> throw new AssertionError();
        }
        var error=assertThrows(MerchantAccessException.class,()->f.resolver.resolve(f.identity));
        assertEquals(DATA_UNAVAILABLE,error.getCode());assertNull(error.getCause());
        assertFalse(error.toString().contains("synthetic-sensitive"));
    }

    @Test void wrongDomainAndResidentAppCannotAcquireMerchantAuthorization() {
        var f=new Fixture();
        denied(DENIED,()->f.resolver.resolve(new SessionIdentity("test-member",SessionDomain.RESIDENT,"resident-test","map",1L)));
        denied(DENIED,()->f.resolver.resolve(new SessionIdentity("test-member",SessionDomain.APPLICANT,"resident-test","map",1L)));
        verifyNoInteractions(f.members,f.clerks,f.stores);
    }

    @Test void crossStoreOrDeletedRoleGrantCannotAuthorizeStaff() {
        var f=new Fixture();f.staff();f.qualifiedProof();
        f.grant.setStoreId("other-store");denied(DENIED,()->f.resolver.resolve(f.identity));
        f.grant.setStoreId("test-store");f.grant.setRoleId(null);denied(DENIED,()->f.resolver.resolve(f.identity));
        f.grant.setRoleId("test-role");f.grant.setPermission(null);denied(DENIED,()->f.resolver.resolve(f.identity));
    }

    private static void denied(MerchantAccessException.Code code,Runnable action) {
        assertEquals(code,assertThrows(MerchantAccessException.class,action::run).getCode());
    }
    private static final class Fixture {
        final SessionApplications apps=new SessionApplications();
        final MemberService members=mock(MemberService.class);
        final ClerkService clerks=mock(ClerkService.class);
        final StoreService stores=mock(StoreService.class);
        final MerchantGrantMapper grants=mock(MerchantGrantMapper.class);
        final ObjectProvider<MerchantOwnershipProof> proofs=mock(ObjectProvider.class);
        final Member member=new Member();final Clerk clerk=new Clerk();final Store store=new Store();
        final MerchantGrantMapper.Grant grant=new MerchantGrantMapper.Grant();
        final SessionIdentity identity=new SessionIdentity("test-member",SessionDomain.APPLICANT,"merchant-test","test-map",1L);
        final MerchantAuthorizationService resolver;
        Fixture() {
            apps.setResidentApplication("resident-test");apps.setMerchantApplication("merchant-test");
            member.setId(identity.subject());member.setUsername("synthetic-user");member.setDisabled(true);member.setDeleteFlag(false);
            when(members.getById(member.getId())).thenReturn(member);
            when(clerks.list(any(QueryWrapper.class))).thenReturn(List.of());when(stores.list(any(QueryWrapper.class))).thenReturn(List.of());
            clerk.setId("test-clerk");clerk.setMemberId(member.getId());clerk.setStoreId("test-store");
            clerk.setStatus(true);clerk.setDeleteFlag(false);clerk.setShopkeeper(true);
            store.setId("test-store");store.setMemberId(member.getId());store.setStoreName("合成店铺");store.setDeleteFlag(false);store.setStoreDisable("OPEN");
            when(stores.getById(store.getId())).thenReturn(store);
            resolver=new MerchantAuthorizationService(apps,members,clerks,stores,grants,proofs);
        }
        void owner() {
            when(clerks.list(any(QueryWrapper.class))).thenReturn(List.of(clerk));
            when(stores.list(any(QueryWrapper.class))).thenReturn(List.of(store));
        }
        void staff() {
            when(clerks.list(any(QueryWrapper.class))).thenReturn(List.of(clerk));
            clerk.setShopkeeper(false);store.setMemberId("other-owner");
            grant.setRoleId("test-role");grant.setStoreId(store.getId());grant.setMenuId("test-menu");
            grant.setPermission("/store/member/user");grant.setWriteGrant(false);
            when(grants.current(clerk.getId(),store.getId(),null)).thenReturn(List.of(grant));
        }
        void qualifiedProof() {
            when(proofs.getIfUnique()).thenReturn(i->new MerchantOwnershipProof.Verified(i.application(),i.bindingId(),
                    i.bindingVersion(),i.subject(),"synthetic-approved-evidence",MerchantOwnershipProof.Kind.APPROVED_EVIDENCE));
        }
        CurrentSessionAuthorization current(boolean install) {
            ObjectProvider<SessionIdentityVerifier> verifiers=mock(ObjectProvider.class);
            when(verifiers.getIfUnique()).thenReturn(identity->{}); // Trusted mapping supplied only by this fixture.
            var result=new CurrentSessionAuthorization(apps,verifiers,members,clerks,stores,mock(AdminUserService.class),
                    mock(MenuService.class),mock(StoreMenuService.class),new ManagerTokenGenerate(),new StoreTokenGenerate());
            if(install)result.setMerchantAuthorization(resolver);
            return result;
        }
    }
}
