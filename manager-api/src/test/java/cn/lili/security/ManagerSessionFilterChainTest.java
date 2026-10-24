package cn.lili.security;

import cn.lili.cache.*;
import cn.lili.common.properties.*;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.CustomAccessDeniedHandler;
import cn.lili.common.security.context.UserContext;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.session.*;
import cn.lili.common.security.session.http.*;
import cn.lili.common.security.token.*;
import cn.lili.controller.manager.passport.AdminUserManagerController;
import cn.lili.controller.seller.passport.StorePassportController;
import cn.lili.modules.member.entity.dos.*;
import cn.lili.modules.member.entity.vo.StoreUserMenuVO;
import cn.lili.modules.member.service.*;
import cn.lili.modules.member.token.StoreTokenGenerate;
import cn.lili.modules.member.merchant.MerchantAuthorizationService;
import cn.lili.modules.permission.entity.dos.AdminUser;
import cn.lili.modules.permission.entity.vo.UserMenuVO;
import cn.lili.modules.permission.service.*;
import cn.lili.modules.store.entity.dos.Store;
import cn.lili.modules.store.service.StoreService;
import cn.lili.modules.system.token.ManagerTokenGenerate;
import cn.lili.modules.verification.service.VerificationService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.gson.Gson;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.cors.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Real chain selection/order and current grants, synthetic handlers and mocked resources only. */
@SuppressWarnings("unchecked")
class ManagerSessionFilterChainTest {
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    Beans beans;

