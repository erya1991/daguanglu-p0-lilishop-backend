package cn.lili.security;

import cn.lili.cache.Cache;
import cn.lili.common.properties.*;
import cn.lili.common.security.*;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.session.*;
import cn.lili.common.security.session.http.*;
import cn.lili.common.security.token.SecretKeyUtil;
import cn.lili.controller.passport.*;
import cn.lili.modules.connect.wechat.MemberLinkService;
import cn.lili.modules.member.profile.*;
import jakarta.servlet.Filter;
import jakarta.servlet.http.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.*;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.AbstractMockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.cors.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import java.security.SecureRandom;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Actual BuyerSecurityConfig, filters, strict body parser and existing Member controller delegation. */
class ResidentProfileFilterChainTest {
    AnnotationConfigWebApplicationContext context;MockMvc mvc;Beans beans;
    @BeforeEach void start(){context=new AnnotationConfigWebApplicationContext();context.setServletContext(new MockServletContext());context.register(Beans.class,BuyerSecurityConfig.class);context.refresh();beans=context.getBean(Beans.class);mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(context.getBean("springSecurityFilterChain",Filter.class)).build();}
    @AfterEach void close(){context.close();SecurityContextHolder.clearContext();}
    AbstractMockHttpServletRequestBuilder<?> request(String path) {
        if(path.equals("/buyer/passport/member"))return get(path);
        if(path.equals(SessionRoutes.RESIDENT_EDIT))return put(path).contentType("application/json").content("{\"nickname\":\"住户\",\"version\":0}");
        if(path.equals(SessionRoutes.RESIDENT_AVATAR))return multipart(path).file(new MockMultipartFile("file","x.png","image/png",new byte[]{1}));
        if(path.equals(SessionRoutes.RESIDENT_PHONE))return post(path).contentType("application/json").content("{\"phoneCode\":\"synthetic-phone-code\",\"requestId\":\"request-phone-0001\",\"version\":0}");
        return post(path).contentType("application/json").content("{\"requestId\":\"link-request-0001\",\"proofReference\":\"synthetic-proof-ref\",\"consentVersion\":\"member-link-v1\",\"confirmed\":true}");
    }
    static List<String> paths(){return List.of("/buyer/passport/member",SessionRoutes.RESIDENT_EDIT,SessionRoutes.RESIDENT_PHONE,SessionRoutes.RESIDENT_AVATAR,SessionRoutes.RESIDENT_LINK);}
    @Test void legitimateResidentReachesFiveExactCapabilities()throws Exception{for(String path:paths())assertEquals(200,mvc.perform(request(path).header("accessToken","s0o1.RESIDENT")).andReturn().getResponse().getStatus(),path);verify(beans.profiles).read(any());verify(beans.profiles).edit(any(),eq(0L),eq("住户"),isNull());verify(beans.avatars).upload(any(),any());verify(beans.links).link(any(),anyString(),anyString(),eq("member-link-v1"),eq(true));}
    @ParameterizedTest @ValueSource(strings={"APPLICANT","STORE","MANAGER","expired","tampered","refresh"})
    void otherDomainsAndInvalidCredentialsNeverReachBusinessHandlers(String source)throws Exception{for(String path:paths())assertEquals(403,mvc.perform(request(path).header("accessToken","s0o1."+source)).andReturn().getResponse().getStatus());verifyNoInteractions(beans.profiles,beans.avatars,beans.links);}
    @Test void anonymousAndLegacyCannotDowngradeNewWritesEvenWithBroadIgnoredUrls()throws Exception{for(String path:paths()){assertEquals(403,mvc.perform(request(path)).andReturn().getResponse().getStatus());assertEquals(403,mvc.perform(request(path).header("accessToken","legacy-invalid")).andReturn().getResponse().getStatus());}verifyNoInteractions(beans.profiles,beans.avatars,beans.links);}
    @Test void wrongMethodsAndStageOneWritesStopBeforeHandlers()throws Exception{for(String path:paths())assertEquals(403,mvc.perform(delete(path).header("accessToken","s0o1.RESIDENT")).andReturn().getResponse().getStatus());assertEquals(403,mvc.perform(post("/buyer/store/store/apply/first").header("accessToken","s0o1.RESIDENT")).andReturn().getResponse().getStatus());assertEquals(0,beans.forbiddenWrites);}
    @ParameterizedTest @ValueSource(strings={"memberId","username","mobile","password","gradeId","role","status","storeId"})
    void excessEditFieldsAreRejected(String field)throws Exception{var response=mvc.perform(put(SessionRoutes.RESIDENT_EDIT).header("accessToken","s0o1.RESIDENT").contentType("application/json").content("{\"nickname\":\"住户\",\"version\":0,\""+field+"\":\"other\"}")).andReturn().getResponse();assertEquals(400,response.getStatus());verifyNoInteractions(beans.profiles);}
    @Test void cannotChooseAnotherMemberByQueryOrHeader()throws Exception{assertEquals(403,mvc.perform(get("/buyer/passport/member").param("memberId","456").header("accessToken","s0o1.RESIDENT")).andReturn().getResponse().getStatus());assertEquals(400,mvc.perform(get("/buyer/passport/member").param("username","other").header("accessToken","s0o1.RESIDENT")).andReturn().getResponse().getStatus());assertEquals(400,mvc.perform(get("/buyer/passport/member").header("memberId","456").header("accessToken","s0o1.RESIDENT")).andReturn().getResponse().getStatus());verifyNoInteractions(beans.profiles);}
    @Test void loginCodeAndClientNumberAreNotPhoneAuthorization()throws Exception{for(String field:List.of("loginCode","mobile"))assertEquals(400,mvc.perform(post(SessionRoutes.RESIDENT_PHONE).header("accessToken","s0o1.RESIDENT").contentType("application/json").content("{\""+field+"\":\"private\",\"version\":0,\"requestId\":\"request-phone-0001\"}")).andReturn().getResponse().getStatus());verifyNoInteractions(beans.profiles);}
    @Test void safeErrorResponseNeverEchoesPhoneProofOrBody()throws Exception{when(beans.profiles.phone(any(),anyLong(),anyString(),anyString())).thenThrow(new ResidentProfileException(ResidentProfileException.Code.LINK_REQUIRED));var r=mvc.perform(request(SessionRoutes.RESIDENT_PHONE).header("accessToken","s0o1.RESIDENT")).andReturn().getResponse();assertEquals(409,r.getStatus());assertTrue(r.getContentAsString().contains("LINK_REQUIRED"));assertFalse(r.getContentAsString().contains("synthetic-phone-code"));assertFalse(r.getContentAsString().contains("456"));assertEquals("no-store",r.getHeader("Cache-Control"));}
    @Test void minimalReadNeverSerializesMemberInternalFields()throws Exception{var r=mvc.perform(request("/buyer/passport/member").header("accessToken","s0o1.RESIDENT")).andReturn().getResponse();assertEquals(200,r.getStatus());assertFalse(r.getContentAsString().contains("password"));assertFalse(r.getContentAsString().contains("username"));assertTrue(r.getContentAsString().contains("phoneBound"));}
    @Test void multipartExtraFilesAndOwnerFieldsRejectThroughSecurityWrappers()throws Exception{
        assertEquals(400,mvc.perform(multipart(SessionRoutes.RESIDENT_AVATAR).file(new MockMultipartFile("file",new byte[]{1})).file(new MockMultipartFile("other",new byte[]{1})).header("accessToken","s0o1.RESIDENT")).andReturn().getResponse().getStatus());
        assertEquals(400,mvc.perform(multipart(SessionRoutes.RESIDENT_AVATAR).file(new MockMultipartFile("file",new byte[]{1})).param("ownerId","456").header("accessToken","s0o1.RESIDENT")).andReturn().getResponse().getStatus());
        verifyNoInteractions(beans.avatars);
    }
    @Configuration @EnableWebMvc static class Beans {
        final ResidentProfileService profiles=mock(ResidentProfileService.class);final ResidentAvatarService avatars=mock(ResidentAvatarService.class);final MemberLinkService links=mock(MemberLinkService.class);
        final SessionService sessions=mock(SessionService.class);final CurrentSessionAuthorization authorization=mock(CurrentSessionAuthorization.class);int forbiddenWrites;
        @Bean ResidentProfileController endpoints(){return new ResidentProfileController(profiles,avatars,links);}
        @Bean ExistingEndpoints existing(ResidentProfileController endpoint){var member=new MemberBuyerController();ReflectionTestUtils.setField(member,"residentProfiles",endpoint);return new ExistingEndpoints(member,this);}
        @Bean SessionApplications apps(){var p=new SessionApplications();p.setResidentApplication("synthetic-r");p.setMerchantApplication("synthetic-m");p.setManagerApplication("synthetic-manager");return p;}
        @Bean SessionService sessions(){when(sessions.verifyAccess(anyString(),eq(SessionDomain.RESIDENT),eq("synthetic-r"))).thenAnswer(i->{if(!"s0o1.RESIDENT".equals(i.getArgument(0)))throw new SessionException(SessionException.Code.INVALID_CREDENTIAL);var v=mock(SessionService.VerifiedSession.class);when(v.identity()).thenReturn(new SessionIdentity("123",SessionDomain.RESIDENT,"synthetic-r","789",1L));when(v.sid()).thenReturn("a".repeat(32));return v;});return sessions;}
        @Bean CurrentSessionAuthorization authorization(){when(authorization.current(any())).thenReturn(AuthUser.builder().id("123").username("synthetic-user").role(UserEnums.MEMBER).build());when(profiles.read(any())).thenReturn(new ResidentProfileService.Profile("123","住户",null,null,false,0,null));return authorization;}
        @Bean Cache<String> cache(){return mock(Cache.class);}
        @Bean IgnoredUrlsProperties ignored(){var p=new IgnoredUrlsProperties();p.setUrls(new ArrayList<>(List.of("/buyer/**")));return p;}
        @Bean CustomAccessDeniedHandler denied(){return new CustomAccessDeniedHandler();}
        @Bean SecretKeyUtil keys(){byte[] raw=new byte[32];new SecureRandom().nextBytes(raw);var p=new JWTTokenProperties();p.setSecretKey(Base64.getEncoder().encodeToString(raw));return new SecretKeyUtil(p);}
        @Bean CorsConfigurationSource corsConfigurationSource(){return new UrlBasedCorsConfigurationSource();}
        @Bean AuthenticationProvider provider(){return new AuthenticationProvider(){public Authentication authenticate(Authentication a){throw new AssertionError("No native login here");}public boolean supports(Class<?> t){return true;}};}
    }
    @RestController static class ExistingEndpoints {
        final MemberBuyerController member;final Beans beans;ExistingEndpoints(MemberBuyerController member,Beans beans){this.member=member;this.beans=beans;}
        @GetMapping("/buyer/passport/member") void read(HttpServletRequest r,HttpServletResponse s){member.getUserInfo(r,s);}
        @PutMapping(SessionRoutes.RESIDENT_EDIT) void edit(HttpServletRequest r,HttpServletResponse s){member.editOwn(r,s);}
        @PostMapping("/buyer/store/store/apply/first") void forbidden(){beans.forbiddenWrites++;throw new AssertionError("Business handler must never execute");}
    }
}
