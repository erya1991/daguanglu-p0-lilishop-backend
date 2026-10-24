package cn.lili.modules.permission.manager;

import cn.lili.common.security.session.SessionException;
import cn.lili.modules.permission.entity.dos.*;
import cn.lili.modules.permission.mapper.*;
import cn.lili.modules.permission.service.RoleMenuService;
import cn.lili.modules.permission.service.UserRoleService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic approved-scope fixtures only; no database, Redis, production IDs or write approval evidence. */
class ManagerAccessPolicyTest {
    @Test
    void defaultConfigurationDoesNotGrantAnyRoleOrTarget() {
        ManagerAccessSettings settings = new ManagerAccessSettings();
        assertFalse(settings.isEnabled());
        assertTrue(settings.getRoleAIds().isEmpty());
        assertTrue(settings.getRoleBIds().isEmpty());
        assertTrue(settings.getManageableUserIds().isEmpty());
        assertTrue(settings.getReadMenuIds().isEmpty());
        assertTrue(settings.getMaintenanceMenuIds().isEmpty());
        Fixture f = new Fixture(); f.settings.setEnabled(false);
        assertThrows(SessionException.class, () -> f.policy.current("a-user"));
    }

    @Test
    void emptyOrOverlappingRoleConfigurationFailsClosed() {
        Fixture f = new Fixture(); f.settings.setRoleAIds(Set.of());
        assertThrows(SessionException.class, () -> f.policy.current("a-user"));
        f.settings.setRoleAIds(Set.of("role-b"));
        assertThrows(SessionException.class, () -> f.policy.current("b-user"));
    }

    @Test
    void malformedConfiguredIdsCannotBecomeTargetOrRouteAuthority() {
        for (String value : List.of("role/*", "role:a", "{roleId}", "role.a", "role a")) {
            Fixture f = new Fixture(); f.settings.getRoleAIds().add(value);
            assertThrows(SessionException.class, () -> f.policy.current("b-user"));
        }
        assertFalse(ManagerAccessPolicy.allowedRoute("PUT", "/manager/permission/userRole/{userId}"));
        assertFalse(ManagerAccessPolicy.allowedRoute("PUT", "/manager/permission/userRole/user:a"));
    }

    @Test
    void validAAndBUseCurrentNonSuperRoles() {
        Fixture f = new Fixture();
        assertEquals(ManagerAccessPolicy.Kind.A, f.policy.current("a-user").kind());
        assertEquals(ManagerAccessPolicy.Kind.B, f.policy.current("b-user").kind());
        assertFalse(f.policy.current("b-user").admin().getIsSuper());
        assertEquals(Set.of("role-b"), f.policy.current("b-user").roleIds());
    }

    @Test
    void aDescriptionDoesNotExposeMaintenanceTargets() {
        Fixture f = new Fixture(); Map<String, Object> description = f.policy.describe("a-user");
        assertEquals("A", description.get("role"));
        assertEquals(List.of("SELF_READ", "MENU_SELF_READ", "PREP_READ"), description.get("capabilities"));
        assertEquals(Set.of(), description.get("manageableUserIds"));
        assertEquals(Set.of(), description.get("roleAIds"));
        assertEquals(Set.of(), description.get("maintenanceMenuIds"));
    }

    @Test
    void bDescriptionIncludesAllElevenCurrentCapabilitiesIncludingDynamicRoutes() {
        Fixture f = new Fixture();
        Map<String, Object> description = f.policy.describe("b-user");
        assertEquals("B", description.get("role"));
        Set<?> capabilities = new HashSet<>((List<?>) description.get("capabilities"));
        assertEquals(Set.of("SELF_READ", "MENU_SELF_READ", "PREP_READ", "USER_READ", "ROLE_READ",
                "USER_ROLE_READ", "ROLE_MENU_READ", "MENU_READ", "USER_STATUS_WRITE", "USER_ROLE_WRITE",
                "ROLE_MENU_WRITE"), capabilities);
        assertEquals(Set.of("target-a"), description.get("manageableUserIds"));
        assertEquals(Set.of("role-a"), description.get("roleAIds"));
    }

