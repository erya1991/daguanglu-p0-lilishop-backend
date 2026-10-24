package cn.lili.common.security.session;

import cn.lili.cache.*;
import cn.lili.common.exception.GlobalControllerExceptionHandler;
import cn.lili.common.properties.*;
import cn.lili.common.security.*;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.session.http.*;
import cn.lili.common.security.token.*;
import cn.lili.controller.manager.passport.*;
import cn.lili.controller.manager.permission.*;
import cn.lili.modules.member.service.*;
import cn.lili.modules.member.token.StoreTokenGenerate;
import cn.lili.modules.permission.entity.dos.*;
import cn.lili.modules.permission.entity.dos.Role;
import cn.lili.modules.permission.manager.*;
import cn.lili.modules.permission.mapper.*;
import cn.lili.modules.permission.service.*;
import cn.lili.modules.permission.serviceimpl.AdminUserServiceImpl;
import cn.lili.modules.store.service.StoreService;
import cn.lili.modules.system.token.ManagerTokenGenerate;
import cn.lili.modules.verification.service.VerificationService;
import cn.lili.security.ManagerStoreSecurityConfig;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.google.gson.*;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.authentication.*;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.cors.*;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** Production MVC/security, BCrypt, W01 policy/maintenance and P02 core.
 * Only persistence/cache and CAPTCHA results are explicit offline doubles.
 * Synthetic IDs and in-memory writes are not real W01 database acceptance.
 */
@SuppressWarnings("unchecked")
class ManagerW01SessionMvcTest {
    AnnotationConfigWebApplicationContext context;
    MockMvc mvc;
    Beans b;
    @BeforeEach void start() {
        context = new AnnotationConfigWebApplicationContext(); context.setServletContext(new MockServletContext());
        context.register(Beans.class, ManagerStoreSecurityConfig.class, AdminUserManagerController.class,
                RoleManagerController.class, UserRoleManagerController.class, RoleMenuManagerController.class,
                MenuManagerController.class, ManagerWebSupport.class, ManagerSessionLogin.class);
        context.refresh(); b = context.getBean(Beans.class);
        mvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(context.getBean("springSecurityFilterChain", Filter.class)).build();
    }
    @AfterEach void stop() { context.close(); SecurityContextHolder.clearContext(); }

