package cn.lili.controller.manager.wechat;
import cn.lili.common.vo.*;
import cn.lili.modules.wechat.entity.dos.WechatMPMessage;
import cn.lili.modules.wechat.service.WechatMPMessageService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class NativeWechatTemplateTest {
 @Test void listHasNoImplicitEnabledOnlyFilter(){var service=mock(WechatMPMessageService.class);var c=new WechatMPMessageManagerController();ReflectionTestUtils.setField(c,"wechatMPMessageService",service);var entity=new WechatMPMessage();assertTrue(entity.getEnable());c.getByPage(entity,new SearchVO(),new PageVO(),null);assertNull(entity.getEnable());c.getByPage(entity,new SearchVO(),new PageVO(),false);assertFalse(entity.getEnable());verify(service,times(2)).getByPage(eq(entity),any(),any());}
}