    @Test
    void dynamicPrepOnlyGrantIsDescribedWithoutRelaxingActualHttpSegmentValidation() {
        Fixture f = new Fixture();
        f.menuMap.get("read-list").setPermission("/manager/goods/goods/get/{id}");
        List<?> capabilities = (List<?>) f.policy.describe("a-user").get("capabilities");
        assertTrue(capabilities.contains("PREP_READ"));
        assertDoesNotThrow(() -> f.policy.requireRoute("a-user", "GET", "/manager/goods/goods/get/item-1"));
        assertFalse(ManagerAccessPolicy.allowedRoute("GET", "/manager/goods/goods/get/{id}"));
        assertThrows(SessionException.class,
                () -> f.policy.requireRoute("a-user", "GET", "/manager/goods/goods/get/{id}"));
    }

    static Stream<Arguments> maintenanceRoutes() {
        return Stream.of(
                Arguments.of("GET", "/manager/passport/user/getByCondition"),
                Arguments.of("GET", "/manager/permission/role"),
                Arguments.of("GET", "/manager/permission/userRole/target-a"),
                Arguments.of("GET", "/manager/permission/roleMenu/role-a"),
                Arguments.of("GET", "/manager/permission/menu/tree"),
                Arguments.of("PUT", "/manager/passport/user/enable/target-a"),
                Arguments.of("PUT", "/manager/permission/userRole/target-a"),
                Arguments.of("POST", "/manager/permission/roleMenu/role-a"));
    }

    @ParameterizedTest @MethodSource("maintenanceRoutes")
    void aCannotUseMaintenanceAndBRequiresCurrentMethodGrant(String method, String path) {
        Fixture f = new Fixture();
        assertTrue(ManagerAccessPolicy.allowedRoute(method, path));
        assertThrows(SessionException.class, () -> f.policy.requireRoute("a-user", method, path));
        assertDoesNotThrow(() -> f.policy.requireRoute("b-user", method, path));
    }

    static Stream<Arguments> rejectedRoutes() {
        return Stream.of(
                Arguments.of("POST", "/manager/passport/user/register"),
                Arguments.of("PUT", "/manager/passport/user/admin/edit"),
                Arguments.of("POST", "/manager/passport/user/resetPassword/a-user"),
                Arguments.of("DELETE", "/manager/passport/user/a-user"),
                Arguments.of("POST", "/manager/permission/role"),
                Arguments.of("PUT", "/manager/permission/menu/read-list"),
                Arguments.of("PUT", "/manager/permission/departmentRole/x"),
                Arguments.of("GET", "/manager/passport/user/enable/target-a"),
                Arguments.of("PUT", "/manager/passport/user/enable/a%2fb"),
                Arguments.of("PUT", "/manager/passport/user/enable/a;b"),
                Arguments.of("PUT", "/manager/passport/user/enable/a/child"),
                Arguments.of("GET", "/manager/goods/goods/get/a%25"),
                Arguments.of("GET", "/manager/goods/goods/get/a/extra"));
    }

    @ParameterizedTest @MethodSource("rejectedRoutes")
    void unknownMethodsLegacyBypassesAndMalformedSegmentsReject(String method, String path) {
        Fixture f = new Fixture();
        assertFalse(ManagerAccessPolicy.allowedRoute(method, path));
        assertThrows(SessionException.class, () -> f.policy.requireRoute("b-user", method, path));
    }

    @Test
    void currentRoleAndMenuRevocationRejectsOldContextOnNextRequest() {
        Fixture f = new Fixture();
        f.policy.requireRoute("b-user", "PUT", "/manager/passport/user/enable/target-a");
        f.grants.removeIf(row -> row.getRoleId().equals("role-b") && row.getMenuId().equals("status"));
        assertThrows(SessionException.class,
                () -> f.policy.requireRoute("b-user", "PUT", "/manager/passport/user/enable/target-a"));
        f.direct.removeIf(row -> row.getUserId().equals("b-user"));
        assertThrows(SessionException.class, () -> f.policy.current("b-user"));
    }

