package cn.lili.security;

import cn.lili.cache.Cache;
import cn.lili.common.properties.*;
import cn.lili.common.security.CustomAccessDeniedHandler;
import cn.lili.common.security.session.*;
import cn.lili.common.security.session.http.*;
import cn.lili.common.security.token.*;
import cn.lili.controller.passport.connect.*;
import cn.lili.modules.connect.entity.dto.WechatMPLoginParams;
import cn.lili.modules.connect.service.ConnectService;
import cn.lili.modules.connect.wechat.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.cors.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import jakarta.servlet.Filter;
import java.security.SecureRandom;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Actual MVC controller + BuyerSecurityConfig/filter chain. No Boot app or external resources. */
class ResidentWechatLoginControllerTest {
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    Beans beans;
    @BeforeEach void start() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.register(Beans.class, BuyerSecurityConfig.class); context.refresh();
        beans = context.getBean(Beans.class);
        mvc = MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }
    @AfterEach void close() { context.close(); SecurityContextHolder.clearContext(); }

    @Test void exactPostLoginWorksWithoutAnyIgnoredUrlAndReturnsMinimalNoStoreResult() throws Exception {
        when(beans.login.login("synthetic-code")).thenReturn(new ResidentWechatLoginService.LoginResult("synthetic-member", "RESIDENT",
                new SessionTokens("synthetic-sid", "synthetic-access", "synthetic-refresh", 1L, 2L)));
        var response = mvc.perform(post(SessionRoutes.RESIDENT_LOGIN).contentType("application/json")
                .content("{\"loginCode\":\"synthetic-code\"}")).andReturn().getResponse();
        assertEquals(200, response.getStatus()); assertEquals("no-store", response.getHeader("Cache-Control"));
        assertTrue(response.getContentAsString().contains("synthetic-member"));
        assertTrue(response.getContentAsString().contains("synthetic-access"));
        assertFalse(response.getContentAsString().contains("synthetic-code"));
        assertFalse(response.getContentAsString().contains("openid"));
        verifyNoInteractions(beans.oldConnect, beans.sessions, beans.authorization);
    }
    @ParameterizedTest @ValueSource(strings = {"openId", "memberId", "appId", "role", "storeId", "clerkId", "nickName", "loginTicket"})
    void unknownIdentityOrProfileFieldsRejectBeforeAuthentication(String field) throws Exception {
        var response = mvc.perform(post(SessionRoutes.RESIDENT_LOGIN).contentType("application/json")
                .content("{\"loginCode\":\"synthetic-private-code\",\"" + field + "\":\"foreign\"}")).andReturn().getResponse();
        assertEquals(400, response.getStatus()); assertFalse(response.getContentAsString().contains("synthetic-private-code"));
        verifyNoInteractions(beans.login, beans.oldConnect);
    }
    @ParameterizedTest @ValueSource(strings = {"phoneCode", "phoneNumberCode", "mobileCode", "mobile", "phone", "encryptedData", "iv"})
    void unsupportedPhoneRequestsAreExplicitlyRejected(String field) throws Exception {
        var response = mvc.perform(post(SessionRoutes.RESIDENT_LOGIN).contentType("application/json")
                .content("{\"loginCode\":\"synthetic-code\",\"" + field + "\":null}")).andReturn().getResponse();
        assertEquals(400, response.getStatus()); assertTrue(response.getContentAsString().contains("PHONE_NOT_SUPPORTED"));
        verifyNoInteractions(beans.login);
    }
    @Test void queryHeaderWrongMethodAndAnonymousProtectedReadsCannotBypass() throws Exception {
        for (String field : List.of("appId", "memberId", "storeId", "role", "openId")) {
            assertEquals(400, mvc.perform(post(SessionRoutes.RESIDENT_LOGIN).contentType("application/json")
                    .header(field, "foreign").content("{\"loginCode\":\"code\"}")).andReturn().getResponse().getStatus());
            assertEquals(400, mvc.perform(post(SessionRoutes.RESIDENT_LOGIN).contentType("application/json")
                    .param(field, "foreign").content("{\"loginCode\":\"code\"}")).andReturn().getResponse().getStatus());
        }
        assertEquals(403, mvc.perform(get(SessionRoutes.RESIDENT_LOGIN)).andReturn().getResponse().getStatus());
        assertEquals(403, mvc.perform(get("/buyer/passport/member")).andReturn().getResponse().getStatus());
        verifyNoInteractions(beans.login);
    }
    @ParameterizedTest @ValueSource(strings = {"{", "null", "[]", "{}", "{\"loginCode\":123}", "{\"loginCode\":null}"})
    void malformedBodiesAreSanitized(String body) throws Exception {
        assertEquals(400, mvc.perform(post(SessionRoutes.RESIDENT_LOGIN).contentType("application/json").content(body))
                .andReturn().getResponse().getStatus());
        verifyNoInteractions(beans.login);
    }
    @Test void dependencyFailureDoesNotReturnSessionOrPrivateCause() throws Exception {
        when(beans.login.login(anyString())).thenThrow(new ResidentLoginException(ResidentLoginException.Code.LOGIN_UNAVAILABLE));
        var response = mvc.perform(post(SessionRoutes.RESIDENT_LOGIN).contentType("application/json")
                .content("{\"loginCode\":\"synthetic-private-code\"}")).andReturn().getResponse();
        assertEquals(503, response.getStatus()); assertFalse(response.getContentAsString().contains("accessToken"));
        assertFalse(response.getContentAsString().contains("synthetic-private-code"));
    }
    @Test void oldAutoLoginStillUsesNativeServiceWithoutNewFlow() {
        var controller = new MiniProgramBuyerController(); controller.connectService = beans.oldConnect;
        var params = new WechatMPLoginParams(); params.setCode("synthetic-legacy-code");
        var token = new Token(); when(beans.oldConnect.miniProgramAutoLogin(params)).thenReturn(token);
        assertSame(token, controller.autoLogin("synthetic-uuid", params).getResult());
        verifyNoInteractions(beans.login);
    }
    @Test void reloginUsesOnlyFreshCodeEvenWithStaleStage0OrLegacyHeader() throws Exception {
        for (String stale : List.of("s0o1.synthetic-expired", "synthetic-expired-legacy")) {
            when(beans.login.login("fresh-code")).thenReturn(new ResidentWechatLoginService.LoginResult("current-resident", "RESIDENT",
                    new SessionTokens("synthetic-sid", "synthetic-access", "synthetic-refresh", 1L, 2L)));
            assertEquals(200, mvc.perform(post(SessionRoutes.RESIDENT_LOGIN).header("accessToken", stale)
                    .contentType("application/json").content("{\"loginCode\":\"fresh-code\"}"))
                    .andReturn().getResponse().getStatus());
        }
        verify(beans.login, times(2)).login("fresh-code");
        verifyNoInteractions(beans.authorization, beans.sessions);
    }
    @Configuration @EnableWebMvc
    static class Beans {
        final ResidentWechatLoginService login = mock(ResidentWechatLoginService.class);
        final ConnectService oldConnect = mock(ConnectService.class);
        final SessionService sessions = mock(SessionService.class);
        final CurrentSessionAuthorization authorization = mock(CurrentSessionAuthorization.class);
        @Bean ResidentWechatLoginController endpoint() { return new ResidentWechatLoginController(login); }
        @Bean SessionService sessions() { return sessions; }
        @Bean CurrentSessionAuthorization authorization() { return authorization; }
        @Bean SessionApplications apps() { return new SessionApplications(); }
        @Bean Cache<String> cache() { return mock(Cache.class); }
        @Bean IgnoredUrlsProperties ignored() { var p = new IgnoredUrlsProperties(); p.setUrls(new ArrayList<>()); return p; }
        @Bean CustomAccessDeniedHandler denied() { return new CustomAccessDeniedHandler(); }
        @Bean SecretKeyUtil keys() {
            byte[] raw = new byte[32]; new SecureRandom().nextBytes(raw);
            var p = new JWTTokenProperties(); p.setSecretKey(Base64.getEncoder().encodeToString(raw)); return new SecretKeyUtil(p);
        }
        @Bean CorsConfigurationSource corsConfigurationSource() { return new UrlBasedCorsConfigurationSource(); }
        @Bean AuthenticationProvider provider() { return new AuthenticationProvider() {
            public Authentication authenticate(Authentication a) { throw new AssertionError("Native password login not used"); }
            public boolean supports(Class<?> type) { return true; }
        }; }
    }
}
