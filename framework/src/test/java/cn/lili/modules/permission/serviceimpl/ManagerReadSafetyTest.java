package cn.lili.modules.permission.serviceimpl;

import cn.lili.cache.Cache;
import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.security.token.Token;
import cn.lili.modules.permission.entity.dos.AdminUser;
import cn.lili.modules.permission.entity.dos.Menu;
import cn.lili.modules.permission.entity.vo.AdminUserVO;
import cn.lili.modules.permission.mapper.MenuMapper;
import cn.lili.modules.system.token.ManagerTokenGenerate;
import com.google.gson.Gson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Offline login/read safety only; no approved W01 write grants or real resources. */
@ExtendWith(OutputCaptureExtension.class)
class ManagerReadSafetyTest {
    private static final String USERNAME = "synthetic-manager";
    private static final String PASSWORD = "synthetic-manager-login-only";
    private static final String HASH = new BCryptPasswordEncoder(4).encode(PASSWORD);
    private AdminUserServiceImpl admins;
    private ManagerTokenGenerate tokens;
    private AdminUser admin;

    @BeforeEach
    void setUp() {
        admins = spy(new AdminUserServiceImpl());
        tokens = mock(ManagerTokenGenerate.class);
        ReflectionTestUtils.setField(admins, "managerTokenGenerate", tokens);
        admin = validAdmin();
        doReturn(admin).when(admins).findByUsername(USERNAME);
    }

    static Stream<Arguments> emptyCredentials() {
        return Stream.of(
                Arguments.of(null, PASSWORD),
                Arguments.of("", PASSWORD),
                Arguments.of("  ", PASSWORD),
                Arguments.of(USERNAME, null),
                Arguments.of(USERNAME, ""),
                Arguments.of(USERNAME, "  "));
    }

    @ParameterizedTest
    @MethodSource("emptyCredentials")
    void emptyCredentialsRejectBeforeLookupOrIssuance(String username, String password) {
        ServiceException error = assertThrows(ServiceException.class, () -> admins.login(username, password));
        assertEquals(ResultCode.USER_PASSWORD_ERROR, error.getResultCode());
        verify(admins, never()).findByUsername(any());
        verifyNoInteractions(tokens);
    }

    static Stream<Arguments> invalidAccountStates() {
        return Stream.of(
                Arguments.of(null, false, HASH),
                Arguments.of(false, false, HASH),
                Arguments.of(true, true, HASH),
                Arguments.of(true, null, HASH),
                Arguments.of(true, false, null),
                Arguments.of(true, false, " "));
    }

    @ParameterizedTest
    @MethodSource("invalidAccountStates")
    void ambiguousDisabledDeletedOrUncredentialedAccountCannotIssue(Boolean status, Boolean deleted, String hash) {
        admin.setStatus(status);
        admin.setDeleteFlag(deleted);
        admin.setPassword(hash);
        ServiceException error = assertThrows(ServiceException.class, () -> admins.login(USERNAME, PASSWORD));
        assertEquals(ResultCode.USER_PASSWORD_ERROR, error.getResultCode());
        verifyNoInteractions(tokens);
    }

    @Test
    void missingAccountRejectsWithoutIssuance() {
        doReturn(null).when(admins).findByUsername(USERNAME);
        ServiceException error = assertThrows(ServiceException.class, () -> admins.login(USERNAME, PASSWORD));
        assertEquals(ResultCode.USER_PASSWORD_ERROR, error.getResultCode());
        verifyNoInteractions(tokens);
    }

    @Test
    void wrongPasswordCannotIssue() {
        ServiceException error = assertThrows(ServiceException.class,
                () -> admins.login(USERNAME, "synthetic-wrong-credential"));
        assertEquals(ResultCode.USER_PASSWORD_ERROR, error.getResultCode());
        verifyNoInteractions(tokens);
    }

    @Test
    void validNonSuperBcryptLoginKeepsOriginalNativeTokenContract() {
        Token expected = pair("synthetic-native-access", "synthetic-native-refresh");
        when(tokens.createToken(admin, false)).thenReturn(expected);
        assertSame(expected, admins.login(USERNAME, PASSWORD));
        assertFalse(admin.getIsSuper());
        verify(tokens).createToken(same(admin), eq(false));
        verifyNoMoreInteractions(tokens);
    }

    static Stream<Arguments> incompleteTokens() {
        return Stream.of(
                Arguments.of((Token) null),
                Arguments.of(new Token()),
                Arguments.of(pair("", "synthetic-refresh")),
                Arguments.of(pair("synthetic-access", " ")),
                Arguments.of(pair(null, "synthetic-refresh")),
                Arguments.of(pair("synthetic-access", null)));
    }

