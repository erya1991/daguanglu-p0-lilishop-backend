package cn.lili.security;

import cn.lili.common.security.AuthUser;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.session.*;
import cn.lili.common.security.session.http.SessionHttpFilter;
import cn.lili.controller.seller.member.StoreUserController;
import cn.lili.modules.member.merchant.MerchantAccessException;
import cn.lili.modules.member.merchant.MerchantAuthorizationService;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/** Controller data minimization; filter-chain tests cover the HTTP authentication boundary. */
class MerchantIdentityControllerTest {
    MerchantAuthorizationService grants;
    SessionIdentity identity;
    AuthUser user;
    MockMvc mvc;

    @BeforeEach void setup() {
        grants = mock(MerchantAuthorizationService.class);
        identity = new SessionIdentity("synthetic-member", SessionDomain.STORE, "synthetic-merchant", "synthetic-map", 1L);
        user = AuthUser.builder().id("synthetic-member").role(UserEnums.STORE).storeId("synthetic-store")
                .clerkId("synthetic-clerk").isSuper(true).build();
        mvc = MockMvcBuilders.standaloneSetup(new StoreUserController(grants)).build();
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); }

    @Test void noPrincipalOrOnlyLegacyUserNeverReadsProtectedData() throws Exception {
        assertEquals(403, mvc.perform(get("/store/member/user/info")).andReturn().getResponse().getStatus());
        var legacy = new UsernamePasswordAuthenticationToken("synthetic", null, List.of());
        legacy.setDetails(user); SecurityContextHolder.getContext().setAuthentication(legacy);
        assertEquals(403, mvc.perform(get("/store/member/user")).andReturn().getResponse().getStatus());
        verifyNoInteractions(grants);
    }

    @Test void ownerAndStaffReturnOnlySafeCurrentStoreFields() throws Exception {
        authenticate(identity);
        for (var role : List.of(MerchantAuthorizationService.Role.OWNER, MerchantAuthorizationService.Role.STAFF)) {
            when(grants.resolve(identity)).thenReturn(context(role, "synthetic-store"));
            var info = mvc.perform(get("/store/member/user/info")).andReturn().getResponse();
            assertEquals(200, info.getStatus());
            assertTrue(info.getContentAsString().contains(role.name()));
            assertTrue(info.getContentAsString().contains("synthetic-member"));
            var store = mvc.perform(get("/store/member/user")).andReturn().getResponse();
            assertEquals(200, store.getStatus());
            assertTrue(store.getContentAsString().contains("Synthetic Store"));
            for (String field : List.of("password", "mobile", "isSuper", "bankAccount", "identityCard", "license")) {
                assertFalse(info.getContentAsString().contains(field));
                assertFalse(store.getContentAsString().contains(field));
            }
            assertEquals("no-store", store.getHeader("Cache-Control"));
        }
    }

    @Test void currentRoleComesFromResolverRatherThanOldSuperFlag() throws Exception {
        authenticate(identity);
        when(grants.resolve(identity)).thenReturn(context(MerchantAuthorizationService.Role.STAFF, "synthetic-store"));
        var response = mvc.perform(get("/store/member/user/info")).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("STAFF"));
        assertFalse(response.getContentAsString().contains("OWNER"));
    }

    @Test void revokedProofAndDatabaseFailuresReturnNoContext() throws Exception {
        authenticate(identity);
        for (var code : MerchantAccessException.Code.values()) {
            doThrow(new MerchantAccessException(code)).when(grants).resolve(identity);
            var response = mvc.perform(get("/store/member/user/info")).andReturn().getResponse();
            assertTrue(response.getStatus() == 403 || response.getStatus() == 409 || response.getStatus() == 503);
            assertFalse(response.getContentAsString().contains("synthetic-member"));
            assertFalse(response.getContentAsString().contains("synthetic-store"));
        }
    }

    @Test void foreignStoreAndApplicantCannotBecomeCurrentStore() throws Exception {
        authenticate(identity);
        when(grants.resolve(identity)).thenReturn(context(MerchantAuthorizationService.Role.OWNER, "foreign-store"));
        assertEquals(403, mvc.perform(get("/store/member/user")).andReturn().getResponse().getStatus());
        var applicant = new SessionIdentity(identity.subject(), SessionDomain.APPLICANT, identity.application(), identity.bindingId(), 1L);
        authenticate(applicant);
        assertEquals(403, mvc.perform(get("/store/member/user/info")).andReturn().getResponse().getStatus());
    }

    void authenticate(SessionIdentity current) {
        var verified = mock(SessionService.VerifiedSession.class); when(verified.identity()).thenReturn(current);
        var auth = new UsernamePasswordAuthenticationToken(current.subject(), null, List.of());
        auth.setDetails(new SessionHttpFilter.Principal(verified, user)); SecurityContextHolder.getContext().setAuthentication(auth);
    }
    MerchantAuthorizationService.Context context(MerchantAuthorizationService.Role role, String store) {
        return new MerchantAuthorizationService.Context(SessionDomain.STORE, identity.subject(), user.getClerkId(), store,
                "Synthetic Store", role, user);
    }
}
