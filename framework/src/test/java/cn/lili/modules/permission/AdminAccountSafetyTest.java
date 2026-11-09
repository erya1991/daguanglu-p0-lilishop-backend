package cn.lili.modules.permission;
import cn.lili.cache.Cache;
import cn.lili.modules.permission.entity.dos.AdminUser;
import cn.lili.modules.permission.entity.dto.AdminUserDTO;
import cn.lili.modules.permission.service.*;
import cn.lili.modules.permission.serviceimpl.AdminUserServiceImpl;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class AdminAccountSafetyTest {
 private final UserRoleService grants=mock(UserRoleService.class);
 private final Cache cache=mock(Cache.class);
 private AdminUserServiceImpl service(){var s=spy(new AdminUserServiceImpl());ReflectionTestUtils.setField(s,"userRoleService",grants);ReflectionTestUtils.setField(s,"roleService",mock(RoleService.class));ReflectionTestUtils.setField(s,"cache",cache);return s;}
 @Test void creationWithNoRolesCannotInjectSuperOrClientId(){
  var s=service();var input=new AdminUserDTO();input.setUsername("ordinary");input.setPassword("test-md5-input");input.setIsSuper(true);input.setId("attacker-id");
  var saved=new AdminUser();saved.setId("101");doReturn(saved).when(s).findByUsername("ordinary");
  doAnswer(call->{AdminUser row=call.getArgument(0);assertNull(row.getId());assertFalse(row.getIsSuper());assertTrue(row.getStatus());assertFalse(row.getDeleteFlag());assertTrue(new BCryptPasswordEncoder().matches("test-md5-input",row.getPassword()));return true;}).when(s).save(any(AdminUser.class));
  assertDoesNotThrow(()->s.saveAdminUser(input,null));verify(grants).remove(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
 }
 @Test void profileEditCannotOverwriteCredentialsOrElevateAccountAndEmptyRolesRevoke(){
  var s=service();var current=new AdminUser();current.setId("101");current.setIsSuper(false);current.setStatus(true);current.setPassword("retained-hash");current.setRoleIds("old-role");doReturn(current).when(s).getById("101");doReturn(true).when(s).updateById(any(AdminUser.class));
  var input=new AdminUser();input.setId("101");input.setUsername("ordinary");input.setPassword("injected");input.setIsSuper(true);input.setStatus(false);
  s.updateAdminUser(input,List.of());assertFalse(current.getIsSuper());assertTrue(current.getStatus());assertEquals("retained-hash",current.getPassword());assertEquals("",current.getRoleIds());verify(grants).remove(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));verify(cache,times(2)).vagueDel(anyString());
 }
 @Test void absentRolesPreserveExistingRelations(){var s=service();var current=new AdminUser();current.setId("101");current.setRoleIds("unchanged");doReturn(current).when(s).getById("101");doReturn(true).when(s).updateById(any(AdminUser.class));var input=new AdminUser();input.setId("101");s.updateAdminUser(input,null);assertEquals("unchanged",current.getRoleIds());verifyNoInteractions(grants);}
}