    @ParameterizedTest
    @MethodSource("incompleteTokens")
    void emptyOrIncompleteIssuanceMustNotBecomeLoginSuccess(Token issued) {
        when(tokens.createToken(admin, false)).thenReturn(issued);
        ServiceException error = assertThrows(ServiceException.class, () -> admins.login(USERNAME, PASSWORD));
        assertEquals(ResultCode.ERROR, error.getResultCode());
    }

    @Test
    void issuanceExceptionIsExplicitAndItsSensitiveMessageIsNotLogged(CapturedOutput output) {
        String privateDetail = "synthetic-private-credential-detail";
        when(tokens.createToken(admin, false)).thenThrow(new IllegalStateException(privateDetail));
        ServiceException error = assertThrows(ServiceException.class, () -> admins.login(USERNAME, PASSWORD));
        assertEquals(ResultCode.ERROR, error.getResultCode());
        assertFalse(error.getMessage().contains(privateDetail));
        assertFalse(output.getAll().contains(privateDetail));
        assertFalse(output.getAll().contains(PASSWORD));
        assertFalse(output.getAll().contains(HASH));
    }

    @Test
    @SuppressWarnings("unchecked")
    void menuReadUsesCurrentMapperOnEveryRequestAndNeverReadsLegacyCache() {
        MenuServiceImpl menus = new MenuServiceImpl();
        MenuMapper mapper = mock(MenuMapper.class);
        Cache<Object> cache = mock(Cache.class);
        ReflectionTestUtils.setField(menus, "baseMapper", mapper);
        ReflectionTestUtils.setField(menus, "cache", cache);
        Menu initial = new Menu();
        initial.setId("synthetic-menu");
        List<Menu> granted = List.of(initial);
        List<Menu> revoked = List.of();
        when(mapper.findByUserId("synthetic-user")).thenReturn(granted, revoked);
        assertSame(granted, menus.findUserList("synthetic-user"));
        assertSame(revoked, menus.findUserList("synthetic-user"));
        verify(mapper, times(2)).findByUserId("synthetic-user");
        verifyNoInteractions(cache);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unavailableMenuResultFailsInsteadOfReturningCachedOrEmptyGrants() {
        MenuServiceImpl menus = new MenuServiceImpl();
        MenuMapper mapper = mock(MenuMapper.class);
        Cache<Object> cache = mock(Cache.class);
        ReflectionTestUtils.setField(menus, "baseMapper", mapper);
        ReflectionTestUtils.setField(menus, "cache", cache);
        when(mapper.findByUserId("synthetic-user")).thenReturn(null);
        ServiceException error = assertThrows(ServiceException.class, () -> menus.findUserList("synthetic-user"));
        assertEquals(ResultCode.ERROR, error.getResultCode());
        verifyNoInteractions(cache);
    }

    @Test
    @SuppressWarnings("unchecked")
    void menuQueryExceptionPropagatesWithoutCacheFallback() {
        MenuServiceImpl menus = new MenuServiceImpl();
        MenuMapper mapper = mock(MenuMapper.class);
        Cache<Object> cache = mock(Cache.class);
        ReflectionTestUtils.setField(menus, "baseMapper", mapper);
        ReflectionTestUtils.setField(menus, "cache", cache);
        IllegalStateException failure = new IllegalStateException("synthetic-query-failure");
        when(mapper.findByUserId("synthetic-user")).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> menus.findUserList("synthetic-user")));
        verifyNoInteractions(cache);
    }

    @Test
    void accountListVoRemovesPasswordWithoutMutatingStoredEntity() {
        AdminUserVO view = new AdminUserVO(admin);
        assertNull(view.getPassword());
        assertEquals(HASH, admin.getPassword());
        assertEquals(admin.getId(), view.getId());
        assertEquals(USERNAME, view.getUsername());
        assertEquals(admin.getStatus(), view.getStatus());
        String json = new Gson().toJson(view);
        assertFalse(json.contains(HASH));
        assertFalse(json.contains(PASSWORD));
    }

    private static Token pair(String access, String refresh) {
        Token token = new Token();
        token.setAccessToken(access);
        token.setRefreshToken(refresh);
        return token;
    }

    private static AdminUser validAdmin() {
        AdminUser admin = new AdminUser();
        admin.setId("synthetic-user");
        admin.setUsername(USERNAME);
        admin.setPassword(HASH);
        admin.setStatus(true);
        admin.setDeleteFlag(false);
        admin.setIsSuper(false);
        return admin;
    }
}