    @Test
    void revokingAllMenusKeepsValidAIdentityButOnlySelfAndEmptyMenuReads() {
        Fixture f = new Fixture();
        f.grants.removeIf(row -> row.getRoleId().equals("role-a"));
        ManagerAccessPolicy.Context current = f.policy.current("a-user");
        assertEquals(ManagerAccessPolicy.Kind.A, current.kind());
        assertTrue(current.menus().isEmpty());
        assertTrue(current.grants().isEmpty());
        assertEquals(List.of("SELF_READ", "MENU_SELF_READ"), f.policy.describe("a-user").get("capabilities"));
        assertDoesNotThrow(() -> f.policy.requireRoute("a-user", "GET", "/manager/passport/user/info"));
        assertDoesNotThrow(() -> f.policy.requireRoute("a-user", "GET", "/manager/permission/menu/memberMenu"));
        assertThrows(SessionException.class,
                () -> f.policy.requireRoute("a-user", "GET", "/manager/goods/goods/list"));
        assertThrows(SessionException.class,
                () -> f.policy.requireRoute("a-user", "PUT", "/manager/passport/user/enable/target-a"));
        f.direct.removeIf(row -> row.getUserId().equals("a-user"));
        assertThrows(SessionException.class, () -> f.policy.current("a-user"));
    }

    @Test
    void queryOnlyFlagDoesNotAuthorizeTheSameUrisWriteMethod() {
        Fixture f = new Fixture();
        f.grants.stream().filter(row -> row.getMenuId().equals("user-roles")).forEach(row -> row.setIsSuper(false));
        f.policy.requireRoute("b-user", "GET", "/manager/permission/userRole/target-a");
        assertThrows(SessionException.class,
                () -> f.policy.requireRoute("b-user", "PUT", "/manager/permission/userRole/target-a"));
    }

    @Test
    void mixedExtraDisabledOrDeletedIdentitiesCannotAcquireNewSessionAuthority() {
        Fixture f = new Fixture(); f.direct.add(new UserRole("b-user", "role-a"));
        assertThrows(SessionException.class, () -> f.policy.current("b-user"));
        f.direct.removeLast(); f.accounts.get("b-user").setStatus(false);
        assertThrows(SessionException.class, () -> f.policy.current("b-user"));
        f.accounts.get("b-user").setStatus(true); f.accounts.get("b-user").setIsSuper(true);
        assertThrows(SessionException.class, () -> f.policy.current("b-user"));
        assertFalse(f.policy.isConfiguredRoleMember("b-user"));
        f.accounts.get("b-user").setIsSuper(false); f.roleMap.get("role-b").setDeleteFlag(true);
        assertThrows(SessionException.class, () -> f.policy.current("b-user"));
    }

    @Test
    void missingMenuOrDataQueryFailureIsNotAnEmptyPermissionSuccess() {
        Fixture f = new Fixture(); f.menuMap.remove("read-list");
        assertThrows(SessionException.class, () -> f.policy.current("a-user"));
        doReturn(null).when(f.roleMenuMapper).selectList(any());
        assertThrows(SessionException.class, () -> f.policy.current("b-user"));
    }

    @Test
    void scopedReadbackUsesActualUserRelationAndRemovesPassword() {
        Fixture f = new Fixture();
        List<UserRole> rows = f.policy.userRoles("b-user", "target-a");
        assertEquals(1, rows.size()); assertEquals("target-a", rows.getFirst().getUserId());
        assertEquals("role-a", rows.getFirst().getRoleId()); assertNull(rows.getFirst().getId());
        var users = f.policy.users("b-user");
        assertEquals(1, users.size()); assertNull(users.getFirst().getPassword());
        assertNull(users.getFirst().getMobile()); assertNull(users.getFirst().getEmail());
        assertEquals("synthetic-not-a-real-hash", f.accounts.get("target-a").getPassword());
        assertThrows(SessionException.class, () -> f.policy.userRoles("b-user", "outside"));
        assertThrows(SessionException.class, () -> f.policy.roleMenus("b-user", "role-b"));
    }

