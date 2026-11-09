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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mock.web.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.*;

import java.security.SecureRandom;
import java.time.*;
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

    @Test void managerStoreFilterIsAnObjectFilterOnlyOnFiniteStage3ReadRoutes() throws Exception {
        var f=new Fixture();var pair=f.issue(SessionDomain.MANAGER);
        for(String route:List.of("/manager/p0/s3/trades","/manager/p0/s3/verifications")) {
            var grant=new UserMenuVO();grant.setPermission(route);grant.setIsSuper(false);
            when(f.menus.findAllMenu("synthetic-actor")).thenReturn(List.of(grant));
            var result=f.run(SessionDomain.MANAGER,"GET",route,pair.accessToken(),null,Map.of("storeId","9007199254740993"));
            assertEquals(200,result.response.getStatus());assertEquals(1,result.calls);
            var other=f.run(SessionDomain.MANAGER,"GET",route,pair.accessToken(),null,Map.of("memberId","foreign"));
            assertEquals(403,other.response.getStatus());assertEquals(0,other.calls);
        }
        var unrelated=f.run(SessionDomain.MANAGER,"GET","/manager/passport/user/info",pair.accessToken(),null,Map.of("storeId","foreign"));
        assertEquals(403,unrelated.response.getStatus());assertEquals(0,unrelated.calls);
        var resident=f.issue(SessionDomain.RESIDENT);var denied=f.run(SessionDomain.RESIDENT,"GET","/buyer/p0/s3/orders",resident.accessToken(),null,Map.of("storeId","foreign"));
        assertEquals(403,denied.response.getStatus());assertEquals(0,denied.calls);
    }

    @Test void stage3PrivateRouteRequiresCurrentSessionAndCannotFallBackToLegacy() throws Exception {
        var f=new Fixture(); var token=f.issue(SessionDomain.RESIDENT);
        String route="/buyer/p0/s3/orders/9007199254740993";
        assertTrue(SessionRoutes.requiresAuthentication(new MockHttpServletRequest("GET",route)));
        var valid=f.run(SessionDomain.RESIDENT,"GET",route,token.accessToken(),null,Map.of());
        assertEquals(200,valid.response.getStatus()); assertEquals(1,valid.calls);
        for(String credential:List.of("legacy-jwt","")) {
            var denied=f.run(SessionDomain.RESIDENT,"GET",route,credential,null,Map.of());
            assertEquals(403,denied.response.getStatus()); assertEquals(0,denied.calls);
        }
        var wrongDomain=f.run(SessionDomain.STORE,"GET",route,token.accessToken(),null,Map.of());
        assertEquals(403,wrongDomain.response.getStatus()); assertEquals(0,wrongDomain.calls);
        var wrongMethod=f.run(SessionDomain.RESIDENT,"POST",route,token.accessToken(),"{}",Map.of());
        assertEquals(403,wrongMethod.response.getStatus()); assertEquals(0,wrongMethod.calls);
        var selector=f.run(SessionDomain.RESIDENT,"GET",route,token.accessToken(),null,Map.of("memberId","foreign"));
        assertEquals(403,selector.response.getStatus()); assertEquals(0,selector.calls);
        f.sessions.revoke(f.sessions.verifyAccess(token.accessToken(),SessionDomain.RESIDENT,f.apps.application(SessionDomain.RESIDENT)));
        var revoked=f.run(SessionDomain.RESIDENT,"GET",route,token.accessToken(),null,Map.of());
        assertEquals(403,revoked.response.getStatus()); assertEquals(0,revoked.calls);
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
    void freshRefreshMatrixRejectsOtherDomainsWithoutConsumingTheSource(SessionDomain source) throws Exception {
        var f = new Fixture();
        var pair = f.issue(source);
        for (var target : SessionDomain.values()) {
            if (target == source) continue;
            var denied = f.refresh(target, pair.refreshToken());
            assertEquals(403, denied.response.getStatus());
            assertEquals(0, denied.calls);
            assertEquals(0, f.state.records.values().iterator().next().generation());
            assertEquals(200, f.read(source, pair.accessToken()).response.getStatus());
        }
        assertEquals(200, f.refresh(source, pair.refreshToken()).response.getStatus());
        assertEquals(1, f.state.records.values().iterator().next().generation());
        assertEquals(1, f.state.records.size(), "cross-domain Refresh never creates another Session");
    }

    @Test void staffHasItsOwnAccessAndFreshRefreshIsolationMatrix() throws Exception {
        var f = new Fixture();
        f.clerk.setShopkeeper(false);
        f.store.setMemberId("synthetic-other-owner");
        var staff = f.issue(SessionDomain.STORE);
        for (var target : SessionDomain.values()) {
            var read = f.read(target, staff.accessToken());
            assertEquals(target == SessionDomain.STORE ? 200 : 403, read.response.getStatus());
            assertEquals(target == SessionDomain.STORE ? 1 : 0, read.calls);
            if (target == SessionDomain.STORE) {
                assertEquals("synthetic-clerk", read.user.getClerkId());
                assertEquals("synthetic-store", read.user.getStoreId());
                assertFalse(read.user.getIsSuper(), "Staff never inherits Owner/super authority");
            } else {
                assertEquals(403, f.refresh(target, staff.refreshToken()).response.getStatus());
            }
        }
        var rotated = refreshResult(f.refresh(SessionDomain.STORE, staff.refreshToken()));
        assertEquals(200, f.read(SessionDomain.STORE, rotated.get("accessToken").getAsString()).response.getStatus());
        when(f.storeMenus.getUserRoleMenu("synthetic-clerk")).thenReturn(List.of());
        var current = f.state.records.values().iterator().next();
        assertEquals(1, current.generation());
        assertEquals(403, f.read(SessionDomain.STORE, rotated.get("accessToken").getAsString()).response.getStatus());
        assertEquals(403, f.refresh(SessionDomain.STORE, rotated.get("refreshToken").getAsString()).response.getStatus());
        verify(f.members, never()).save(any(Member.class));
        verify(f.stores, never()).save(any(Store.class));
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    void invalidExpiredRevokedAndWrongPurposeCredentialsNeverInvokeAHandler(SessionDomain domain) throws Exception {
        var f = new Fixture();
        var pair = f.issue(domain);
        for (String invalid : List.of(tamper(pair.accessToken()), pair.refreshToken())) {
            var denied = f.read(domain, invalid);
            assertEquals(403, denied.response.getStatus());
            assertEquals(0, denied.calls);
        }
        assertEquals(403, f.refresh(domain, pair.accessToken()).response.getStatus());
        f.clock.advance(Duration.ofMinutes(15));
        assertEquals(403, f.read(domain, pair.accessToken()).response.getStatus());
        var rotated = refreshResult(f.refresh(domain, pair.refreshToken()));
        assertEquals(200, f.read(domain, rotated.get("accessToken").getAsString()).response.getStatus());
        var logout = f.run(domain, "POST", SessionRoutes.logout(domain), rotated.get("accessToken").getAsString(), null, Map.of());
        assertEquals(200, logout.response.getStatus());
        assertEquals(0, logout.calls);
        assertEquals(403, f.read(domain, rotated.get("accessToken").getAsString()).response.getStatus());
        assertEquals(403, f.refresh(domain, rotated.get("refreshToken").getAsString()).response.getStatus());
        assertEquals(1, f.state.records.size());
        assertEquals(SessionRecord.Status.REVOKED, f.state.records.values().iterator().next().status());
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    void deviceOneRefreshAndLogoutLeaveDeviceTwoAndAnotherApplicationUsable(SessionDomain domain) throws Exception {
        var f = new Fixture();
        var first = f.issue(domain);
        var second = f.issue(domain);
        var otherDomain = domain == SessionDomain.RESIDENT ? SessionDomain.MANAGER : SessionDomain.RESIDENT;
        var other = f.issue(otherDomain);
        var current = refreshResult(f.refresh(domain, first.refreshToken()));
        assertEquals(403, f.read(domain, first.accessToken()).response.getStatus());
        assertEquals(200, f.read(domain, second.accessToken()).response.getStatus());
        assertEquals(200, f.run(domain, "POST", SessionRoutes.logout(domain), current.get("accessToken").getAsString(), null, Map.of()).response.getStatus());
        assertEquals(403, f.refresh(domain, current.get("refreshToken").getAsString()).response.getStatus());
        assertEquals(200, f.read(domain, second.accessToken()).response.getStatus());
        assertEquals(200, f.refresh(domain, second.refreshToken()).response.getStatus());
        assertEquals(200, f.read(otherDomain, other.accessToken()).response.getStatus());
        assertEquals(200, f.refresh(otherDomain, other.refreshToken()).response.getStatus());
        assertEquals(3, f.state.records.size());
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    @ExtendWith(OutputCaptureExtension.class)
    void unavailableApplicationRemains503AndDoesNotReadSessionStorage(SessionDomain domain, CapturedOutput output) throws Exception {
        var f = new Fixture();
        var pair = f.issue(domain);
        String application = f.apps.application(domain);
        int loads = f.state.loads;
        switch (domain) {
            case RESIDENT -> f.apps.setResidentApplication(null);
            case APPLICANT, STORE -> f.apps.setMerchantApplication(null);
            case MANAGER -> f.apps.setManagerApplication(null);
        }
        assertAudit(output, f.read(domain, pair.accessToken()), domain, "ACCESS", "AUTHORIZATION_REQUIRED", 503);
        assertAudit(output, f.refresh(domain, pair.refreshToken()), domain, "REFRESH", "AUTHORIZATION_REQUIRED", 503);
        assertEquals(loads, f.state.loads, "missing trusted application is not a Token or Redis authorization result");
        assertEquals(0, f.state.records.values().iterator().next().generation());
        switch (domain) {
            case RESIDENT -> f.apps.setResidentApplication(application);
            case APPLICANT, STORE -> f.apps.setMerchantApplication(application);
            case MANAGER -> f.apps.setManagerApplication(application);
        }
        f.state.unavailable = true;
        assertAudit(output, f.read(domain, pair.accessToken()), domain, "ACCESS", "STORE_UNAVAILABLE", 503);
        assertAudit(output, f.refresh(domain, pair.refreshToken()), domain, "REFRESH", "STORE_UNAVAILABLE", 503);
    }

    @ParameterizedTest @EnumSource(SessionDomain.class)
    @ExtendWith(OutputCaptureExtension.class)
    void refusalAuditExistsOnceAndNeverIncludesCredentialsOrRequestData(SessionDomain domain, CapturedOutput output) throws Exception {
        var f = new Fixture();
        var pair = f.issue(domain);
        assertEquals(200, f.read(domain, pair.accessToken()).response.getStatus());
        assertFalse(output.getAll().contains("STAGE0_SESSION_REJECT"), "successful access is not a refusal");
        var invalid = f.read(domain, tamper(pair.accessToken()));
        assertAudit(output, invalid, domain, "ACCESS", "INVALID_CREDENTIAL", 403);
        var wrongPurpose = f.refresh(domain, pair.accessToken());
        assertAudit(output, wrongPurpose, domain, "REFRESH", "INVALID_CREDENTIAL", 403);
        var selector = f.run(domain, "GET", readPath(domain), pair.accessToken(), null,
                Map.of("memberId", "canary-personal-phone-15500000000"));
        assertAudit(output, selector, domain, "ACCESS", "INVALID_CREDENTIAL", 403);
        assertEquals(200, f.run(domain, "POST", SessionRoutes.logout(domain), pair.accessToken(), null, Map.of()).response.getStatus());
        assertAudit(output, f.read(domain, pair.accessToken()), domain, "ACCESS", "INVALID_CREDENTIAL", 403);
        var expiry = new Fixture();
        var expiring = expiry.issue(domain);
        expiry.clock.advance(Duration.ofMinutes(15));
        assertAudit(output, expiry.read(domain, expiring.accessToken()), domain, "ACCESS", "INVALID_CREDENTIAL", 403);
        expiry.clock.advance(domain.absoluteLifetime());
        assertAudit(output, expiry.refresh(domain, expiring.refreshToken()), domain, "REFRESH", "INVALID_CREDENTIAL", 403);
        assertFalse(output.getAll().contains(pair.accessToken()));
        assertFalse(output.getAll().contains(pair.refreshToken()));
        assertFalse(output.getAll().contains(pair.sid()));
        assertFalse(output.getAll().contains("synthetic-actor"));
        assertFalse(output.getAll().contains("15500000000"));
        assertFalse(output.getAll().contains(expiring.accessToken()));
        assertFalse(output.getAll().contains(expiring.refreshToken()));
    }

    @Test @ExtendWith(OutputCaptureExtension.class)
    void malformedLegacyUriAndUpstreamErrorsHaveOnlyFixedAuditFields(CapturedOutput output) throws Exception {
        var f = new Fixture();
        var pair = f.issue(SessionDomain.RESIDENT);
        String canary = "canary-private-code-cookie-secret";
        var malformed = f.run(SessionDomain.RESIDENT, "POST", SessionRoutes.refresh(SessionDomain.RESIDENT), null,
                "{\"refreshToken\":\"" + canary, Map.of());
        assertAudit(output, malformed, SessionDomain.RESIDENT, "REFRESH", "UNEXPECTED_FAILURE", 403);
        var legacy = f.run(SessionDomain.RESIDENT, "GET", SessionRoutes.refresh(SessionDomain.RESIDENT) + "/" + pair.refreshToken(), null, null, Map.of());
        assertAudit(output, legacy, SessionDomain.RESIDENT, "REFRESH", "INVALID_CREDENTIAL", 403);
        when(f.members.getById("synthetic-actor")).thenThrow(new IllegalStateException(canary));
        assertAudit(output, f.read(SessionDomain.RESIDENT, pair.accessToken()), SessionDomain.RESIDENT, "ACCESS", "DENIED", 403);
        assertFalse(output.getAll().contains(canary));
        assertFalse(output.getAll().contains(pair.refreshToken()));
        assertFalse(output.getAll().contains("IllegalStateException"));
    }

    @Test @ExtendWith(OutputCaptureExtension.class)
    void anonymousAndFailedFreshLoginHaveOneSafeAuditWithoutChangingLegacyResponses(CapturedOutput output) throws Exception {
        var f = new Fixture();
        for (var domain : SessionDomain.values()) {
            assertAudit(output, f.read(domain, null), domain, "ACCESS",
                    domain == SessionDomain.APPLICANT || domain == SessionDomain.STORE ? "INVALID_CREDENTIAL" : "UNVERIFIED_REQUEST", 403);
        }
        for (var domain : List.of(SessionDomain.RESIDENT, SessionDomain.STORE, SessionDomain.MANAGER)) {
            var request = new MockHttpServletRequest("POST", switch (domain) {
                case RESIDENT -> SessionRoutes.RESIDENT_LOGIN;
                case STORE -> SessionRoutes.MERCHANT_LOGIN;
                default -> SessionRoutes.MANAGER_LOGIN;
            });
            var response = new MockHttpServletResponse();
            new SessionHttpFilter(f.sessions, f.authorization, f.apps, domain).doFilter(request, response, (r, s) -> response.setStatus(400));
            assertAudit(output, new Result(response, 1, null), domain, "LOGIN", "AUTHENTICATION_FAILED", 400);
        }
    }

    private static void assertAudit(CapturedOutput output, Result result, SessionDomain domain, String action, String reason, int status) {
        assertEquals(status, result.response.getStatus());
        String id = result.response.getHeader("X-Session-Audit-Id");
        assertNotNull(id, "a rejected HTTP request needs a traceable safe audit event");
        assertTrue(id.matches("[a-f0-9]{32}"));
        var lines = output.getAll().lines().filter(line -> line.contains("eventId=" + id)).toList();
        assertEquals(1, lines.size(), "one HTTP refusal must not be recorded twice");
        String event = lines.getFirst();
        assertTrue(event.contains("STAGE0_SESSION_REJECT"));
        assertTrue(event.contains("domain=" + domain));
        assertTrue(event.contains("action=" + action));
        assertTrue(event.contains("reason=" + reason));
        assertTrue(event.contains("status=" + status));
        assertTrue(event.contains("time="));
    }

    private static com.google.gson.JsonObject refreshResult(Result result) throws Exception {
        assertEquals(200, result.response.getStatus());
        return JsonParser.parseString(result.response.getContentAsString()).getAsJsonObject().getAsJsonObject("result");
    }

    private static String tamper(String token) {
        int index = token.lastIndexOf('.') + 1;
        return token.substring(0, index) + (token.charAt(index) == 'A' ? 'B' : 'A') + token.substring(index + 1);
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
    @ExtendWith(OutputCaptureExtension.class)
    void disabledOrDeletedAccountDeniesAccessAndRefresh(SessionDomain domain, CapturedOutput output) throws Exception {
        var f = new Fixture();
        var pair = f.issue(domain);
        if (domain == SessionDomain.MANAGER) f.admin.setStatus(false);
        else f.member.setDisabled(false);
        assertAudit(output, f.read(domain, pair.accessToken()), domain, "ACCESS", "DENIED", 403);
        assertAudit(output, f.refresh(domain, pair.refreshToken()), domain, "REFRESH", "DENIED", 403);
        if (domain == SessionDomain.MANAGER) { f.admin.setStatus(true); f.admin.setDeleteFlag(true); }
        else { f.member.setDisabled(true); f.member.setDeleteFlag(true); }
        assertAudit(output, f.read(domain, pair.accessToken()), domain, "ACCESS", "DENIED", 403);
        assertAudit(output, f.refresh(domain, pair.refreshToken()), domain, "REFRESH", "DENIED", 403);
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
        final MutableClock clock = new MutableClock();
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
            sessions = new SessionService(state, new SecretKeyUtil(properties), authorization, clock);
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
        int loads;
        boolean unavailable;
        private String key(SessionRecord value) { return RedisSessionStore.key(value.identity().domain(), value.identity().application(), value.sid()); }
        @Override public synchronized boolean create(SessionRecord value) { return records.putIfAbsent(key(value), value) == null; }
        @Override public synchronized SessionRecord load(SessionDomain domain, String app, String sid) {
            loads++;
            if (unavailable) throw new SessionException(STORE_UNAVAILABLE);
            return records.get(RedisSessionStore.key(domain, app, sid));
        }
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

    private static class MutableClock extends Clock {
        private long millis = Math.floorDiv(System.currentTimeMillis(), 1000) * 1000;
        void advance(Duration value) { millis += value.toMillis(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(millis); }
        @Override public long millis() { return millis; }
    }
}
