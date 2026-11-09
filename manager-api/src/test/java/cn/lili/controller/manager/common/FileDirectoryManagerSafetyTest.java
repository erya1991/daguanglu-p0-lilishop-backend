package cn.lili.controller.manager.common;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.context.UserContext;
import cn.lili.common.exception.ServiceException;
import cn.lili.modules.file.entity.FileDirectory;
import cn.lili.modules.file.service.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class FileDirectoryManagerSafetyTest {
 final FileDirectoryService directories=mock(FileDirectoryService.class);
 final FileService files=mock(FileService.class);
 final FileDirectoryManagerController controller=new FileDirectoryManagerController(directories,files);
 FileDirectory input(){var d=new FileDirectory();d.setDirectoryName("UAT group");d.setLevel(0);d.setParentId("0");return d;}
 AuthUser actor(){var a=new AuthUser();a.setId("101");return a;}
 @Test void createDropsIdentityAndAuditFields(){try(var context=mockStatic(UserContext.class)){context.when(UserContext::getCurrentUser).thenReturn(actor());var d=input();d.setId("999");d.setOwnerId("other");d.setCreateBy("forged");d.setDeleteFlag(true);controller.addSceneFileList(d);var saved=ArgumentCaptor.forClass(FileDirectory.class);verify(directories).save(saved.capture());assertNull(saved.getValue().getId());assertNull(saved.getValue().getCreateBy());assertNotEquals(Boolean.TRUE,saved.getValue().getDeleteFlag());assertEquals("101",saved.getValue().getOwnerId());assertEquals("MANAGER",saved.getValue().getDirectoryType());}}
 @Test void foreignParentRejectedBeforeSave(){try(var context=mockStatic(UserContext.class)){context.when(UserContext::getCurrentUser).thenReturn(actor());var d=input();d.setLevel(1);d.setParentId("202");doThrow(new ServiceException("FOREIGN")).when(directories).assertDirectoryOwned("202","101","MANAGER");assertThrows(ServiceException.class,()->controller.addSceneFileList(d));verify(directories,never()).save(any());}}
 @Test void parentLevelCannotFormCycle(){try(var context=mockStatic(UserContext.class)){context.when(UserContext::getCurrentUser).thenReturn(actor());var d=input();d.setId("201");d.setParentId("201");d.setLevel(1);when(directories.getById("201")).thenReturn(d);assertThrows(ServiceException.class,()->controller.editSceneFileList(d));verify(directories,never()).updateById(any(FileDirectory.class));}}
}
