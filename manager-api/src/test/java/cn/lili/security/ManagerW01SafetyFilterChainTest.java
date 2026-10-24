package cn.lili.security;

import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.session.SessionDomain;
import cn.lili.common.security.session.SessionException;
import cn.lili.controller.manager.passport.AdminUserManagerController;
import cn.lili.controller.manager.permission.RoleMenuManagerController;
import cn.lili.controller.manager.permission.UserRoleManagerController;
import cn.lili.modules.member.service.MemberService;
import cn.lili.modules.permission.entity.vo.UserMenuVO;
import cn.lili.modules.permission.service.AdminUserService;
import cn.lili.modules.permission.service.RoleMenuService;
import cn.lili.modules.permission.service.UserRoleService;
import cn.lili.modules.verification.service.VerificationService;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Actual production chain and Controllers; synthetic repositories/cache, no runtime resources.
 * No synthetic grant in this test is an approved W01 maintenance whitelist.
 */
class ManagerW01SafetyFilterChainTest {
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    Beans beans;

    @BeforeEach void start() {
        context = new AnnotationConfigWebApplicationContext();
        context.setServletContext(new MockServletContext());
        context.register(Beans.class, ManagerStoreSecurityConfig.class, AdminUserManagerController.class,
                RoleMenuManagerController.class, UserRoleManagerController.class);
        // Reuse only the offline dependencies, not the older suite's synthetic handlers.
        context.addBeanFactoryPostProcessor(factory ->
                ((org.springframework.beans.factory.support.BeanDefinitionRegistry) factory)
                        .removeBeanDefinition("endpoints"));
        context.refresh();
        beans = context.getBean(Beans.class);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }
    @AfterEach void stop() { context.close(); SecurityContextHolder.clearContext(); }

    @Test void ordinaryManagerCannotInvokeAccountRoleOrMenuWritesEvenWithForgedSuperFlag() throws Exception {
        String legacy = beans.legacy(UserEnums.MANAGER, true); // Current DB is non-super.
        for (String path : List.of("/manager/passport/user/enable/synthetic-target",
                "/manager/passport/user/admin/edit", "/manager/permission/userRole/synthetic-target")) {
            assertEquals(403, mvc.perform(put(path).header("accessToken", legacy)
                    .param("isSuper", "true").param("roleId", "synthetic-high-role")
                    .param("status", "true")).andReturn().getResponse().getStatus());
        }
        assertEquals(403, mvc.perform(post("/manager/permission/roleMenu/synthetic-role")
                .header("accessToken", legacy)).andReturn().getResponse().getStatus());
        verify(beans.admins, never()).updateById(any());
        verify(beans.admins, never()).updateAdminUser(any(), any());
        verifyNoInteractions(beans.userRoles, beans.roleMenus);
    }

    @Test void anonymousAndForeignDomainsNeverReachActualManagerWriteControllers() throws Exception {
        assertEquals(403, mvc.perform(put("/manager/permission/userRole/synthetic-target"))
                .andReturn().getResponse().getStatus());
        for (SessionDomain domain : List.of(SessionDomain.RESIDENT, SessionDomain.APPLICANT, SessionDomain.STORE)) {
            assertEquals(403, mvc.perform(put("/manager/permission/userRole/synthetic-target")
                    .header("accessToken", beans.credential(domain))).andReturn().getResponse().getStatus());
        }
        verifyNoInteractions(beans.userRoles);
    }

    @Test void actualSelfControllerUsesVerifiedIdAndDoesNotMutateStoredPassword() throws Exception {
        beans.admin.setPassword("synthetic-hash-never-return");
        var response = mvc.perform(get("/manager/passport/user/info")
                .header("accessToken", beans.legacy(UserEnums.MANAGER, false))).andReturn().getResponse();
        assertEquals(200, response.getStatus());
        assertTrue(response.getContentAsString().contains("synthetic-actor"));
        assertFalse(response.getContentAsString().contains("synthetic-hash-never-return"));
        assertEquals("synthetic-hash-never-return", beans.admin.getPassword());
        verify(beans.admins, never()).findByUsername(anyString());
    }