    @Test
    void roleReadbackReturnsOnlyApprovedDisplayFieldsAndRejectsForeignGrants() {
        Fixture f = new Fixture();
        Role source = f.roleMap.get("role-a"); source.setName("Synthetic restricted role");
        source.setDescription("Synthetic description"); source.setCreateBy("private-audit"); source.setDefaultRole(true);
        Role view = f.policy.roles("b-user").getFirst();
        assertNotSame(source, view); assertEquals("role-a", view.getId());
        assertEquals(source.getName(), view.getName()); assertEquals(source.getDescription(), view.getDescription());
        assertNull(view.getCreateBy()); assertNull(view.getDefaultRole());
        f.grants.add(grant("role-a", "outside", false));
        assertThrows(SessionException.class, () -> f.policy.roles("b-user"));
        assertThrows(SessionException.class, () -> f.policy.roleMenus("b-user", "role-a"));
    }

    @Test
    void bCanChangeOnlyTargetStatusAndUsesRowLocks() {
        Fixture f = new Fixture();
        f.maintenance.setEnabled("b-user", "target-a", false);
        verify(f.adminMapper).update(isNull(), argThat(wrapper -> {
            UpdateWrapper<AdminUser> update = (UpdateWrapper<AdminUser>) wrapper;
            return update.getSqlSet().startsWith("status=") && !update.getSqlSet().contains("is_super");
        }));
        verify(f.adminMapper, atLeastOnce()).selectOne(argThat(wrapper -> wrapper.getSqlSegment().contains("FOR UPDATE")));
    }

    @Test
    void selfSuperOtherBUnknownAndUnconfiguredTargetsRejectBeforeWrites() {
        Fixture f = new Fixture();
        assertThrows(SessionException.class, () -> f.maintenance.setEnabled("b-user", "b-user", false));
        assertThrows(SessionException.class, () -> f.maintenance.setEnabled("b-user", "outside", false));
        f.accounts.get("target-a").setIsSuper(true);
        assertThrows(SessionException.class, () -> f.maintenance.setEnabled("b-user", "target-a", false));
        f.accounts.get("target-a").setIsSuper(false); f.direct.add(new UserRole("target-a", "role-b"));
        assertThrows(SessionException.class, () -> f.maintenance.setEnabled("b-user", "target-a", false));
        verify(f.adminMapper, never()).update(any(), any());
    }

    @Test
    void bReplacesOnlyDirectAUserRolesAndUpdatesRedundantColumn() {
        Fixture f = new Fixture();
        f.maintenance.replaceUserRoles("b-user", "target-a", List.of());
        verify(f.userRoleService).updateUserRole("target-a", List.of());
        verify(f.adminMapper).update(isNull(), argThat(wrapper -> ((UpdateWrapper<AdminUser>) wrapper)
                .getSqlSet().startsWith("role_ids=")));
    }

    @Test
    void bCanAssignAnAOnlyRoleToAnUnassignedApprovedTarget() {
        Fixture f = new Fixture(); f.direct.removeIf(row -> row.getUserId().equals("target-a"));
        f.maintenance.replaceUserRoles("b-user", "target-a", List.of("role-a"));
        verify(f.userRoleService).updateUserRole(eq("target-a"), argThat(rows -> rows.size() == 1
                && "role-a".equals(rows.getFirst().getRoleId()) && "target-a".equals(rows.getFirst().getUserId())));
    }

    @Test
    void bRoleExtraRoleDuplicateOrSelfAssignmentNeverDeletesExistingRoles() {
        Fixture f = new Fixture();
        for (List<String> roles : List.of(List.of("role-b"), List.of("extra"), List.of("role-a", "role-a"))) {
            assertThrows(SessionException.class, () -> f.maintenance.replaceUserRoles("b-user", "target-a", roles));
        }
        assertThrows(SessionException.class, () -> f.maintenance.replaceUserRoles("b-user", "b-user", List.of("role-a")));
        f.direct.add(new UserRole("target-a", "extra"));
        assertThrows(SessionException.class, () -> f.maintenance.replaceUserRoles("b-user", "target-a", List.of()));
        verifyNoInteractions(f.userRoleService);
    }

