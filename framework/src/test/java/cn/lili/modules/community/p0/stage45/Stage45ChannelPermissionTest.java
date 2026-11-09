package cn.lili.modules.community.p0.stage45;
import cn.lili.modules.community.p0.channel.*;
import cn.lili.modules.community.p0.support.*;
import cn.lili.modules.community.p0.channel.ChannelService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import java.time.Clock;import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.mockito.Mockito.*;import static org.junit.jupiter.api.Assertions.*;
class Stage45ChannelPermissionTest{
 @Test void independentSyncAllowsOnlyMockApplicationQueryAndRechecksRevocation(){var db=mock(Stage1Db.class);var access=mock(Stage1Access.class);var extension=mock(Stage45Authorization.class);var service=new ChannelService(db,null,access,null,null,null,null,null,null,mock(PlatformTransactionManager.class),Clock.systemUTC());ReflectionTestUtils.setField(service,"stage45",new StaticListableBeanFactory(Map.of("s45",extension)).getBeanProvider(Stage45Authorization.class));when(access.managerPermissions("123")).thenReturn(Set.of());when(extension.managerPermissions("123")).thenReturn(Set.of("channel.sync"));when(db.required("SELECT mode FROM li_p0_submerchant_relation WHERE id=?","456")).thenReturn(map("mode","MOCK"));var op=map("actor_id","123","action","QUERY","object_type","APPLICATION","object_id","456");assertEquals(true,ReflectionTestUtils.invokeMethod(service,"permitted",op));op.put("action","SUBMIT");assertEquals(false,ReflectionTestUtils.invokeMethod(service,"permitted",op));op.put("action","QUERY");when(db.required("SELECT mode FROM li_p0_submerchant_relation WHERE id=?","456")).thenReturn(map("mode","REAL"));assertEquals(false,ReflectionTestUtils.invokeMethod(service,"permitted",op));when(db.required("SELECT mode FROM li_p0_submerchant_relation WHERE id=?","456")).thenReturn(map("mode","MOCK"));when(extension.managerPermissions("123")).thenReturn(Set.of());assertEquals(false,ReflectionTestUtils.invokeMethod(service,"permitted",op));}
 @Test void nativeOnboardingPermissionKeepsItsExistingContract(){var db=mock(Stage1Db.class);var access=mock(Stage1Access.class);when(access.managerPermissions("123")).thenReturn(Set.of("channel.onboard"));var service=new ChannelService(db,null,access,null,null,null,null,null,null,mock(PlatformTransactionManager.class),Clock.systemUTC());assertEquals(true,ReflectionTestUtils.invokeMethod(service,"permitted",map("actor_id","123","action","SUBMIT","object_type","APPLICATION","object_id","456")));}
 @Test void noExtensionNeverGrantsNativeRights(){var access=mock(Stage1Access.class);when(access.managerPermissions("123")).thenReturn(Set.of());var service=new ChannelService(mock(Stage1Db.class),null,access,null,null,null,null,null,null,mock(PlatformTransactionManager.class),Clock.systemUTC());assertEquals(false,ReflectionTestUtils.invokeMethod(service,"permitted",map("actor_id","123","action","QUERY","object_type","APPLICATION","object_id","456")));}
}
