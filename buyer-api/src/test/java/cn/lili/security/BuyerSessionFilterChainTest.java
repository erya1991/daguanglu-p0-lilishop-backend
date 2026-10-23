package cn.lili.security;

import cn.lili.cache.*;
import cn.lili.common.properties.*;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.context.UserContext;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.session.*;
import cn.lili.common.security.session.http.*;
import cn.lili.common.security.token.*;
import cn.lili.common.security.CustomAccessDeniedHandler;
import cn.lili.controller.passport.MemberBuyerController;
import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.member.service.*;
import cn.lili.modules.member.token.StoreTokenGenerate;
import cn.lili.modules.permission.service.*;
import cn.lili.modules.store.service.StoreService;
import cn.lili.modules.system.token.ManagerTokenGenerate;
import cn.lili.modules.verification.service.VerificationService;
import com.google.gson.Gson;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.*;
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
import jakarta.servlet.Filter;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Actual BuyerSecurityConfig and filter chain, isolated MVC handlers, no Spring Boot/service startup. */
@SuppressWarnings("unchecked")
class BuyerSessionFilterChainTest {
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    Beans beans;

    @BeforeEach void start() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Beans.class, BuyerSecurityConfig.class);
        context.refresh();
        beans = context.getBean(Beans.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }
    @AfterEach void stop() { context.close(); SecurityContextHolder.clearContext(); }

    @Test void newResidentUsesCoreAndThreeForeignDomainsAreRejected() throws Exception {
        for (var domain : SessionDomain.values()) {
            int before = beans.calls.get();
            var result = mvc.perform(get("/buyer/passport/member").header("accessToken", beans.credential(domain))).andReturn();
            assertEquals(domain == SessionDomain.RESIDENT ? 200 : 403, result.getResponse().getStatus());
            assertEquals(domain == SessionDomain.RESIDENT ? before + 1 : before, beans.calls.get());
        }
        verify(beans.sessions, atLeastOnce()).verifyAccess(anyString(), eq(SessionDomain.RESIDENT), eq("synthetic-r"));
    }

    @Test void broadIgnoredConfigurationCannotExposeIdentityOrLogout() throws Exception {
        assertEquals(403, mvc.perform(get("/buyer/passport/member")).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(post("/buyer/passport/member/logout")).andReturn().getResponse().getStatus());
        assertEquals(0, beans.calls.get());
        assertEquals(200, mvc.perform(get("/buyer/public/catalog")).andReturn().getResponse().getStatus());
    }

    @Test void validLegacyBuyerSurvivesButDisabledCurrentMemberDoesNot() throws Exception {
        String token = beans.legacy();
        assertEquals(200, mvc.perform(get("/buyer/passport/member").header("accessToken", token)).andReturn().getResponse().getStatus());
        beans.member.setDisabled(false);
        assertEquals(403, mvc.perform(get("/buyer/passport/member").header("accessToken", token)).andReturn().getResponse().getStatus());
        assertEquals(1, beans.calls.get());
        verifyNoInteractions(beans.sessions);
    }

    @Test void stageWritesStopBeforeHandlerAndNewLogoutNeverCallsLegacyHandler() throws Exception {
        String token = beans.credential(SessionDomain.RESIDENT);
        assertEquals(403, mvc.perform(post("/buyer/store/store/apply/first").header("accessToken", token)).andReturn().getResponse().getStatus());
        assertEquals(0, beans.calls.get());
        assertEquals(200, mvc.perform(post("/buyer/passport/member/logout").header("accessToken", token)).andReturn().getResponse().getStatus());
        assertEquals(0, beans.calls.get());
        verify(beans.sessions).revoke(any(SessionService.VerifiedSession.class));
    }

    @Test void actualLegacyLoginControllerStillDelegatesToNativeLogin() {
        var controller = new MemberBuyerController();
        var verification = mock(VerificationService.class);
        when(verification.check(anyString(), any())).thenReturn(true);
        var token = new Token();
        when(beans.members.usernameLogin("synthetic-user", "synthetic-password")).thenReturn(token);
        ReflectionTestUtils.setField(controller, "memberService", beans.members);
        ReflectionTestUtils.setField(controller, "verificationService", verification);
        assertSame(token, controller.userLogin("synthetic-user", "synthetic-password", "synthetic-uuid").getResult());
    }

    @Configuration @EnableWebMvc
    static class Beans {
        final AtomicInteger calls = new AtomicInteger();
        final SessionService sessions = mock(SessionService.class);
        final MemberService members = mock(MemberService.class);
        final Member member = new Member();
        final Map<Object, Object> entries = new HashMap<>();
        SecretKeyUtil keys;
        SessionApplications apps;
        @Bean Cache<String> cache() { return mock(Cache.class, i -> {
            if (i.getMethod().getName().equals("hasKey")) return entries.containsKey(i.getArgument(0));
            return null;
        }); }
        @Bean IgnoredUrlsProperties ignored() {
            var p = new IgnoredUrlsProperties(); p.setUrls(new ArrayList<>(List.of("/buyer/passport/member/**", "/buyer/public/**", "/buyer/store/**"))); return p;
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
                var claims = keys.parseClaims(token);
                if (!expected.name().equals(claims.get("domain"))) throw new SessionException(SessionException.Code.INVALID_CREDENTIAL);
                var identity = new SessionIdentity("synthetic-actor", expected, apps.application(expected), "synthetic-map", 1L);
                var verified = mock(SessionService.VerifiedSession.class);
                when(verified.identity()).thenReturn(identity); when(verified.sid()).thenReturn("synthetic-sid"); return verified;
            }); return sessions;
        }
        @Bean CurrentSessionAuthorization current(SessionApplications apps) {
            member.setId("synthetic-actor"); member.setUsername("synthetic-member"); member.setDisabled(true); member.setDeleteFlag(false);
            when(members.getById("synthetic-actor")).thenReturn(member);
            ObjectProvider<SessionIdentityVerifier> verifiers = mock(ObjectProvider.class);
            when(verifiers.getIfUnique()).thenReturn(identity -> { });
            return new CurrentSessionAuthorization(apps, verifiers, members, mock(ClerkService.class), mock(StoreService.class),
                    mock(AdminUserService.class), mock(MenuService.class), mock(StoreMenuService.class), new ManagerTokenGenerate(), new StoreTokenGenerate());
        }
        @Bean CorsConfigurationSource corsConfigurationSource() { return new UrlBasedCorsConfigurationSource(); }
        @Bean AuthenticationProvider provider() { return new AuthenticationProvider() {
            public Authentication authenticate(Authentication authentication) { throw new AssertionError("native password authentication not invoked here"); }
            public boolean supports(Class<?> type) { return true; }
        }; }
        @Bean Endpoints endpoints() { return new Endpoints(calls); }
        String credential(SessionDomain domain) {
            return Jwts.builder().claim("sv", 1).claim("domain", domain.name()).subject("synthetic-actor")
                    .expiration(new Date(System.currentTimeMillis() + 60000)).signWith(keys.generalKey(), Jwts.SIG.HS256).compact();
        }
        String legacy() {
            var user = AuthUser.builder().id("synthetic-actor").username("synthetic-member").role(UserEnums.MEMBER).build();
            String token = Jwts.builder().claim("userContext", new Gson().toJson(user)).subject(user.getUsername())
                    .expiration(new Date(System.currentTimeMillis() + 60000)).signWith(keys.generalKey(), Jwts.SIG.HS256).compact();
            entries.put(CachePrefix.ACCESS_TOKEN.getPrefix(UserEnums.MEMBER, user.getId()) + token, 1); return token;
        }
    }
    @RestController static class Endpoints {
        final AtomicInteger calls;
        Endpoints(AtomicInteger calls) { this.calls = calls; }
        @GetMapping("/buyer/passport/member") String own() { assertNotNull(UserContext.getCurrentUser()); calls.incrementAndGet(); return "OWN"; }
        @PostMapping({"/buyer/passport/member/logout", "/buyer/store/store/apply/first"}) String write() { calls.incrementAndGet(); return "SIDE_EFFECT_SENTINEL"; }
        @GetMapping("/buyer/public/catalog") String catalog() { return "PUBLIC"; }
    }
}