    @Test
    void aRoleConfiguredByIdCannotBeAssignedWhenItsLiveGrantHasWriteFlag() {
        Fixture f = new Fixture();
        f.direct.removeIf(row -> row.getUserId().equals("target-a"));
        f.grants.stream().filter(row -> row.getRoleId().equals("role-a")).forEach(row -> row.setIsSuper(true));
        assertThrows(SessionException.class,
                () -> f.maintenance.replaceUserRoles("b-user", "target-a", List.of("role-a")));
        verifyNoInteractions(f.userRoleService);
    }

    @Test
    void aRoleWithAdministrativeWriteNodeCannotBeAssignedThroughW2() {
        Fixture f = new Fixture();
        f.direct.removeIf(row -> row.getUserId().equals("target-a"));
        f.settings.getReadMenuIds().add("bad-read");
        f.menuMap.put("bad-read", menu("bad-read", "/manager/passport/user/enable/{userId}"));
        f.grants.add(grant("role-a", "bad-read", false));
        assertThrows(SessionException.class,
                () -> f.maintenance.replaceUserRoles("b-user", "target-a", List.of("role-a")));
        verifyNoInteractions(f.userRoleService);
    }

    @Test
    void targetWithOutOfScopeRoleMenuCannotBeOverwrittenOrRevokedThroughW2() {
        Fixture f = new Fixture();
        f.grants.add(grant("role-a", "outside", false));
        assertThrows(SessionException.class,
                () -> f.maintenance.replaceUserRoles("b-user", "target-a", List.of()));
        verifyNoInteractions(f.userRoleService);
    }

    @Test
    void inheritedTargetRoleCannotBePretendedRevokedByDirectReplacement() {
        Fixture f = new Fixture();
        f.accounts.get("target-a").setDepartmentId("department-a");
        f.inherited.add(department("department-a", "role-a"));
        assertThrows(SessionException.class, () -> f.maintenance.replaceUserRoles("b-user", "target-a", List.of()));
        verifyNoInteractions(f.userRoleService);
    }

    @Test
    void bCanReplaceOnlyApprovedAReadMenuRelationsWithFalseFlags() {
        Fixture f = new Fixture();
        f.maintenance.replaceRoleMenus("b-user", "role-a", List.of("read-list"));
        verify(f.roleMenuService).updateRoleMenu(eq("role-a"), argThat(rows -> rows.size() == 1
                && "read-list".equals(rows.getFirst().getMenuId()) && Boolean.FALSE.equals(rows.getFirst().getIsSuper())));
    }

    @Test
    void bCannotReplaceBRoleOrInjectMaintenanceMenuOrDeleteOutOfScopeRelations() {
        Fixture f = new Fixture();
        assertThrows(SessionException.class, () -> f.maintenance.replaceRoleMenus("b-user", "role-b", List.of("read-list")));
        assertThrows(SessionException.class, () -> f.maintenance.replaceRoleMenus("b-user", "role-a", List.of("status")));
        f.grants.add(grant("role-a", "outside", false));
        assertThrows(SessionException.class, () -> f.maintenance.replaceRoleMenus("b-user", "role-a", List.of()));
        verifyNoInteractions(f.roleMenuService);
    }

    @Test
    void readScopeCannotContainWildcardOrHiddenWriteDefinition() {
        Fixture f = new Fixture();
        f.settings.getReadMenuIds().add("bad-read");
        f.menuMap.put("bad-read", menu("bad-read", "/manager/goods/goods/get/*"));
        assertThrows(SessionException.class, () -> f.maintenance.replaceRoleMenus("b-user", "role-a", List.of("bad-read")));
        f.menuMap.get("bad-read").setPermission("/manager/passport/user/enable/{userId}");
        assertThrows(SessionException.class, () -> f.maintenance.replaceRoleMenus("b-user", "role-a", List.of("bad-read")));
        verifyNoInteractions(f.roleMenuService);
    }

    @Test
    void structuralMenuNodeCannotBeSubmittedAsANewGrant() {
        Fixture f = new Fixture();
        f.settings.getReadMenuIds().add("tree-parent");
        f.menuMap.put("tree-parent", menu("tree-parent", null));
        assertThrows(SessionException.class, () -> f.maintenance.replaceRoleMenus("b-user", "role-a", List.of("tree-parent")));
        verifyNoInteractions(f.roleMenuService);
    }

