package cn.lili.common.security.session;

import cn.lili.common.properties.JWTTokenProperties;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.context.UserContext;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.session.http.*;
import cn.lili.common.security.token.SecretKeyUtil;
import cn.lili.modules.member.entity.dos.*;
import cn.lili.modules.member.entity.vo.StoreUserMenuVO;
import cn.lili.modules.member.service.*;
import cn.lili.modules.member.token.StoreTokenGenerate;
import cn.lili.modules.member.mapper.MerchantGrantMapper;
import cn.lili.modules.member.merchant.MerchantAuthorizationService;
import cn.lili.modules.member.merchant.MerchantOwnershipProof;
import cn.lili.modules.permission.entity.dos.AdminUser;
import cn.lili.modules.permission.entity.vo.UserMenuVO;
import cn.lili.modules.permission.service.*;
import cn.lili.modules.store.entity.dos.Store;
import cn.lili.modules.store.service.StoreService;
import cn.lili.modules.system.token.ManagerTokenGenerate;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.*;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static cn.lili.common.security.session.SessionException.Code.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Real codec/core/HTTP adapter and current-authorization code; mocked data and atomic store, no services. */
@SuppressWarnings("unchecked")
class SessionHttpIntegrationTest {
    @AfterEach void clear() {
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    void fourDomainMatrixRejectsTwelveCrossDomainCombinations(SessionDomain source) throws Exception {
        var f = new Fixture();
        var pair = f.issue(source);
        for (var target : SessionDomain.values()) {
            var result = f.read(target, pair.accessToken());
            assertEquals(source == target ? 200 : 403, result.response.getStatus());
            assertEquals(source == target && target != SessionDomain.APPLICANT ? 1 : 0, result.calls);
            if (source == target && target == SessionDomain.APPLICANT) {
                assertNull(result.user, "Applicant is not a STORE AuthUser");
                assertTrue(result.response.getContentAsString().contains("RESTRICTED"));
            }
        }
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    void anonymousAndLegacyCredentialsCannotObtainNewApplicantOrRefresh(SessionDomain domain) throws Exception {
        var f = new Fixture();
        var absent = f.read(domain, null);
        assertEquals(403, absent.response.getStatus());
        assertEquals(0, absent.calls);
        var legacyRefresh = f.run(domain, "POST", SessionRoutes.refresh(domain), null,
                new Gson().toJson(Map.of("refreshToken", "synthetic-legacy-credential")), Map.of());
        assertEquals(403, legacyRefresh.response.getStatus());
        assertEquals(0, legacyRefresh.calls);
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    void refreshUsesOnlyBodyPurposeDomainAndServerApplication(SessionDomain domain) throws Exception {
        var f = new Fixture();
        var pair = f.issue(domain);
        assertEquals(403, f.read(domain, pair.refreshToken()).response.getStatus());
        assertEquals(403, f.refresh(domain, pair.accessToken()).response.getStatus());
        var result = f.refresh(domain, pair.refreshToken());
        assertEquals(200, result.response.getStatus());
        assertEquals("no-store", result.response.getHeader("Cache-Control"));
        var json = JsonParser.parseString(result.response.getContentAsString()).getAsJsonObject().getAsJsonObject("result");
        String nextAccess = json.get("accessToken").getAsString();
        assertEquals(200, f.read(domain, nextAccess).response.getStatus());
        assertEquals(403, f.read(domain, pair.accessToken()).response.getStatus());
        assertEquals(403, f.refresh(domain, pair.refreshToken()).response.getStatus());
        for (var other : SessionDomain.values()) {
            if (other != domain) assertEquals(403, f.refresh(other, pair.refreshToken()).response.getStatus());
        }
        var oldUrl = f.run(domain, "GET", SessionRoutes.refresh(domain) + "/" + pair.refreshToken(), null, null, Map.of());
        assertEquals(403, oldUrl.response.getStatus());
        assertEquals(0, oldUrl.calls, "new Refresh never reaches a legacy URL handler");
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    void logoutRevokesOnlyCurrentSidAndNeverInvokesLegacyLogout(SessionDomain domain) throws Exception {
        var f = new Fixture();
        var one = f.issue(domain);
        var two = f.issue(domain);
        var other = f.issue(domain == SessionDomain.MANAGER ? SessionDomain.RESIDENT : SessionDomain.MANAGER);
        var result = f.run(domain, "POST", SessionRoutes.logout(domain), one.accessToken(), null, Map.of());
        assertEquals(200, result.response.getStatus());
        assertEquals(0, result.calls, "no legacy wildcard deletion");
        assertEquals(403, f.read(domain, one.accessToken()).response.getStatus());
        assertEquals(403, f.refresh(domain, one.refreshToken()).response.getStatus());
        assertEquals(200, f.read(domain, two.accessToken()).response.getStatus());
        assertEquals(200, f.refresh(domain, two.refreshToken()).response.getStatus());
        var otherDomain = domain == SessionDomain.MANAGER ? SessionDomain.RESIDENT : SessionDomain.MANAGER;
        assertEquals(200, f.read(otherDomain, other.accessToken()).response.getStatus());
    }

    @Test void applicantAndStoreSelectorsCannotChooseAnotherPersonOrStore() throws Exception {
        var f = new Fixture();
        for (var domain : List.of(SessionDomain.APPLICANT, SessionDomain.STORE)) {
            var pair = f.issue(domain);
            for (String selector : List.of("memberId", "applicantId", "storeId", "clerkId", "role", "isSuper", "appId")) {
                var result = f.run(domain, "GET", readPath(domain), pair.accessToken(), null,
                        Map.of(selector, "synthetic-foreign-value"));
                assertEquals(403, result.response.getStatus());
                assertEquals(0, result.calls);
            }
        }
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    void stageOneToFiveWritesAndWrongMethodsStopBeforeSideEffectHandler(SessionDomain domain) throws Exception {
        var f = new Fixture();
        var pair = f.issue(domain);
        for (String path : List.of("/buyer/store/store/apply/first", "/buyer/order/order", "/store/goods/goods",
                "/store/order/order", "/manager/store/store/audit", readPath(domain))) {
            var result = f.run(domain, "POST", path, pair.accessToken(), "{}", Map.of());
            assertEquals(403, result.response.getStatus());
            assertEquals(0, result.calls);
        }
        assertEquals(1, f.state.records.size(), "no extra Session or business write");
        verify(f.members, never()).save(any(Member.class));
        verify(f.stores, never()).save(any(Store.class));
    }

    @ParameterizedTest @EnumSource(value = SessionDomain.class, names = {"RESIDENT", "APPLICANT", "STORE"})
    void missingMappingProofAndChangedBindingVersionFailClosed(SessionDomain domain) throws Exception {
        var f = new Fixture();
        var pair = f.issue(domain);
        when(f.verifiers.getIfUnique()).thenReturn(null);
        assertEquals(503, f.read(domain, pair.accessToken()).response.getStatus());
        assertEquals(503, f.refresh(domain, pair.refreshToken()).response.getStatus());
        when(f.verifiers.getIfUnique()).thenReturn(identity -> { throw new SessionException(DENIED); });
        assertEquals(403, f.read(domain, pair.accessToken()).response.getStatus());
        assertEquals(403, f.refresh(domain, pair.refreshToken()).response.getStatus());
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    void disabledOrDeletedAccountDeniesAccessAndRefresh(SessionDomain domain) throws Exception {
        var f = new Fixture();
        var pair = f.issue(domain);
        if (domain == SessionDomain.MANAGER) f.admin.setStatus(false);
        else f.member.setDisabled(false);
        assertEquals(403, f.read(domain, pair.accessToken()).response.getStatus());
        assertEquals(403, f.refresh(domain, pair.refreshToken()).response.getStatus());
    }

    @Test void ownerStaffClerkAndStoreAreReadFromCurrentUniqueRelations() throws Exception {
        var f = new Fixture();
        var owner = f.issue(SessionDomain.STORE);
        assertEquals("synthetic-store", f.read(SessionDomain.STORE, owner.accessToken()).user.getStoreId());
        f.clerk.setStatus(false);
        assertEquals(403, f.read(SessionDomain.STORE, owner.accessToken()).response.getStatus());
        f.clerk.setStatus(true);
        f.store.setStoreDisable("CLOSED");
        assertEquals(403, f.refresh(SessionDomain.STORE, owner.refreshToken()).response.getStatus());
        f.store.setStoreDisable("OPEN");
        when(f.clerks.list(any(QueryWrapper.class))).thenReturn(List.of(f.clerk, f.clerk));
        assertEquals(403, f.read(SessionDomain.STORE, owner.accessToken()).response.getStatus());
        when(f.clerks.list(any(QueryWrapper.class))).thenReturn(List.of(f.clerk));
        f.clerk.setShopkeeper(false);
        f.store.setMemberId("synthetic-other-owner");
        var staff = f.issue(SessionDomain.STORE);
        assertEquals(200, f.read(SessionDomain.STORE, staff.accessToken()).response.getStatus());
        when(f.storeMenus.getUserRoleMenu("synthetic-clerk")).thenReturn(List.of());
        assertEquals(403, f.read(SessionDomain.STORE, staff.accessToken()).response.getStatus());
        assertEquals(403, f.refresh(SessionDomain.STORE, staff.refreshToken()).response.getStatus());
    }

    @Test void oldSuperAndPermissionCacheCannotReplaceCurrentManagerGrant() {
        var f = new Fixture();
        var jwtUser = AuthUser.builder().id("synthetic-actor").username("old-name")
                .role(UserEnums.MANAGER).isSuper(true).build();
        var current = f.authorization.legacy(jwtUser);
        assertFalse(current.getIsSuper());
        assertThrows(SessionException.class, () -> f.authorization.checkRoute(current, "POST", "/manager/passport/user/admin/edit", false));
        var grant = new UserMenuVO();
        grant.setPermission("/manager/passport/user/admin/edit");
        grant.setIsSuper(true);
        when(f.menus.findAllMenu("synthetic-actor")).thenReturn(List.of(grant));
        f.authorization.checkRoute(current, "POST", "/manager/passport/user/admin/edit", false);
        when(f.menus.findAllMenu("synthetic-actor")).thenReturn(List.of());
        assertThrows(SessionException.class, () -> f.authorization.checkRoute(current, "POST", "/manager/passport/user/admin/edit", false));
    }

    @Test void unconfiguredApplicationsCannotCreateNewSessions() {
        var f = new Fixture();
        f.apps.setManagerApplication(null);
        assertThrows(SessionException.class, () -> f.issue(SessionDomain.MANAGER));
        f.apps.setMerchantApplication(f.apps.getResidentApplication());
        assertThrows(SessionException.class, () -> f.issue(SessionDomain.RESIDENT));
    }

    @Test void malformedInputsAndUpstreamExceptionsNeverEchoCredentials() throws Exception {
        var f = new Fixture();
        var pair = f.issue(SessionDomain.RESIDENT);
        var bad = f.run(SessionDomain.RESIDENT, "POST", SessionRoutes.refresh(SessionDomain.RESIDENT), null,
                "{\"refreshToken\":\"" + pair.refreshToken(), Map.of());
        assertEquals(403, bad.response.getStatus());
        assertFalse(bad.response.getContentAsString().contains(pair.refreshToken()), "no parser input in response");
        when(f.members.getById("synthetic-actor")).thenThrow(new IllegalStateException(pair.accessToken()));
        var denied = f.read(SessionDomain.RESIDENT, pair.accessToken());
        assertEquals(403, denied.response.getStatus());
        assertFalse(denied.response.getContentAsString().contains(pair.accessToken()), "no upstream cause in response");
    }

    private static String readPath(SessionDomain domain) {
        return switch (domain) {
            case RESIDENT -> "/buyer/passport/member";
            case APPLICANT -> SessionRoutes.APPLICANT + "/identity";
            case STORE -> "/store/member/user/info";
            case MANAGER -> "/manager/passport/user/info";
        };
    }
    private record Result(MockHttpServletResponse response, int calls, AuthUser user) { }

    private static class Fixture {
        final SessionApplications apps = new SessionApplications();
        final ObjectProvider<SessionIdentityVerifier> verifiers = mock(ObjectProvider.class);
        final MemberService members = mock(MemberService.class);
        final ClerkService clerks = mock(ClerkService.class);
        final StoreService stores = mock(StoreService.class);
        final AdminUserService admins = mock(AdminUserService.class);
        final MenuService menus = mock(MenuService.class);
        final StoreMenuService storeMenus = mock(StoreMenuService.class);
        final Member member = new Member();
        final Clerk clerk = new Clerk();
        final Store store = new Store();
        final AdminUser admin = new AdminUser();
        final State state = new State();
        boolean applicantFixture;
        final CurrentSessionAuthorization authorization;
        final SessionService sessions;

        Fixture() {
            apps.setResidentApplication("synthetic-resident-app");
            apps.setMerchantApplication("synthetic-merchant-app");
            apps.setManagerApplication("synthetic-manager-web");
            member.setId("synthetic-actor"); member.setUsername("synthetic-member"); member.setDisabled(true); member.setDeleteFlag(false);
            clerk.setId("synthetic-clerk"); clerk.setMemberId(member.getId()); clerk.setStoreId("synthetic-store");
            clerk.setStatus(true); clerk.setDeleteFlag(false); clerk.setShopkeeper(true); clerk.setIsSuper(false);
            store.setId(clerk.getStoreId()); store.setMemberId(member.getId()); store.setStoreDisable("OPEN"); store.setDeleteFlag(false);
            admin.setId(member.getId()); admin.setUsername("synthetic-manager"); admin.setDeleteFlag(false); admin.setStatus(true); admin.setIsSuper(false);
            when(members.getById("synthetic-actor")).thenReturn(member);
            when(admins.getById("synthetic-actor")).thenReturn(admin);
            when(clerks.list(any(QueryWrapper.class))).thenAnswer(i -> applicantFixture ? List.of() : List.of(clerk));
            when(stores.list(any(QueryWrapper.class))).thenAnswer(i -> !applicantFixture
                    && member.getId().equals(store.getMemberId()) ? List.of(store) : List.of());
            when(stores.getById("synthetic-store")).thenReturn(store);
            when(menus.findAllMenu("synthetic-actor")).thenReturn(List.of());
            var grant = new StoreUserMenuVO(); grant.setPermission("/store/member/user*"); grant.setIsSuper(false);
            when(storeMenus.getUserRoleMenu("synthetic-clerk")).thenReturn(List.of(grant));
            when(verifiers.getIfUnique()).thenReturn(identity -> {
                if (!Long.valueOf(1).equals(identity.bindingVersion())) throw new SessionException(DENIED);
            });
            authorization = new CurrentSessionAuthorization(apps, verifiers, members, clerks, stores, admins,
                    menus, storeMenus, new ManagerTokenGenerate(), new StoreTokenGenerate());
            MerchantGrantMapper merchantGrants=mock(MerchantGrantMapper.class);
            when(merchantGrants.current(anyString(),anyString(),nullable(String.class))).thenAnswer(i ->
                    storeMenus.getUserRoleMenu(clerk.getId()).stream().map(menu -> {
                        var current=new MerchantGrantMapper.Grant(); current.setRoleId("synthetic-role");
                        current.setStoreId(store.getId()); current.setMenuId("synthetic-menu");
                        current.setPermission(menu.getPermission());current.setWriteGrant(menu.getSuper());return current;
                    }).toList());
            ObjectProvider<MerchantOwnershipProof> ownership=mock(ObjectProvider.class);
            when(ownership.getIfUnique()).thenReturn(identity -> new MerchantOwnershipProof.Verified(identity.application(),
                    identity.bindingId(),identity.bindingVersion(),identity.subject(),"synthetic-approved-proof",
                    MerchantOwnershipProof.Kind.APPROVED_EVIDENCE));
            authorization.setMerchantAuthorization(new MerchantAuthorizationService(apps,members,clerks,stores,merchantGrants,ownership));
            byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
            var properties = new JWTTokenProperties(); properties.setSecretKey(Base64.getEncoder().encodeToString(key));
            sessions = new SessionService(state, new SecretKeyUtil(properties), authorization, Clock.systemUTC());
        }

        SessionTokens issue(SessionDomain domain) {
            if(domain==SessionDomain.APPLICANT || domain==SessionDomain.STORE) applicantFixture=domain==SessionDomain.APPLICANT;
            return sessions.create(new SessionIdentity("synthetic-actor", domain, apps.application(domain),
                    domain == SessionDomain.MANAGER ? null : "synthetic-mapping", domain == SessionDomain.MANAGER ? null : 1L));
        }
        Result read(SessionDomain domain, String access) throws Exception {
            return run(domain, "GET", readPath(domain), access, null, Map.of());
        }
        Result refresh(SessionDomain domain, String refresh) throws Exception {
            return run(domain, "POST", SessionRoutes.refresh(domain), null, new Gson().toJson(Map.of("refreshToken", refresh)), Map.of());
        }
        Result run(SessionDomain domain, String method, String path, String access, String body, Map<String, String> params) throws Exception {
            SecurityContextHolder.clearContext();
            var request = new MockHttpServletRequest(method, path);
            if (access != null) request.addHeader("accessToken", access);
            params.forEach(request::setParameter);
            if (body != null) { request.setContentType("application/json"); request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
            RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
            var response = new MockHttpServletResponse();
            var calls = new AtomicInteger();
            AuthUser[] user = new AuthUser[1];
            new SessionHttpFilter(sessions, authorization, apps, domain).doFilter(request, response, (r, s) -> {
                if (!SessionHttpFilter.verifiedRequest()) { response.setStatus(403); return; }
                user[0] = UserContext.getCurrentUser();
                calls.incrementAndGet(); // A side-effect sentinel, never a real business service.
            });
            return new Result(response, calls.get(), user[0]);
        }
    }

    private static class State implements SessionService.Store {
        final Map<String, SessionRecord> records = new HashMap<>();
        private String key(SessionRecord value) { return RedisSessionStore.key(value.identity().domain(), value.identity().application(), value.sid()); }
        @Override public synchronized boolean create(SessionRecord value) { return records.putIfAbsent(key(value), value) == null; }
        @Override public synchronized SessionRecord load(SessionDomain domain, String app, String sid) { return records.get(RedisSessionStore.key(domain, app, sid)); }
        @Override public synchronized boolean rotate(SessionRecord old, SessionRecord next) {
            if (!old.equals(records.get(key(old))) || old.status() != SessionRecord.Status.ACTIVE) return false;
            records.put(key(old), next); return true;
        }
        @Override public synchronized boolean revoke(SessionRecord old) {
            var current = records.get(key(old));
            if (current == null || !current.identity().equals(old.identity())) return false;
            records.put(key(old), current.revoked()); return true;
        }
    }
}