    @BeforeEach void start() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Beans.class, ManagerStoreSecurityConfig.class);
        context.refresh();
        beans = context.getBean(Beans.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }
    @AfterEach void stop() { context.close(); SecurityContextHolder.clearContext(); }

    @ParameterizedTest @EnumSource(value = SessionDomain.class, names = {"APPLICANT", "STORE", "MANAGER"})
    void actualChainsRejectEveryForeignDomainAndAnonymous(SessionDomain target) throws Exception {
        assertEquals(403, mvc.perform(get(path(target))).andReturn().getResponse().getStatus());
        for (var source : SessionDomain.values()) {
            int before = beans.calls.get();
            var result = mvc.perform(get(path(target)).header("accessToken", beans.credential(source))).andReturn();
            assertEquals(source == target ? 200 : 403, result.getResponse().getStatus());
            assertEquals(source == target && target != SessionDomain.APPLICANT ? before + 1 : before, beans.calls.get());
            if (source == target && target == SessionDomain.APPLICANT) assertTrue(result.getResponse().getContentAsString().contains("RESTRICTED"));
        }
        verify(beans.sessions, atLeastOnce()).verifyAccess(anyString(), eq(target), eq(beans.apps.application(target)));
    }

    @ParameterizedTest @EnumSource(value = UserEnums.class, names = {"STORE", "MANAGER"})
    void validLegacySessionSurvivesAndCurrentRevocationWins(UserEnums role) throws Exception {
        var target = role == UserEnums.STORE ? SessionDomain.STORE : SessionDomain.MANAGER;
        String endpoint = role == UserEnums.STORE ? "/store/legacy/read" : path(target);
        String credential = beans.legacy(role, true);
        assertEquals(200, mvc.perform(get(endpoint).header("accessToken", credential)).andReturn().getResponse().getStatus());
        if (role == UserEnums.STORE) beans.clerk.setStatus(false); else beans.admin.setStatus(false);
        assertEquals(403, mvc.perform(get(endpoint).header("accessToken", credential)).andReturn().getResponse().getStatus());
        assertEquals(1, beans.calls.get());
        verifyNoInteractions(beans.sessions);
    }

    @Test void jwtSuperAndStaleMenuCacheCannotGrantBusinessWrites() throws Exception {
        for (var role : List.of(UserEnums.STORE, UserEnums.MANAGER)) {
            String token = beans.legacy(role, true);
            String route = role == UserEnums.STORE ? "/store/goods/goods" : "/manager/store/store/audit";
            assertEquals(403, mvc.perform(post(route).header("accessToken", token)).andReturn().getResponse().getStatus());
        }
        assertEquals(0, beans.calls.get());
        verify(beans.cachedStoreMenus, never()).findAllMenu(anyString(), anyString());
    }

    @Test void applicantAndStoreCannotSelectForeignIdentityOrEscalateRole() throws Exception {
        for (var target : List.of(SessionDomain.APPLICANT, SessionDomain.STORE)) {
            for (String field : List.of("applicantId", "memberId", "storeId", "role", "isSuper")) {
                assertEquals(403, mvc.perform(get(path(target)).header("accessToken", beans.credential(target))
                        .param(field, "synthetic-foreign")).andReturn().getResponse().getStatus());
            }
        }
        assertEquals(0, beans.calls.get());
        verifyNoInteractions(beans.sessions);
    }

    @ParameterizedTest @EnumSource(value = SessionDomain.class, names = {"APPLICANT", "STORE", "MANAGER"})
    void refreshAndLogoutUseTheSameCoreAndNoLegacyOrBusinessHandler(SessionDomain target) throws Exception {
        String token = beans.credential(target);
        assertEquals(200, mvc.perform(post(SessionRoutes.refresh(target)).contentType("application/json")
                .content("{\"refreshToken\":\"synthetic-body-refresh\"}")).andReturn().getResponse().getStatus());
        verify(beans.sessions).refresh("synthetic-body-refresh", target, beans.apps.application(target));
        assertEquals(200, mvc.perform(post(SessionRoutes.logout(target)).header("accessToken", token)).andReturn().getResponse().getStatus());
        verify(beans.sessions).revoke(any(SessionService.VerifiedSession.class));
        String business = target == SessionDomain.MANAGER ? "/manager/store/store/audit" : "/store/goods/goods";
        assertEquals(403, mvc.perform(post(business).header("accessToken", token)).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(get(SessionRoutes.refresh(target) + "/" + token)).andReturn().getResponse().getStatus());
        assertEquals(0, beans.calls.get());
    }

    @Test void legacyTokensCannotEnterApplicantOrCrossLegacyDomains() throws Exception {
        for (var role : List.of(UserEnums.MEMBER, UserEnums.STORE, UserEnums.MANAGER)) {
            String token = beans.legacy(role, false);
            assertEquals(403, mvc.perform(get(path(SessionDomain.APPLICANT)).header("accessToken", token)).andReturn().getResponse().getStatus());
            if (role != UserEnums.STORE) assertEquals(403, mvc.perform(get(path(SessionDomain.STORE)).header("accessToken", token)).andReturn().getResponse().getStatus());
            if (role != UserEnums.MANAGER) assertEquals(403, mvc.perform(get(path(SessionDomain.MANAGER)).header("accessToken", token)).andReturn().getResponse().getStatus());
        }
        assertEquals(0, beans.calls.get());
    }

    @Test void nativeManagerAndStoreLoginControllersKeepTheirDelegates() {
        var verification = mock(VerificationService.class);
        when(verification.check(anyString(), any())).thenReturn(true);
        var token = new Token();
        var manager = new AdminUserManagerController();
        ReflectionTestUtils.setField(manager, "adminUserService", beans.admins);
        ReflectionTestUtils.setField(manager, "verificationService", verification);
        when(beans.admins.login("synthetic-user", "synthetic-password")).thenReturn(token);
        assertSame(token, manager.login("synthetic-user", "synthetic-password", "synthetic-uuid").getResult());
        var store = new StorePassportController();
        ReflectionTestUtils.setField(store, "memberService", beans.members);
        ReflectionTestUtils.setField(store, "verificationService", verification);
        when(beans.members.usernameStoreLogin("synthetic-user", "synthetic-password")).thenReturn(token);
        assertSame(token, store.userLogin("synthetic-user", "synthetic-password", "synthetic-uuid").getResult());
    }

    static String path(SessionDomain domain) {
        return switch (domain) {
            case APPLICANT -> SessionRoutes.APPLICANT + "/identity";
            case STORE -> "/store/member/user/info";
            case MANAGER -> "/manager/passport/user/info";
            default -> throw new AssertionError();
        };
    }

    @Configuration @EnableWebMvc
    static class Beans {
        final AtomicInteger calls = new AtomicInteger();
        final SessionService sessions = mock(SessionService.class);
        final MemberService members = mock(MemberService.class);
        final ClerkService clerks = mock(ClerkService.class);
        final StoreService stores = mock(StoreService.class);
        final AdminUserService admins = mock(AdminUserService.class);
        final MenuService menus = mock(MenuService.class);
        final StoreMenuService storeMenus = mock(StoreMenuService.class);
        final StoreMenuRoleService cachedStoreMenus = mock(StoreMenuRoleService.class);
        final Member member = new Member();
        final Clerk clerk = new Clerk();
        final Store store = new Store();
        final AdminUser admin = new AdminUser();
        final Map<Object, Object> entries = new HashMap<>();
        SecretKeyUtil keys;
        SessionApplications apps;
        @Bean Cache<String> cache() { return mock(Cache.class, i -> {
            if (i.getMethod().getName().equals("hasKey")) return entries.containsKey(i.getArgument(0));
            if (i.getMethod().getName().equals("get")) return entries.get(i.getArgument(0));
            return null;
        }); }
        @Bean IgnoredUrlsProperties ignored() {
            var p = new IgnoredUrlsProperties(); p.setUrls(new ArrayList<>(List.of("/store/passport/**", "/manager/passport/**"))); return p;
        }
        @Bean CustomAccessDeniedHandler denied() { return new CustomAccessDeniedHandler(); }
        @Bean SecretKeyUtil keys() {
            byte[] raw = new byte[32]; new SecureRandom().nextBytes(raw);
            var p = new JWTTokenProperties(); p.setSecretKey(Base64.getEncoder().encodeToString(raw)); return keys = new SecretKeyUtil(p);
        }
        @Bean SessionApplications apps() {
            apps = new SessionApplications(); apps.setResidentApplication("synthetic-r"); apps.setMerchantApplication("synthetic-m"); apps.setManagerApplication("synthetic-web"); return apps;
        }
        @Bean SessionService sessions() {
            when(sessions.verifyAccess(anyString(), any(), anyString())).thenAnswer(i -> {
                String token = i.getArgument(0); SessionDomain expected = i.getArgument(1);
                SessionDomain actual = token.startsWith("s0o1.") ? SessionDomain.APPLICANT
                        : SessionDomain.valueOf(keys.parseClaims(token).get("domain", String.class));
                if (expected != actual) throw new SessionException(SessionException.Code.INVALID_CREDENTIAL);
                var identity = new SessionIdentity("synthetic-actor", expected, apps.application(expected),
                        expected == SessionDomain.MANAGER ? null : "synthetic-map", expected == SessionDomain.MANAGER ? null : 1L);
                var verified = mock(SessionService.VerifiedSession.class);
                when(verified.identity()).thenReturn(identity); when(verified.sid()).thenReturn("synthetic-sid"); return verified;
            });
            when(sessions.refresh(anyString(), any(), anyString())).thenReturn(new SessionTokens("synthetic-sid", "synthetic-access", "synthetic-refresh", 1, 2));
            return sessions;
        }
        @Bean StoreTokenGenerate storeTokens() { return new StoreTokenGenerate(); }
        @Bean ManagerTokenGenerate managerTokens() { return new ManagerTokenGenerate(); }
        @Bean TokenUtil nativeTokens() { return mock(TokenUtil.class); }
        @Bean JWTTokenProperties tokenProperties() { return new JWTTokenProperties(); }
        @Bean StoreService stores() { return stores; }
        @Bean StoreMenuRoleService cachedStoreMenus() { return cachedStoreMenus; }
        @Bean ClerkService clerks() { return clerks; }
        @Bean MenuService menus() { return menus; }
        @Bean CurrentSessionAuthorization current(SessionApplications apps, StoreTokenGenerate st, ManagerTokenGenerate mt) {
            member.setId("synthetic-actor"); member.setUsername("synthetic-member"); member.setDisabled(true); member.setDeleteFlag(false);
            clerk.setId("synthetic-clerk"); clerk.setMemberId(member.getId()); clerk.setStoreId("synthetic-store");
            clerk.setStatus(true); clerk.setDeleteFlag(false); clerk.setShopkeeper(true); clerk.setIsSuper(false);
            store.setId(clerk.getStoreId()); store.setMemberId(member.getId()); store.setStoreDisable("OPEN"); store.setDeleteFlag(false);
            admin.setId(member.getId()); admin.setUsername("synthetic-manager"); admin.setDeleteFlag(false); admin.setStatus(true); admin.setIsSuper(false);
            when(members.getById("synthetic-actor")).thenReturn(member);
            when(admins.getById("synthetic-actor")).thenReturn(admin);
            when(clerks.list(any(QueryWrapper.class))).thenReturn(List.of(clerk));
            when(stores.getById("synthetic-store")).thenReturn(store);
            var storeGrant = new StoreUserMenuVO(); storeGrant.setPermission("/store/member/user*"); storeGrant.setIsSuper(false);
            var legacyRead = new StoreUserMenuVO(); legacyRead.setPermission("/store/legacy/read"); legacyRead.setIsSuper(false);
            when(storeMenus.getUserRoleMenu("synthetic-clerk")).thenReturn(List.of(storeGrant, legacyRead));
            var managerGrant = new UserMenuVO(); managerGrant.setPermission("/manager/passport/user/info"); managerGrant.setIsSuper(false);
            when(menus.findAllMenu("synthetic-actor")).thenReturn(List.of(managerGrant));
            ObjectProvider<SessionIdentityVerifier> verifiers = mock(ObjectProvider.class);
            when(verifiers.getIfUnique()).thenReturn(identity -> { });
            var current = new CurrentSessionAuthorization(apps, verifiers, members, clerks, stores, admins, menus, storeMenus, mt, st);
            var merchant = mock(MerchantAuthorizationService.class);
            when(merchant.resolve(any())).thenAnswer(i -> {
                SessionIdentity identity = i.getArgument(0);
                if (identity.domain() == SessionDomain.APPLICANT) {
                    return new MerchantAuthorizationService.Context(SessionDomain.APPLICANT, member.getId(), null,
                            null, null, MerchantAuthorizationService.Role.APPLICANT, null);
                }
                var user = AuthUser.builder().id(member.getId()).username(member.getUsername()).role(UserEnums.STORE)
                        .storeId(store.getId()).clerkId(clerk.getId()).isSuper(false).build();
                return new MerchantAuthorizationService.Context(SessionDomain.STORE, member.getId(), clerk.getId(),
                        store.getId(), store.getStoreName(), MerchantAuthorizationService.Role.OWNER, user);
            });
            current.setMerchantAuthorization(merchant);
            return current;
        }
        @Bean CorsConfigurationSource corsConfigurationSource() { return new UrlBasedCorsConfigurationSource(); }
        @Bean AuthenticationProvider provider() { return new AuthenticationProvider() {
            public Authentication authenticate(Authentication authentication) { throw new AssertionError("password authentication not invoked here"); }
            public boolean supports(Class<?> type) { return true; }
        }; }
        @Bean Endpoints endpoints() { return new Endpoints(calls); }
        String credential(SessionDomain domain) {
            if (domain == SessionDomain.APPLICANT) return "s0o1.synthetic-sid.ACCESS.synthetic-secret";
            return Jwts.builder().claim("sv", 1).claim("domain", domain.name()).subject("synthetic-actor")
                    .expiration(new Date(System.currentTimeMillis() + 60000)).signWith(keys.generalKey(), Jwts.SIG.HS256).compact();
        }
        String legacy(UserEnums role, boolean superFlag) {
            var user = AuthUser.builder().id("synthetic-actor").username("synthetic-user").role(role).isSuper(superFlag)
                    .storeId("synthetic-store").clerkId("synthetic-clerk").build();
            String token = Jwts.builder().claim("userContext", new Gson().toJson(user)).subject(user.getUsername())
                    .expiration(new Date(System.currentTimeMillis() + 60000)).signWith(keys.generalKey(), Jwts.SIG.HS256).compact();
            entries.put(CachePrefix.ACCESS_TOKEN.getPrefix(role, user.getId()) + token, 1);
            entries.put(CachePrefix.PERMISSION_LIST.getPrefix(role) + user.getId(), Map.of("SUPER", List.of("/**")));
            return token;
        }
    }
    @RestController static class Endpoints {
        final AtomicInteger calls;
        Endpoints(AtomicInteger calls) { this.calls = calls; }
        @GetMapping({"/store/member/user/info", "/manager/passport/user/info", "/store/legacy/read"}) String own() {
            assertNotNull(UserContext.getCurrentUser()); calls.incrementAndGet(); return "OWN";
        }
        @PostMapping({"/store/goods/goods", "/manager/store/store/audit", "/store/passport/login/logout", "/manager/passport/user/logout"})
        String write() { calls.incrementAndGet(); return "SIDE_EFFECT_SENTINEL"; }
        @GetMapping({"/store/passport/login/refresh/{token}", "/manager/passport/user/refresh/{token}"})
        String oldRefresh() { calls.incrementAndGet(); return "LEGACY_SENTINEL"; }
    }
}
