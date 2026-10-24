package cn.lili.security;

import cn.lili.common.security.session.*;
import cn.lili.common.security.session.http.SessionRoutes;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.controller.seller.passport.MerchantWechatLoginController;
import cn.lili.modules.connect.wechat.MerchantWechatLoginService;
import cn.lili.modules.connect.wechat.ResidentLoginException;
import cn.lili.modules.member.merchant.MerchantAccessException;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** P05 routes pass through the actual Manager/Store filter chains with isolated resource substitutes. */
class MerchantSessionFilterChainTest {
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    ManagerSessionFilterChainTest.Beans beans;
    MerchantWechatLoginService login;

    @BeforeEach void start() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(ManagerSessionFilterChainTest.Beans.class, ManagerStoreSecurityConfig.class,
                MerchantWechatLoginController.class, LoginBeans.class);
        context.refresh();
        beans = context.getBean(ManagerSessionFilterChainTest.Beans.class);
        login = context.getBean(MerchantWechatLoginService.class);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }
    @AfterEach void stop() { context.close(); SecurityContextHolder.clearContext(); }

    @Test void exactLoginPostWorksWithSyntheticGatewayResultAndIgnoresStaleAccessHeader() throws Exception {
        var session = new SessionTokens("synthetic-sid", "synthetic-access", "synthetic-refresh", 1, 2);
        when(login.login("synthetic-code")).thenReturn(new MerchantWechatLoginService.LoginResult("synthetic-actor", "APPLICANT", null, session));
        var response = mvc.perform(post(SessionRoutes.MERCHANT_LOGIN).contentType("application/json")
                .header("accessToken", "stale-untrusted-access").content("{\"loginCode\":\"synthetic-code\"}"))
                .andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("APPLICANT"));
        assertEquals("no-store", response.getHeader("Cache-Control"));
        verify(login).login("synthetic-code");
        verifyNoInteractions(beans.sessions);
    }

    @Test void onlyCodeAllowedAndInputIsNeverEchoed() throws Exception {
        for (String selector : List.of("appId", "openId", "memberId", "storeId", "clerkId", "role", "isSuper", "phoneCode")) {
            var response = mvc.perform(post(SessionRoutes.MERCHANT_LOGIN).contentType("application/json")
                    .content("{\"loginCode\":\"synthetic-secret-code\",\"" + selector + "\":\"spoofed\"}"))
                    .andReturn().getResponse();
            assertEquals(400, response.getStatus());
            assertFalse(response.getContentAsString().contains("synthetic-secret-code"));
            assertFalse(response.getContentAsString().contains("spoofed"));
        }
        verifyNoInteractions(login);
    }

    @Test void loginCannotSelectIdentityThroughQueryOrHeaderOrWrongVerb() throws Exception {
        assertEquals(400, mvc.perform(post(SessionRoutes.MERCHANT_LOGIN).contentType("application/json")
                .param("storeId", "foreign").content("{\"loginCode\":\"synthetic\"}")).andReturn().getResponse().getStatus());
        assertEquals(400, mvc.perform(post(SessionRoutes.MERCHANT_LOGIN).contentType("application/json")
                .header("role", "OWNER").content("{\"loginCode\":\"synthetic\"}")).andReturn().getResponse().getStatus());
        assertNotEquals(200, mvc.perform(get(SessionRoutes.MERCHANT_LOGIN)).andReturn().getResponse().getStatus());
        verifyNoInteractions(login);
    }

    @Test void configuredFailuresAreSafeAndDoNotReturnSessionOrOldAccountDetails() throws Exception {
        for (var code : List.of(ResidentLoginException.Code.WECHAT_REJECTED, ResidentLoginException.Code.B04_PENDING,
                ResidentLoginException.Code.CONFIG_REQUIRED, ResidentLoginException.Code.LOGIN_UNAVAILABLE)) {
            doThrow(new ResidentLoginException(code)).when(login).login("synthetic-code");
            var response = mvc.perform(post(SessionRoutes.MERCHANT_LOGIN).contentType("application/json")
                    .content("{\"loginCode\":\"synthetic-code\"}")).andReturn().getResponse();
            assertEquals(code == ResidentLoginException.Code.WECHAT_REJECTED ? 403 : 503, response.getStatus());
            assertTrue(response.getContentAsString().contains(code.name()));
            assertFalse(response.getContentAsString().contains("refreshToken"));
            assertFalse(response.getContentAsString().contains("memberId"));
        }
    }

    @Test void legacyJwtCannotDowngradeIntoMerchantIdentityReads() throws Exception {
        for (String endpoint : List.of("/store/member/user", "/store/member/user/info")) {
            assertEquals(403, mvc.perform(get(endpoint).header("accessToken", beans.legacy(UserEnums.STORE, true)))
                    .andReturn().getResponse().getStatus());
            assertEquals(403, mvc.perform(get(endpoint)).andReturn().getResponse().getStatus());
        }
        assertEquals(0, beans.calls.get());
        verifyNoInteractions(beans.sessions);
    }

    @Test void missingProofAndFailedRelationsAreNotDisguisedAsApplicantOrBadInput() throws Exception {
        for (var code : MerchantAccessException.Code.values()) {
            doThrow(new MerchantAccessException(code)).when(login).login("synthetic-code");
            var response = mvc.perform(post(SessionRoutes.MERCHANT_LOGIN).contentType("application/json")
                    .content("{\"loginCode\":\"synthetic-code\"}")).andReturn().getResponse();
            int expected = switch (code) {
                case DATA_UNAVAILABLE, PROOF_SOURCE_PENDING -> 503;
                case RELATION_CONFLICT -> 409;
                case DENIED -> 403;
            };
            assertEquals(expected, response.getStatus());
            assertTrue(response.getContentAsString().contains(code.name()));
            assertFalse(response.getContentAsString().contains("APPLICANT"));
            assertFalse(response.getContentAsString().contains("refreshToken"));
        }
    }

    @Test void allCrossDomainsAndSpoofedHeadersAreRejectedBeforeHandlers() throws Exception {
        for (String endpoint : List.of("/store/member/user/info", SessionRoutes.APPLICANT + "/identity")) {
            var allowed = endpoint.contains("applicant") ? SessionDomain.APPLICANT : SessionDomain.STORE;
            for (var source : SessionDomain.values()) {
                if (source == allowed) continue;
                assertEquals(403, mvc.perform(get(endpoint).header("accessToken", beans.credential(source)))
                        .andReturn().getResponse().getStatus());
            }
            for (String header : List.of("appId", "memberId", "storeId", "clerkId", "role", "isSuper")) {
                assertEquals(403, mvc.perform(get(endpoint).header("accessToken", beans.credential(allowed)).header(header, "foreign"))
                        .andReturn().getResponse().getStatus());
            }
        }
        assertEquals(0, beans.calls.get());
    }

    @Test void applicantNeverReachesBusinessHandlerOrCreatesStoreAndClerk() throws Exception {
        String token = beans.credential(SessionDomain.APPLICANT);
        var identity = mvc.perform(get(SessionRoutes.APPLICANT + "/identity").header("accessToken", token)).andReturn().getResponse();
        assertEquals(200, identity.getStatus());
        assertTrue(identity.getContentAsString().contains("SHOW_APPLICATION_ENTRY"));
        assertEquals(403, mvc.perform(post("/store/goods/goods").header("accessToken", token)).andReturn().getResponse().getStatus());
        assertEquals(0, beans.calls.get());
        verify(beans.clerks, never()).save(any());
        verify(beans.stores, never()).save(any());
    }

    @Configuration static class LoginBeans {
        @Bean MerchantWechatLoginService merchantLogin() { return mock(MerchantWechatLoginService.class); }
    }
}
