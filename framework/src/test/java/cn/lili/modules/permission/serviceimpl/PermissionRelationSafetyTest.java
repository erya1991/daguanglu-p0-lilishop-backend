package cn.lili.modules.permission.serviceimpl;

import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.modules.permission.entity.dos.RoleMenu;
import cn.lili.modules.permission.entity.dos.UserRole;
import cn.lili.modules.permission.mapper.RoleMenuMapper;
import cn.lili.modules.permission.mapper.UserRoleMapper;
import cn.lili.modules.permission.service.RoleMenuService;
import cn.lili.modules.permission.service.UserRoleService;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Offline failure/target-isolation tests, not approval of any W01 role or write permission.
 * Transaction tests exercise Spring's real interceptor with an isolated transaction-manager
 * double; they do not claim real database rollback or cache integration evidence.
 */
class PermissionRelationSafetyTest {
    private RoleMenuServiceImpl roleMenus;
    private UserRoleServiceImpl userRoles;
    private RoleMenuMapper roleMenuMapper;
    private UserRoleMapper userRoleMapper;
    private Cache<Object> cache;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        cache = mock(Cache.class);
        roleMenuMapper = mock(RoleMenuMapper.class);
        userRoleMapper = mock(UserRoleMapper.class);
        roleMenus = spy(new RoleMenuServiceImpl());
        userRoles = spy(new UserRoleServiceImpl());
        ReflectionTestUtils.setField(roleMenus, "baseMapper", roleMenuMapper);
        ReflectionTestUtils.setField(roleMenus, "cache", cache);
        ReflectionTestUtils.setField(userRoles, "baseMapper", userRoleMapper);
        ReflectionTestUtils.setField(userRoles, "cache", cache);
        doReturn(true).when(roleMenus).saveBatch(anyCollection());
        doReturn(true).when(userRoles).saveBatch(anyCollection());
    }

    static Stream<Arguments> invalidRoleMenus() {
        return Stream.of(
                Arguments.of(null, List.of(roleMenu("role-a", "menu-a"))),
                Arguments.of(" ", List.of(roleMenu("role-a", "menu-a"))),
                Arguments.of("role-a", null),
                Arguments.of("role-a", Arrays.asList((RoleMenu) null)),
                Arguments.of("role-a", List.of(roleMenu("role-b", "menu-a"))),
                Arguments.of("role-a", List.of(roleMenu("role-a", " "))),
                Arguments.of("role-a", List.of(roleMenu("role-a", "menu-a"), roleMenu("role-a", "menu-a"))),
                Arguments.of("role-a", List.of(new RoleMenu())));
    }

    @ParameterizedTest
    @MethodSource("invalidRoleMenus")
    void invalidRoleMenuTargetsCannotDeleteOrWrite(String roleId, List<RoleMenu> relations) {
        assertThrows(ServiceException.class, () -> roleMenus.updateRoleMenu(roleId, relations));
        verifyNoInteractions(roleMenuMapper, cache);
        verify(roleMenus, never()).saveBatch(anyCollection());
    }

    @Test
    void missingGrantFlagCannotBecomeAnImplicitGrant() {
        RoleMenu relation = roleMenu("role-a", "menu-a");
        relation.setIsSuper(null);
        assertThrows(ServiceException.class, () -> roleMenus.updateRoleMenu("role-a", List.of(relation)));
        verifyNoInteractions(roleMenuMapper, cache);
    }

    static Stream<Arguments> invalidUserRoles() {
        return Stream.of(
                Arguments.of(null, List.of(new UserRole("user-a", "role-a"))),
                Arguments.of(" ", List.of(new UserRole("user-a", "role-a"))),
                Arguments.of("user-a", null),
                Arguments.of("user-a", Arrays.asList((UserRole) null)),
                Arguments.of("user-a", List.of(new UserRole("user-b", "role-a"))),
                Arguments.of("user-a", List.of(new UserRole("user-a", " "))),
                Arguments.of("user-a", List.of(new UserRole("user-a", "role-a"), new UserRole("user-a", "role-a"))),
                Arguments.of("user-a", List.of(new UserRole())));
    }

    @ParameterizedTest
    @MethodSource("invalidUserRoles")
    void invalidUserRoleTargetsCannotDeleteOrWrite(String userId, List<UserRole> relations) {
        assertThrows(ServiceException.class, () -> userRoles.updateUserRole(userId, relations));
        verifyNoInteractions(userRoleMapper, cache);
        verify(userRoles, never()).saveBatch(anyCollection());
    }

    @Test
    void emptyRoleMenusRevokeAllAndZeroDeletedRowsAreValid() {
        when(roleMenuMapper.delete(any(Wrapper.class))).thenReturn(0);
        roleMenus.updateRoleMenu("role-a", List.of());
        verify(roleMenuMapper).delete(argThat(q -> q.getSqlSegment().contains("role_id")));
        verify(roleMenus, never()).saveBatch(anyCollection());
        verifyBothCachesInvalidated();
    }

    @Test
    void emptyUserRolesRevokeAllAndZeroDeletedRowsAreValid() {
        when(userRoleMapper.delete(any(Wrapper.class))).thenReturn(0);
        userRoles.updateUserRole("user-a", List.of());
        verify(userRoleMapper).delete(argThat(q -> q.getSqlSegment().contains("user_id")));
        verify(userRoles, never()).saveBatch(anyCollection());
        verifyBothCachesInvalidated();
    }

    @Test
    void roleMenuReplacementDropsClientRelationIdAndAuditFields() {
        RoleMenu relation = roleMenu("role-a", "menu-a");
        relation.setId("foreign-relation");
        relation.setCreateBy("untrusted-audit");
        relation.setDeleteFlag(true);
        doAnswer(invocation -> {
            Collection<RoleMenu> rows = invocation.getArgument(0);
            RoleMenu row = rows.iterator().next();
            assertNotSame(relation, row);
            assertNull(row.getId());
            assertNull(row.getCreateBy());
            assertNull(row.getDeleteFlag());
            assertEquals("role-a", row.getRoleId());
            assertEquals("menu-a", row.getMenuId());
            assertFalse(row.getIsSuper());
            return true;
        }).when(roleMenus).saveBatch(anyCollection());
        roleMenus.updateRoleMenu("role-a", List.of(relation));
        verifyBothCachesInvalidated();
    }

    @Test
    void userRoleReplacementDropsClientRelationId() {
        UserRole relation = new UserRole("user-a", "role-a");
        relation.setId("foreign-relation");
        doAnswer(invocation -> {
            Collection<UserRole> rows = invocation.getArgument(0);
            UserRole row = rows.iterator().next();
            assertNotSame(relation, row);
            assertNull(row.getId());
            assertEquals("user-a", row.getUserId());
            assertEquals("role-a", row.getRoleId());
            return true;
        }).when(userRoles).saveBatch(anyCollection());
        userRoles.updateUserRole("user-a", List.of(relation));
        verifyBothCachesInvalidated();
    }

    @Test
    void roleMenuSaveFalseIsAnExplicitFailure() {
        doReturn(false).when(roleMenus).saveBatch(anyCollection());
        assertThrows(ServiceException.class,
                () -> roleMenus.updateRoleMenu("role-a", List.of(roleMenu("role-a", "menu-a"))));
        verifyNoInteractions(cache);
    }

    @Test
    void userRoleSaveFalseIsAnExplicitFailure() {
        doReturn(false).when(userRoles).saveBatch(anyCollection());
        assertThrows(ServiceException.class,
                () -> userRoles.updateUserRole("user-a", List.of(new UserRole("user-a", "role-a"))));
        verifyNoInteractions(cache);
    }

    @Test
    void roleMenuDeleteFailureCannotContinueToSave() {
        when(roleMenuMapper.delete(any(Wrapper.class))).thenThrow(new IllegalStateException("synthetic-delete"));
        assertThrows(IllegalStateException.class,
                () -> roleMenus.updateRoleMenu("role-a", List.of(roleMenu("role-a", "menu-a"))));
        verify(roleMenus, never()).saveBatch(anyCollection());
        verifyNoInteractions(cache);
    }

    @Test
    void userRoleDeleteFailureCannotContinueToSave() {
        when(userRoleMapper.delete(any(Wrapper.class))).thenThrow(new IllegalStateException("synthetic-delete"));
        assertThrows(IllegalStateException.class,
                () -> userRoles.updateUserRole("user-a", List.of(new UserRole("user-a", "role-a"))));
        verify(userRoles, never()).saveBatch(anyCollection());
        verifyNoInteractions(cache);
    }

    @Test
    void roleMenuSaveExceptionReachesSpringRollback() {
        doThrow(new IllegalStateException("synthetic-insert")).when(roleMenus).saveBatch(anyCollection());
        PlatformTransactionManager tm = transactionManager();
        RoleMenuService proxy = transactional(roleMenus, RoleMenuService.class, tm);
        assertThrows(IllegalStateException.class,
                () -> proxy.updateRoleMenu("role-a", List.of(roleMenu("role-a", "menu-a"))));
        verify(tm).rollback(any());
        verify(tm, never()).commit(any());
        verifyNoInteractions(cache);
    }

    @Test
    void userRoleSaveExceptionReachesSpringRollback() {
        doThrow(new IllegalStateException("synthetic-insert")).when(userRoles).saveBatch(anyCollection());
        PlatformTransactionManager tm = transactionManager();
        UserRoleService proxy = transactional(userRoles, UserRoleService.class, tm);
        assertThrows(IllegalStateException.class,
                () -> proxy.updateUserRole("user-a", List.of(new UserRole("user-a", "role-a"))));
        verify(tm).rollback(any());
        verify(tm, never()).commit(any());
        verifyNoInteractions(cache);
    }

    @Test
    void roleMenuCacheFailureReachesSpringRollback() {
        doThrow(new IllegalStateException("synthetic-cache")).when(cache)
                .vagueDel(CachePrefix.PERMISSION_LIST.getPrefix(UserEnums.MANAGER));
        PlatformTransactionManager tm = transactionManager();
        RoleMenuService proxy = transactional(roleMenus, RoleMenuService.class, tm);
        assertThrows(IllegalStateException.class, () -> proxy.updateRoleMenu("role-a", List.of()));
        verify(tm).rollback(any());
        verify(tm, never()).commit(any());
    }

    @Test
    void userRoleCacheFailureReachesSpringRollback() {
        doThrow(new IllegalStateException("synthetic-cache")).when(cache)
                .vagueDel(CachePrefix.PERMISSION_LIST.getPrefix(UserEnums.MANAGER));
        PlatformTransactionManager tm = transactionManager();
        UserRoleService proxy = transactional(userRoles, UserRoleService.class, tm);
        assertThrows(IllegalStateException.class, () -> proxy.updateUserRole("user-a", List.of()));
        verify(tm).rollback(any());
        verify(tm, never()).commit(any());
    }

    private void verifyBothCachesInvalidated() {
        verify(cache).vagueDel(CachePrefix.USER_MENU.getPrefix(UserEnums.MANAGER));
        verify(cache).vagueDel(CachePrefix.PERMISSION_LIST.getPrefix(UserEnums.MANAGER));
    }

    private static RoleMenu roleMenu(String roleId, String menuId) {
        RoleMenu relation = new RoleMenu();
        relation.setRoleId(roleId);
        relation.setMenuId(menuId);
        relation.setIsSuper(false);
        return relation;
    }

    private static PlatformTransactionManager transactionManager() {
        PlatformTransactionManager tm = mock(PlatformTransactionManager.class);
        when(tm.getTransaction(any(TransactionDefinition.class))).thenReturn(new SimpleTransactionStatus());
        return tm;
    }

    private static <T> T transactional(T target, Class<T> api, PlatformTransactionManager tm) {
        TransactionInterceptor advice = new TransactionInterceptor();
        advice.setTransactionManager(tm);
        advice.setTransactionAttributeSource(new AnnotationTransactionAttributeSource());
        ProxyFactory factory = new ProxyFactory();
        factory.setTarget(target);
        factory.setInterfaces(api);
        factory.addAdvice(advice);
        return api.cast(factory.getProxy());
    }
}