    @Test
    void existingApprovedStructuralRelationSurvivesApiLeafOnlyReplacement() {
        Fixture f = new Fixture();
        f.settings.getReadMenuIds().add("tree-parent");
        f.menuMap.put("tree-parent", menu("tree-parent", null));
        f.grants.add(grant("role-a", "tree-parent", false));
        f.maintenance.replaceRoleMenus("b-user", "role-a", List.of("read-list"));
        verify(f.roleMenuService).updateRoleMenu(eq("role-a"), argThat(rows -> rows.size() == 2
                && rows.stream().allMatch(row -> Boolean.FALSE.equals(row.getIsSuper()))
                && rows.stream().map(RoleMenu::getMenuId).collect(java.util.stream.Collectors.toSet())
                .equals(Set.of("tree-parent", "read-list"))));
    }

    @Test
    void indirectBDepartmentOrSharedRoleReferenceBlocksAReplacement() {
        Fixture f = new Fixture();
        AdminUser otherB = account("other-b"); otherB.setDepartmentId("shared-department");
        f.accounts.put(otherB.getId(), otherB);
        f.direct.add(new UserRole("other-b", "role-b"));
        f.inherited.add(department("shared-department", "role-a"));
        assertThrows(SessionException.class, () -> f.maintenance.replaceRoleMenus("b-user", "role-a", List.of()));
        verifyNoInteractions(f.roleMenuService);
    }

    @Test
    void persistenceAndCacheErrorsMustRemainExplicitFailures() {
        Fixture f = new Fixture(); doReturn(0).when(f.adminMapper).update(any(), any());
        assertThrows(SessionException.class, () -> f.maintenance.setEnabled("b-user", "target-a", false));
        doThrow(new IllegalStateException("synthetic-cache-failure")).when(f.roleMenuService).updateRoleMenu(anyString(), anyList());
        assertThrows(IllegalStateException.class, () -> f.maintenance.replaceRoleMenus("b-user", "role-a", List.of()));
    }

    private static class Fixture {
        final ManagerAccessSettings settings = new ManagerAccessSettings();
        final AdminUserMapper adminMapper = mock(AdminUserMapper.class);
        final UserRoleMapper userRoleMapper = mock(UserRoleMapper.class);
        final DepartmentRoleMapper departmentRoleMapper = mock(DepartmentRoleMapper.class);
        final RoleMapper roleMapper = mock(RoleMapper.class);
        final RoleMenuMapper roleMenuMapper = mock(RoleMenuMapper.class);
        final MenuMapper menuMapper = mock(MenuMapper.class);
        final UserRoleService userRoleService = mock(UserRoleService.class);
        final RoleMenuService roleMenuService = mock(RoleMenuService.class);
        final Map<String, AdminUser> accounts = new LinkedHashMap<>();
        final Map<String, Role> roleMap = new LinkedHashMap<>();
        final Map<String, Menu> menuMap = new LinkedHashMap<>();
        final List<UserRole> direct = new ArrayList<>();
        final List<DepartmentRole> inherited = new ArrayList<>();
        final List<RoleMenu> grants = new ArrayList<>();
        final ManagerAccessPolicy policy;
        final ManagerMaintenanceService maintenance;

