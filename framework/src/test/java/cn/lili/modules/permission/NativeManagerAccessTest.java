package cn.lili.modules.permission;
import cn.lili.modules.permission.manager.*;
import cn.lili.modules.permission.mapper.MenuMapper;
import cn.lili.modules.permission.entity.vo.UserMenuVO;
import cn.lili.common.security.session.SessionException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class NativeManagerAccessTest {
 private final JdbcTemplate db=mock(JdbcTemplate.class);
 private final MenuMapper menus=mock(MenuMapper.class);
 private final NativeManagerAccess access=new NativeManagerAccess(db,menus);
 private void active(){when(db.queryForObject(anyString(),eq(Long.class),eq("101"))).thenReturn(1L);}
 private UserMenuVO grant(String permission,boolean write){var m=new UserMenuVO();m.setPermission(permission);m.setIsSuper(write);return m;}
 @Test void uploadRequiresCurrentNativeFileWriteGrant(){active();when(menus.getUserRoleMenu("101")).thenReturn(List.of(grant("/manager/common/file*",false)),List.of(grant("/manager/common/file*",true)),List.of());assertFalse(access.grants("101","POST","/manager/common/upload/file"));assertTrue(access.grants("101","POST","/manager/common/upload/file"));assertFalse(access.grants("101","POST","/manager/common/upload/file"));}
 @Test void emptyRoleHasOnlySelfAccess(){active();when(menus.getUserRoleMenu("101")).thenReturn(List.of());assertTrue(access.grants("101","GET","/manager/passport/user/info"));assertFalse(access.grants("101","POST","/manager/passport/user/register"));}
 @Test void mixedNativeAndStagePermissionsDoNotGrantUnrelatedWrites(){active();when(menus.getUserRoleMenu("101")).thenReturn(List.of(grant("/manager/permission/role*",false),grant("s1.goods.review",true)));assertTrue(access.grants("101","GET","/manager/permission/role"));assertFalse(access.grants("101","POST","/manager/permission/role"));assertFalse(access.grants("101","POST","/manager/passport/user/register"));}
 @Test void wildcardCannotReopenLegacyCommerceOrExternalOperations(){active();when(menus.getUserRoleMenu("101")).thenReturn(List.of(grant("/manager/*",true)));assertFalse(access.grants("101","POST","/manager/order/afterSale/refund"));assertFalse(access.grants("101","POST","/manager/setting/timed-task/trigger/orderCancel"));assertFalse(access.grants("101","POST","/manager/setting/region/sync"));assertTrue(access.grants("101","POST","/manager/permission/role"));}
 @Test void permissionsAreReadAgainAfterRevocation(){active();when(menus.getUserRoleMenu("101")).thenReturn(List.of(grant("/manager/permission/role*",true)),List.of());assertTrue(access.grants("101","POST","/manager/permission/role"));assertFalse(access.grants("101","POST","/manager/permission/role"));}
 @Test void disabledAccountRejectedEvenForSelf(){assertThrows(SessionException.class,()->access.grants("101","GET","/manager/passport/user/info"));}
 @Test void cutoffRejectsAllOlderSessionsAndAcceptsNewAuthentication(){when(db.queryForList(anyString(),eq(Long.class),eq("101"))).thenReturn(List.of(1234L));assertThrows(SessionException.class,()->access.checkIssuedAt("101",1234));assertThrows(SessionException.class,()->access.checkIssuedAt("101",1233));assertDoesNotThrow(()->access.checkIssuedAt("101",1235));}
 @Test void lastEntryRejectsEmptyAndAcceptsOrdinaryGrantBasedAdmin(){when(db.queryForList(anyString(),eq(String.class))).thenReturn(List.of());assertThrows(cn.lili.common.exception.ServiceException.class,access::requireManagementEntry);active();when(db.queryForList(anyString(),eq(String.class))).thenReturn(List.of("101"));when(menus.getUserRoleMenu("101")).thenReturn(List.of(grant("/manager/*",true)));assertThrows(cn.lili.common.exception.ServiceException.class,access::requireManagementEntry);when(menus.findByUserId("101")).thenReturn(managementPages());assertDoesNotThrow(access::requireManagementEntry);}
 private List<cn.lili.modules.permission.entity.dos.Menu> managementPages(){var result=new ArrayList<cn.lili.modules.permission.entity.dos.Menu>();String[][] specs={{"users","user-manage/userManage","/manager/passport/user/*"},{"roles","role-manage/roleManage","/manager/permission/role*"},{"menus","menu-manage/menuManage","/manager/permission/menu*"}};for(var spec:specs){var m=new cn.lili.modules.permission.entity.dos.Menu();m.setId(String.valueOf(result.size()+1));m.setName("uat-"+spec[0]);m.setPath("/settings/"+spec[0]);m.setFrontRoute("sys/"+spec[1]);m.setPermission(spec[2]);m.setParentId("0");result.add(m);}return result;}
 @Test void brokenMenuCannotCountAsLastManagementEntry(){active();when(menus.getUserRoleMenu("101")).thenReturn(List.of(grant("/manager/*",true)));var rows=managementPages();rows.getFirst().setParentId(rows.getFirst().getId());when(menus.findByUserId("101")).thenReturn(rows);assertFalse(access.hasManagementEntry("101"));rows.getFirst().setParentId("0");rows.getFirst().setFrontRoute("unknown/component");assertFalse(access.hasManagementEntry("101"));}
 @Test void menuSelfCycleIsRejectedBeforePersistence(){var menu=managementPages().getFirst();menu.setTitle("Accounts");menu.setSortOrder(0.0);menu.setParentId(menu.getId());assertThrows(SessionException.class,()->access.validateMenu(menu));verifyNoInteractions(db);}
 @Test void nativeFilesAndSmsReadsDoNotOpenProviderMutations(){active();when(menus.getUserRoleMenu("101")).thenReturn(List.of(grant("/manager/*",true)));assertTrue(access.grants("101","GET","/manager/common/file"));assertTrue(access.grants("101","GET","/manager/sms/sms"));assertFalse(access.grants("101","POST","/manager/sms/sms"));assertFalse(access.grants("101","PUT","/manager/sms/sign/querySmsSign"));}
}