    JsonObject request(MockHttpServletRequestBuilder request) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertFalse(response.getContentAsString().isBlank());
        return JsonParser.parseString(response.getContentAsString()).getAsJsonObject();
    }
    JsonObject login(String user) throws Exception {
        var response = request(post("/manager/passport/user/login").header("uuid", "synthetic-verification")
                .header("X-Manager-Session-Protocol", "STAGE0_SESSION")
                .param("username", user).param("password", "synthetic-input"));
        assertTrue(response.get("success").getAsBoolean());
        var result = response.getAsJsonObject("result");
        assertEquals("STAGE0_SESSION", result.get("sessionProtocol").getAsString());
        return result;
    }
    String access(JsonObject login) { return login.get("accessToken").getAsString(); }
    String refresh(JsonObject login) { return login.get("refreshToken").getAsString(); }
    JsonObject as(String token, MockHttpServletRequestBuilder request) throws Exception {
        return request(request.header("accessToken", token));
    }
    void denied(JsonObject response) { assertFalse(response.get("success").getAsBoolean()); }

    @Test void aAndBLoginUseOnlyRealP02CoreAndReturnCurrentSelfCapabilities() throws Exception {
        for (String id : List.of("a-user", "b-user")) {
            var token = login(id);
            var claims = b.keys.parseClaims(access(token));
            assertEquals("MANAGER", claims.get("domain")); assertEquals("ACCESS", claims.get("use"));
            assertEquals("synthetic-manager-app", claims.get("app"));
            assertFalse(claims.containsKey("bid")); assertFalse(claims.containsKey("bv"));
            var info = as(access(token), get("/manager/passport/user/info"));
            assertEquals(id, info.getAsJsonObject("result").get("id").getAsString());
            assertEquals(id.startsWith("a") ? "A" : "B", info.getAsJsonObject("result")
                    .getAsJsonObject("stage0").get("role").getAsString());
            assertFalse(info.toString().contains(b.accounts.get(id).getPassword()));
            assertTrue(as(access(token), get("/manager/permission/menu/memberMenu")).get("success").getAsBoolean());
        }
        verifyNoInteractions(b.nativeTokens);
    }
    @Test void badCaptchaPasswordOrDisabledAccountCannotCreateAnySession() throws Exception {
        when(b.verification.check(anyString(), any())).thenReturn(false);
        denied(request(post("/manager/passport/user/login").header("uuid", "synthetic")
                .param("username", "a-user").param("password", "synthetic-input")));
        when(b.verification.check(anyString(), any())).thenReturn(true);
        denied(request(post("/manager/passport/user/login").header("uuid", "synthetic")
                .param("username", "a-user").param("password", "wrong-input")));
        b.accounts.get("a-user").setStatus(false);
        denied(request(post("/manager/passport/user/login").header("uuid", "synthetic")
                .param("username", "a-user").param("password", "synthetic-input")));
        assertTrue(b.store.rows.isEmpty()); verifyNoInteractions(b.nativeTokens);
    }
    @Test void missingRoleConfigurationOrApplicationCannotFallbackToLegacyIssuance() throws Exception {
        b.settings.setRoleBIds(Set.of());
        denied(request(post("/manager/passport/user/login").header("uuid", "synthetic")
                .header("X-Manager-Session-Protocol", "STAGE0_SESSION")
                .param("username", "a-user").param("password", "synthetic-input")));
        b.settings.setRoleBIds(Set.of("role-b")); b.apps.setManagerApplication(null);
        denied(request(post("/manager/passport/user/login").header("uuid", "synthetic")
                .header("X-Manager-Session-Protocol", "STAGE0_SESSION")
                .param("username", "a-user").param("password", "synthetic-input")));
        b.settings.setEnabled(false);
        denied(request(post("/manager/passport/user/login").header("uuid", "synthetic")
                .header("X-Manager-Session-Protocol", "STAGE0_SESSION")
                .param("username", "a-user").param("password", "synthetic-input")));
        assertTrue(b.store.rows.isEmpty()); verifyNoInteractions(b.nativeTokens);
    }
    @Test void aCannotReadMaintenanceOrInvokeAnyOfTheThreeWrites() throws Exception {
        String token = access(login("a-user"));
        denied(as(token, get("/manager/passport/user/getByCondition")));
        denied(as(token, put("/manager/passport/user/enable/target-a").param("status", "false")));
        denied(as(token, put("/manager/permission/userRole/target-a").contentType("application/json").content("{\"roleIds\":[]}")));
        denied(as(token, post("/manager/permission/roleMenu/role-a").contentType("application/json").content("{\"menuIds\":[]}")));
        assertEquals(0, b.writes.get());
    }
    @Test void loginRejectsClientSelectedIdentityWithoutIssuingAnything() throws Exception {
        denied(request(post("/manager/passport/user/login").header("uuid", "synthetic")
                .header("X-Manager-Session-Protocol", "STAGE0_SESSION")
                .param("username", "a-user").param("password", "synthetic-input").param("isSuper", "true")));
        denied(request(post("/manager/passport/user/login").header("uuid", "synthetic")
                .header("X-Manager-Session-Protocol", "STAGE0_SESSION")
                .header("appId", "untrusted-app").param("username", "a-user").param("password", "synthetic-input")));
        assertTrue(b.store.rows.isEmpty()); verifyNoInteractions(b.nativeTokens);
    }
    @Test void bReadsOnlyConfiguredAccountsAndActualUserRoleRelations() throws Exception {
        String token = access(login("b-user"));
        var users = as(token, get("/manager/passport/user/getByCondition").param("username", "b-user"));
        var records = users.getAsJsonObject("result").getAsJsonArray("records");
        assertEquals(1, records.size()); assertEquals("target-a", records.get(0).getAsJsonObject().get("id").getAsString());
        var rows = as(token, get("/manager/permission/userRole/target-a")).getAsJsonArray("result");
        assertEquals("target-a", rows.get(0).getAsJsonObject().get("userId").getAsString());
        assertEquals("role-a", rows.get(0).getAsJsonObject().get("roleId").getAsString());
        denied(as(token, get("/manager/permission/userRole/outside")));
        verify(b.userRoleService, never()).getById(any());
    }
    @Test void bCanDisableAndReenableOnlyTheApprovedOrdinaryTarget() throws Exception {
        String token = access(login("b-user"));
        assertTrue(as(token, put("/manager/passport/user/enable/target-a").param("status", "false")).get("success").getAsBoolean());
        assertFalse(b.accounts.get("target-a").getStatus());
        assertTrue(as(token, put("/manager/passport/user/enable/target-a").param("status", "true")).get("success").getAsBoolean());
        assertTrue(b.accounts.get("target-a").getStatus()); assertEquals(2, b.writes.get());
    }
    @Test void selfBOrSuperTargetsAndExtraStatusFieldsRejectBeforeWrite() throws Exception {
        String token = access(login("b-user"));
        for (String id : List.of("b-user", "outside")) denied(as(token, put("/manager/passport/user/enable/" + id).param("status", "false")));
        b.accounts.get("target-a").setIsSuper(true);
        denied(as(token, put("/manager/passport/user/enable/target-a").param("status", "false")));
        b.accounts.get("target-a").setIsSuper(false); b.direct.add(new UserRole("target-a", "role-b"));
        denied(as(token, put("/manager/passport/user/enable/target-a").param("status", "false")));
        b.direct.removeLast();
        denied(as(token, put("/manager/passport/user/enable/target-a").param("status", "false").param("password", "untrusted")));
        assertEquals(0, b.writes.get());
    }
    @Test void bCanRevokeAndRestoreOnlyTheTargetsDirectARole() throws Exception {
        String token = access(login("b-user"));
        assertTrue(as(token, put("/manager/permission/userRole/target-a").contentType("application/json").content("{\"roleIds\":[]}"))
                .get("success").getAsBoolean());
        assertFalse(b.direct.stream().anyMatch(row -> row.getUserId().equals("target-a")));
        assertTrue(as(token, put("/manager/permission/userRole/target-a").contentType("application/json").content("{\"roleIds\":[\"role-a\"]}"))
                .get("success").getAsBoolean());
        assertEquals(1, b.direct.stream().filter(row -> row.getUserId().equals("target-a")).count());
        assertTrue(as(token, put("/manager/permission/userRole/target-a").contentType("application/json").content("{\"roleIds\":[\"role-a\"]}"))
                .get("success").getAsBoolean());
        assertEquals(1, b.direct.stream().filter(row -> row.getUserId().equals("target-a")).count());
    }
    @Test void bRoleSelfAssignmentAndPayloadIdentitySubstitutionReject() throws Exception {
        String token = access(login("b-user"));
        for (String body : List.of("{\"roleIds\":[\"role-b\"]}", "{\"roleIds\":[\"role-a\"],\"userId\":\"b-user\"}",
                "{\"roleIds\":[\"role-a\",\"role-a\"]}")) {
            denied(as(token, put("/manager/permission/userRole/target-a").contentType("application/json").content(body)));
        }
        denied(as(token, put("/manager/permission/userRole/b-user").contentType("application/json").content("{\"roleIds\":[]}")));
        assertEquals(0, b.writes.get());
    }
    @Test void bOnlyChangesApprovedAReadMenuRelationsAndCannotExpandFlagsOrRole() throws Exception {
        String token = access(login("b-user"));
        assertTrue(as(token, post("/manager/permission/roleMenu/role-a").contentType("application/json")
                .content("{\"menuIds\":[\"read-list\"]}")).get("success").getAsBoolean());
        denied(as(token, post("/manager/permission/roleMenu/role-a").contentType("application/json").content("{\"menuIds\":[\"status\"]}")));
        denied(as(token, post("/manager/permission/roleMenu/role-b").contentType("application/json").content("{\"menuIds\":[]}")));
        denied(as(token, post("/manager/permission/roleMenu/role-a").contentType("application/json")
                .content("{\"menuIds\":[\"read-list\"],\"isSuper\":true}")));
        assertEquals(1, b.writes.get());
    }
    @Test void legacyAliasesAndOldJwtCannotBypassTheNewWriteContract() throws Exception {
        String token = access(login("b-user"));
        for (var route : List.of(put("/manager/passport/user/admin/edit"), post("/manager/passport/user/register"),
                post("/manager/passport/user/resetPassword/target-a"), delete("/manager/passport/user/target-a"),
                put("/manager/permission/menu/read-list"), post("/manager/permission/role"),
                put("/manager/permission/departmentRole/synthetic"))) denied(as(token, route));
        denied(as(b.legacy(), put("/manager/passport/user/enable/target-a").param("status", "false")));
        assertEquals(0, b.writes.get());
    }
    @Test void currentDisableRoleAndMenuRevocationWinsWithoutClearingStaleCache() throws Exception {
        String token = access(login("b-user"));
        b.grants.removeIf(row -> row.getRoleId().equals("role-b") && row.getMenuId().equals("status"));
        denied(as(token, put("/manager/passport/user/enable/target-a").param("status", "false")));
        b.direct.removeIf(row -> row.getUserId().equals("b-user"));
        denied(as(token, get("/manager/passport/user/info")));
        b.direct.add(new UserRole("b-user", "role-b")); b.accounts.get("b-user").setStatus(false);
        denied(as(token, get("/manager/passport/user/info"))); assertEquals(0, b.writes.get());
    }
    @Test void configuredBLegacyJwtCannotReachUnscopedListOrForeignReadControllers() throws Exception {
        String legacy = b.legacy();
        for (String path : List.of("/manager/passport/user/getByCondition", "/manager/permission/role",
                "/manager/permission/menu/tree", "/manager/permission/userRole/outside",
                "/manager/permission/roleMenu/role-b", "/manager/passport/user/info"))
            denied(as(legacy, get(path)));
        verify(b.adminService, never()).adminUserPage(any(cn.lili.modules.permission.entity.dto.AdminUserDTO.class), any(), any());
        assertEquals(0, b.writes.get());
    }
    @Test void historicalSuperKeepsItsLegacySelfReadWithoutAcquiringANewABSession() throws Exception {
        b.accounts.get("b-user").setIsSuper(true);
        assertTrue(as(b.legacy(), get("/manager/passport/user/info")).get("success").getAsBoolean());
        denied(request(post("/manager/passport/user/login").header("uuid", "synthetic")
                .header("X-Manager-Session-Protocol", "STAGE0_SESSION")
                .param("username", "b-user").param("password", "synthetic-input")));
    }
    @Test void historicalSuperUnrequestedLoginStaysLegacyButABMustRequestNewProtocol() throws Exception {
        denied(request(post("/manager/passport/user/login").header("uuid", "synthetic")
                .param("username", "a-user").param("password", "synthetic-input")));
        verifyNoInteractions(b.nativeTokens);
        b.accounts.get("b-user").setIsSuper(true);
        var pair = new Token(); pair.setAccessToken("synthetic-legacy-access"); pair.setRefreshToken("synthetic-legacy-refresh");
        when(b.nativeTokens.createToken(any())).thenReturn(pair);
        assertTrue(request(post("/manager/passport/user/login").header("uuid", "synthetic")
                .param("username", "b-user").param("password", "synthetic-input")).get("success").getAsBoolean());
        assertTrue(b.store.rows.isEmpty()); verify(b.nativeTokens, times(1)).createToken(any());
    }
    @Test void realManagerRefreshRotatesOnceLogoutRevokesCurrentSidAndOtherSidSurvives() throws Exception {
        var first = login("a-user"); var second = login("a-user");
        var next = request(post("/manager/passport/user/refresh").contentType("application/json")
                .content(new Gson().toJson(Map.of("refreshToken", refresh(first))))).getAsJsonObject("result");
        assertEquals("STAGE0_SESSION", next.get("sessionProtocol").getAsString());
        denied(as(access(first), get("/manager/passport/user/info")));
        denied(request(post("/manager/passport/user/refresh").contentType("application/json")
                .content(new Gson().toJson(Map.of("refreshToken", refresh(first))))));
        assertTrue(as(access(next), post("/manager/passport/user/logout")).get("success").getAsBoolean());
        denied(as(access(next), get("/manager/passport/user/info")));
        denied(request(post("/manager/passport/user/refresh").contentType("application/json")
                .content(new Gson().toJson(Map.of("refreshToken", refresh(next))))));
        assertTrue(as(access(second), get("/manager/passport/user/info")).get("success").getAsBoolean());
        denied(request(get("/manager/passport/user/refresh/" + refresh(second))));
    }
    @Test void concurrentSameSecondRefreshSucceedsAtMostOnceThroughActualMvc() throws Exception {
        var initial = login("a-user"); var pool = Executors.newFixedThreadPool(2); var start = new CountDownLatch(1);
        try {
            Callable<Boolean> operation = () -> { start.await(); return request(post("/manager/passport/user/refresh")
                    .contentType("application/json").content(new Gson().toJson(Map.of("refreshToken", refresh(initial)))))
                    .get("success").getAsBoolean(); };
            var one = pool.submit(operation); var two = pool.submit(operation); start.countDown();
            assertEquals(1, (one.get(10, TimeUnit.SECONDS) ? 1 : 0) + (two.get(10, TimeUnit.SECONDS) ? 1 : 0));
        } finally { pool.shutdownNow(); }
    }
    @Test void foreignDomainAndCredentialPurposeCannotObtainManagerAccessOrRefresh() throws Exception {
        var factory = new SessionService(b.store, b.keys, identity -> {}, Clock.systemUTC());
        for (var domain : List.of(SessionDomain.RESIDENT, SessionDomain.APPLICANT, SessionDomain.STORE)) {
            var foreign = factory.create(new SessionIdentity("synthetic-person", domain, "synthetic-foreign-app", "synthetic-mapping", 1L));
            denied(as(foreign.accessToken(), get("/manager/passport/user/info")));
            denied(request(post("/manager/passport/user/refresh").contentType("application/json")
                    .content(new Gson().toJson(Map.of("refreshToken", foreign.refreshToken())))));
        }
        var own = login("a-user");
        denied(as(refresh(own), get("/manager/passport/user/info")));
        denied(request(post("/manager/passport/user/refresh").contentType("application/json")
                .content(new Gson().toJson(Map.of("refreshToken", access(own))))));
        denied(as(access(own) + "tamper", get("/manager/passport/user/info")));
    }

    @Configuration @EnableWebMvc
    static class Beans {
        final Map<String, AdminUser> accounts = new LinkedHashMap<>();
        final Map<String, Role> roleMap = new LinkedHashMap<>();
        final Map<String, Menu> menuMap = new LinkedHashMap<>();
        final List<UserRole> direct = new ArrayList<>(); final List<RoleMenu> grants = new ArrayList<>();
        final AtomicInteger writes = new AtomicInteger(); final MemoryStore store = new MemoryStore();
        final AdminUserMapper adminMapper = mock(AdminUserMapper.class); final UserRoleMapper userRoleMapper = mock(UserRoleMapper.class);
        final DepartmentRoleMapper departmentMapper = mock(DepartmentRoleMapper.class); final RoleMapper roleMapper = mock(RoleMapper.class);
        final RoleMenuMapper roleMenuMapper = mock(RoleMenuMapper.class); final MenuMapper menuMapper = mock(MenuMapper.class);
        final UserRoleService userRoleService = mock(UserRoleService.class); final RoleMenuService roleMenuService = mock(RoleMenuService.class);
        final VerificationService verification = mock(VerificationService.class); final TokenUtil nativeTokens = mock(TokenUtil.class);
        final ManagerAccessSettings settings = new ManagerAccessSettings();
        SecretKeyUtil keys; SessionApplications apps; AdminUserServiceImpl adminService;
        Beans() {
            settings.setEnabled(true); settings.setRoleAIds(Set.of("role-a")); settings.setRoleBIds(Set.of("role-b"));
            settings.setManageableUserIds(Set.of("target-a")); settings.setReadMenuIds(Set.of("read-list"));
            settings.setMaintenanceMenuIds(Set.of("users", "roles", "user-roles", "role-menus", "tree", "status"));
            String hash = new BCryptPasswordEncoder(4).encode("synthetic-input");
            for (String id : List.of("a-user", "b-user", "target-a")) {
                var admin = new AdminUser(); admin.setId(id); admin.setUsername(id); admin.setPassword(hash);
                admin.setStatus(true); admin.setIsSuper(false); admin.setDeleteFlag(false); accounts.put(id, admin);
                direct.add(new UserRole(id, id.equals("b-user") ? "role-b" : "role-a"));
            }
            for (String id : List.of("role-a", "role-b")) { var role = new Role(); role.setId(id); role.setDeleteFlag(false); roleMap.put(id, role); }
            menu("read-list", "/manager/goods/goods/list"); menu("users", "/manager/passport/user/getByCondition");
            menu("roles", "/manager/permission/role"); menu("user-roles", "/manager/permission/userRole/{userId}");
            menu("role-menus", "/manager/permission/roleMenu/{roleId}"); menu("tree", "/manager/permission/menu/tree");
            menu("status", "/manager/passport/user/enable/{userId}");
            grants.add(grant("role-a", "read-list", false)); grants.add(grant("role-b", "read-list", false));
            for (String id : settings.getMaintenanceMenuIds()) grants.add(grant("role-b", id, Set.of("status", "user-roles", "role-menus").contains(id)));
            when(adminMapper.selectOne(any())).thenAnswer(i -> accounts.get(first(i.getArgument(0))));
            when(roleMapper.selectOne(any())).thenAnswer(i -> roleMap.get(first(i.getArgument(0))));
            when(menuMapper.selectOne(any())).thenAnswer(i -> menuMap.get(first(i.getArgument(0))));
            when(userRoleMapper.selectList(any())).thenAnswer(i -> {
                QueryWrapper<UserRole> q = i.getArgument(0); var values = parameters(q);
                return direct.stream().filter(row -> q.getSqlSegment().contains("user_id") ? values.contains(row.getUserId()) : values.contains(row.getRoleId())).toList();
            });
            when(departmentMapper.selectList(any())).thenReturn(List.of()); when(adminMapper.selectList(any())).thenReturn(List.of());
            when(roleMenuMapper.selectList(any())).thenAnswer(i -> grants.stream().filter(row -> row.getRoleId().equals(first(i.getArgument(0)))).toList());
            when(adminMapper.update(any(), any())).thenAnswer(i -> {
                UpdateWrapper<AdminUser> q = i.getArgument(1); q.getSqlSegment();
                var values = q.getParamNameValuePairs(); String id = values.values().stream().filter(accounts::containsKey).findFirst().orElseThrow().toString();
                var parameter = java.util.regex.Pattern.compile("paramNameValuePairs\\.([^}]+)").matcher(q.getSqlSet()); assertTrue(parameter.find());
                Object value = values.get(parameter.group(1));
                if (q.getSqlSet().startsWith("status=")) accounts.get(id).setStatus((Boolean) value);
                else accounts.get(id).setRoleIds((String) value);
                writes.incrementAndGet(); return 1;
            });
            doAnswer(i -> { String id = i.getArgument(0); direct.removeIf(row -> row.getUserId().equals(id)); direct.addAll(i.getArgument(1)); return null; })
                    .when(userRoleService).updateUserRole(anyString(), anyList());
            doAnswer(i -> { String id = i.getArgument(0); grants.removeIf(row -> row.getRoleId().equals(id));
                for (RoleMenu row : (List<RoleMenu>) i.getArgument(1)) { row.setDeleteFlag(false); grants.add(row); }
                writes.incrementAndGet(); return null; }).when(roleMenuService).updateRoleMenu(anyString(), anyList());
            when(verification.check(anyString(), any())).thenReturn(true);
        }
        void menu(String id, String permission) { var menu = new Menu(); menu.setId(id); menu.setPermission(permission);
            menu.setDeleteFlag(false); menu.setSortOrder(0.0); menu.setLevel(0); menuMap.put(id, menu); }
        @Bean ManagerAccessSettings settings() { return settings; }
        @Bean AdminUserMapper adminMapper() { return adminMapper; }
        @Bean ManagerAccessPolicy policy() { return new ManagerAccessPolicy(settings, adminMapper, userRoleMapper, departmentMapper, roleMapper, roleMenuMapper, menuMapper); }
        @Bean ManagerMaintenanceService maintenance(ManagerAccessPolicy policy) { return new ManagerMaintenanceService(settings, policy, adminMapper, userRoleMapper,
                departmentMapper, roleMenuMapper, userRoleService, roleMenuService); }
        @Bean AdminUserService admins(ManagerTokenGenerate tokens) { adminService = spy(new AdminUserServiceImpl());
            doAnswer(i -> accounts.values().stream().filter(row -> row.getUsername().equals(i.getArgument(0))).findFirst().orElse(null))
                    .when(adminService).findByUsername(anyString());
            doAnswer(i -> accounts.get(i.getArgument(0))).when(adminService).getById(anyString());
            ReflectionTestUtils.setField(adminService, "managerTokenGenerate", tokens); return adminService; }
        @Bean SessionApplications apps() { apps = new SessionApplications(); apps.setManagerApplication("synthetic-manager-app"); return apps; }
        @Bean SecretKeyUtil keys() { byte[] raw = new byte[32]; new SecureRandom().nextBytes(raw); var props = new JWTTokenProperties();
            props.setSecretKey(Base64.getEncoder().encodeToString(raw)); return keys = new SecretKeyUtil(props); }
        @Bean SessionService sessions(CurrentSessionAuthorization authorization, SecretKeyUtil keys) { return new SessionService(store, keys, authorization, Clock.systemUTC()); }
        @Bean CurrentSessionAuthorization authorization(SessionApplications apps, AdminUserService admins, ManagerTokenGenerate tokens) {
            return new CurrentSessionAuthorization(apps, mock(ObjectProvider.class), mock(MemberService.class), mock(ClerkService.class),
                    mock(StoreService.class), admins, mock(MenuService.class), mock(StoreMenuService.class), tokens, new StoreTokenGenerate()); }
        @Bean TokenUtil nativeTokens() { return nativeTokens; }
        @Bean Cache<String> cache() {
            Cache<String> cache = mock(Cache.class);
            when(cache.hasKey(any())).thenReturn(true); // A valid legacy-cache hit must still not grant W01 writes.
            doReturn(Map.of("SUPER", List.of("/**"))).when(cache).get(any());
            return cache;
        }
        @Bean MenuService menus() { return mock(MenuService.class); }
        @Bean RoleService roles() { return mock(RoleService.class); }
        @Bean UserRoleService userRoles() { return userRoleService; }
        @Bean RoleMenuService roleMenus() { return roleMenuService; }
        @Bean MemberService members() { return mock(MemberService.class); }
        @Bean ClerkService clerks() { return mock(ClerkService.class); }
        @Bean StoreMenuRoleService storeMenuRoles() { return mock(StoreMenuRoleService.class); }
        @Bean StoreTokenGenerate storeTokens() { return new StoreTokenGenerate(); }
        @Bean ManagerTokenGenerate managerTokens() { return new ManagerTokenGenerate(); }
        @Bean VerificationService verification() { return verification; }
        @Bean JWTTokenProperties tokenProperties() { return new JWTTokenProperties(); }
        @Bean IgnoredUrlsProperties ignored() { var p = new IgnoredUrlsProperties(); p.setUrls(new ArrayList<>(List.of("/manager/passport/**"))); return p; }
        @Bean CustomAccessDeniedHandler denied() { return new CustomAccessDeniedHandler(); }
        @Bean GlobalControllerExceptionHandler errors() { return new GlobalControllerExceptionHandler(); }
        @Bean @Primary CorsConfigurationSource cors() { return new UrlBasedCorsConfigurationSource(); }
        @Bean AuthenticationProvider provider() { return new AuthenticationProvider() {
            public Authentication authenticate(Authentication a) { throw new AssertionError("Native password filter should not execute"); }
            public boolean supports(Class<?> type) { return true; }
        }; }
        String legacy() { var user = AuthUser.builder().id("b-user").username("b-user").role(UserEnums.MANAGER).isSuper(true).build();
            return Jwts.builder().claim("userContext", new Gson().toJson(user)).expiration(new Date(System.currentTimeMillis()+60000))
                    .signWith(keys.generalKey(), Jwts.SIG.HS256).compact(); }
    }
    static RoleMenu grant(String role, String menu, boolean write) { var row = new RoleMenu(); row.setRoleId(role); row.setMenuId(menu); row.setIsSuper(write); row.setDeleteFlag(false); return row; }
    static Object first(QueryWrapper<?> q) { return parameters(q).iterator().next(); }
    static Set<Object> parameters(QueryWrapper<?> q) { q.getSqlSegment(); return new HashSet<>(q.getParamNameValuePairs().values()); }
    static class MemoryStore implements SessionService.Store {
        final ConcurrentMap<String, SessionRecord> rows = new ConcurrentHashMap<>();
        String key(SessionDomain d, String app, String sid) { return d+":"+app+":"+sid; }
        String key(SessionRecord r) { return key(r.identity().domain(), r.identity().application(), r.sid()); }
        public boolean create(SessionRecord r) { return rows.putIfAbsent(key(r),r)==null; }
        public SessionRecord load(SessionDomain d,String app,String sid) { return rows.get(key(d,app,sid)); }
        public boolean rotate(SessionRecord old,SessionRecord next) { return rows.replace(key(old),old,next); }
        public boolean revoke(SessionRecord authenticated) { var changed = new java.util.concurrent.atomic.AtomicBoolean();
            rows.computeIfPresent(key(authenticated),(key,current)-> { if(current.status()==SessionRecord.Status.ACTIVE) {changed.set(true); return current.revoked();} return current;}); return changed.get(); }
    }
}