    @Test void accountDisableOrDeleteWinsOverUnexpiredLegacyAccessAndCachedSuper() throws Exception {
        String legacy = beans.legacy(UserEnums.MANAGER, true);
        beans.admin.setStatus(false);
        assertEquals(403, mvc.perform(get("/manager/passport/user/info").header("accessToken", legacy))
                .andReturn().getResponse().getStatus());
        beans.admin.setStatus(true); beans.admin.setDeleteFlag(true);
        assertEquals(403, mvc.perform(get("/manager/passport/user/info").header("accessToken", legacy))
                .andReturn().getResponse().getStatus());
    }

    @Test void roleMenuRevocationIgnoresStaleLegacyCacheBeforeActualRoleRead() throws Exception {
        String route = "/manager/permission/roleMenu/synthetic-role";
        var query = new UserMenuVO(); query.setPermission(route); query.setIsSuper(false);
        when(beans.menus.findAllMenu("synthetic-actor")).thenReturn(List.of(query));
        when(beans.roleMenus.findByRoleId("synthetic-role")).thenReturn(List.of());
        String legacy = beans.legacy(UserEnums.MANAGER, true);
        assertEquals(200, mvc.perform(get(route).header("accessToken", legacy)).andReturn().getResponse().getStatus());
        when(beans.menus.findAllMenu("synthetic-actor")).thenReturn(List.of());
        assertEquals(403, mvc.perform(get(route).header("accessToken", legacy)).andReturn().getResponse().getStatus());
        verify(beans.roleMenus, times(1)).findByRoleId("synthetic-role");
    }

    @Test void missingCurrentAuthorizationDataRejectsBeforeController() throws Exception {
        String legacy = beans.legacy(UserEnums.MANAGER, true);
        when(beans.admins.getById("synthetic-actor")).thenThrow(new IllegalStateException("synthetic database unavailable"));
        assertEquals(403, mvc.perform(get("/manager/passport/user/info").header("accessToken", legacy))
                .andReturn().getResponse().getStatus());
    }

    @Test void managerSessionDoesNotImplicitlyGrantArbitraryPostOperations() {
        var auth = context.getBean(cn.lili.common.security.session.http.CurrentSessionAuthorization.class);
        var user = auth.legacy(cn.lili.common.security.AuthUser.builder()
                .id("synthetic-actor").role(UserEnums.MANAGER).build());
        assertThrows(SessionException.class, () -> auth.checkRoute(user, "POST",
                "/manager/permission/roleMenu/synthetic-role", true));
    }

    @Test void failedCaptchaNeverCallsPasswordLogin() throws Exception {
        var response = mvc.perform(post("/manager/passport/user/login").header("uuid", "synthetic-check")
                .param("username", "synthetic-user").param("password", "synthetic-password"))
                .andExpect(result -> {
                    assertInstanceOf(ServiceException.class, result.getResolvedException());
                    assertEquals(ResultCode.VERIFICATION_ERROR,
                            ((ServiceException) result.getResolvedException()).getResultCode());
                });
        verify(beans.admins, never()).login(anyString(), anyString());
    }

    @Configuration
    static class Beans extends ManagerSessionFilterChainTest.Beans {
        final UserRoleService userRoles = mock(UserRoleService.class);
        final RoleMenuService roleMenus = mock(RoleMenuService.class);
        @Bean AdminUserService adminService() { return admins; }
        @Bean MemberService memberService() { return members; }
        @Bean UserRoleService userRoles() { return userRoles; }
        @Bean RoleMenuService roleMenus() { return roleMenus; }
        @Bean VerificationService verification() { return mock(VerificationService.class); }
        @Bean cn.lili.common.exception.GlobalControllerExceptionHandler errors() {
            return new cn.lili.common.exception.GlobalControllerExceptionHandler();
        }
    }
}