        @SuppressWarnings("unchecked")
        Fixture() {
            settings.setEnabled(true); settings.setRoleAIds(Set.of("role-a")); settings.setRoleBIds(Set.of("role-b"));
            settings.setManageableUserIds(Set.of("target-a")); settings.setReadMenuIds(Set.of("read-list"));
            settings.setMaintenanceMenuIds(Set.of("users", "roles", "user-roles", "role-menus", "tree", "status"));
            for (String id : List.of("a-user", "b-user", "target-a")) accounts.put(id, account(id));
            for (String id : List.of("role-a", "role-b")) { Role role = new Role(); role.setId(id); role.setDeleteFlag(false); roleMap.put(id, role); }
            direct.add(new UserRole("a-user", "role-a")); direct.add(new UserRole("b-user", "role-b")); direct.add(new UserRole("target-a", "role-a"));
            menuMap.put("read-list", menu("read-list", "/manager/goods/goods/list"));
            menuMap.put("users", menu("users", "/manager/passport/user/getByCondition"));
            menuMap.put("roles", menu("roles", "/manager/permission/role"));
            menuMap.put("user-roles", menu("user-roles", "/manager/permission/userRole/{userId}"));
            menuMap.put("role-menus", menu("role-menus", "/manager/permission/roleMenu/{roleId}"));
            menuMap.put("tree", menu("tree", "/manager/permission/menu/tree"));
            menuMap.put("status", menu("status", "/manager/passport/user/enable/{userId}"));
            grants.add(grant("role-a", "read-list", false)); grants.add(grant("role-b", "read-list", false));
            for (String id : settings.getMaintenanceMenuIds()) grants.add(grant("role-b", id, Set.of("user-roles", "role-menus", "status").contains(id)));
            when(adminMapper.selectOne(any())).thenAnswer(call -> accounts.get(first(call.getArgument(0))));
            when(roleMapper.selectOne(any())).thenAnswer(call -> roleMap.get(first(call.getArgument(0))));
            when(menuMapper.selectOne(any())).thenAnswer(call -> menuMap.get(first(call.getArgument(0))));
            when(adminMapper.update(any(), any())).thenReturn(1);
            when(adminMapper.selectList(any())).thenAnswer(call -> {
                Set<Object> ids = parameters(call.getArgument(0));
                return accounts.values().stream().filter(row -> ids.contains(row.getDepartmentId())).toList();
            });
            when(userRoleMapper.selectList(any())).thenAnswer(call -> {
                QueryWrapper<UserRole> query = call.getArgument(0); Set<Object> values = parameters(query);
                return direct.stream().filter(row -> query.getSqlSegment().contains("user_id")
                        ? values.contains(row.getUserId()) : values.contains(row.getRoleId())).toList();
            });
            when(departmentRoleMapper.selectList(any())).thenAnswer(call -> {
                QueryWrapper<DepartmentRole> query = call.getArgument(0); Set<Object> values = parameters(query);
                return inherited.stream().filter(row -> query.getSqlSegment().contains("department_id")
                        ? values.contains(row.getDepartmentId()) : values.contains(row.getRoleId())).toList();
            });
            when(roleMenuMapper.selectList(any())).thenAnswer(call -> {
                Object role = first(call.getArgument(0)); return grants.stream().filter(row -> row.getRoleId().equals(role)).toList();
            });
            policy = new ManagerAccessPolicy(settings, adminMapper, userRoleMapper, departmentRoleMapper, roleMapper, roleMenuMapper, menuMapper);
            maintenance = new ManagerMaintenanceService(settings, policy, adminMapper, userRoleMapper, departmentRoleMapper,
                    roleMenuMapper, userRoleService, roleMenuService);
        }
    }

    private static AdminUser account(String id) {
        AdminUser admin = new AdminUser(); admin.setId(id); admin.setUsername("synthetic-" + id);
        admin.setStatus(true); admin.setIsSuper(false); admin.setDeleteFlag(false); admin.setPassword("synthetic-not-a-real-hash"); return admin;
    }
    private static Menu menu(String id, String permission) {
        Menu menu = new Menu(); menu.setId(id); menu.setPermission(permission); menu.setDeleteFlag(false); return menu;
    }
    private static RoleMenu grant(String role, String menu, boolean write) {
        RoleMenu row = new RoleMenu(); row.setRoleId(role); row.setMenuId(menu); row.setIsSuper(write); row.setDeleteFlag(false); return row;
    }
    private static DepartmentRole department(String department, String role) {
        DepartmentRole row = new DepartmentRole(); row.setDepartmentId(department); row.setRoleId(role);
        row.setDeleteFlag(false); return row;
    }
    private static Object first(QueryWrapper<?> query) { return parameters(query).iterator().next(); }
    private static Set<Object> parameters(QueryWrapper<?> query) {
        query.getSqlSegment(); return new HashSet<>(query.getParamNameValuePairs().values());
    }
}
