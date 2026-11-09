package cn.lili.controller.manager.setting;
import cn.lili.modules.system.entity.dos.Region;
import cn.lili.modules.system.service.RegionService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class NativeRegionSafetyTest {
 private Region region(String id,String parent,String level){var r=new Region();r.setId(id);r.setParentId(parent);r.setLevel(level);r.setAdCode("990999");r.setOrderNum(1);r.setPath("0,"+id);return r;}
 private RegionSettingManagerController controller(RegionService service,long references){var c=new RegionSettingManagerController();JdbcTemplate jdbc=mock(JdbcTemplate.class,call->call.getMethod().getName().equals("queryForObject")?Long.valueOf(references):null);ReflectionTestUtils.setField(c,"regionService",service);ReflectionTestUtils.setField(c,"jdbc",jdbc);ReflectionTestUtils.setField(c,"uat",true);return c;}
 @Test void legacyNullSortAndNewRegionCanBeReadTogether(){
  var service=spy(new cn.lili.modules.system.serviceimpl.RegionServiceImpl());
  var legacy=region("123","122","district");legacy.setOrderNum(null);var added=region("124","122","district");
  doReturn(new java.util.ArrayList<>(List.of(legacy,added))).when(service).list(any(com.baomidou.mybatisplus.core.conditions.Wrapper.class));
  assertEquals(List.of(added,legacy),service.getItem("122"));
 }
 @Test void referencedRegionCannotBeDeleted(){var service=mock(RegionService.class);when(service.getById("123")).thenReturn(region("123","122","district"));var c=controller(service,1);assertThrows(cn.lili.common.exception.ServiceException.class,()->c.delAllByIds(List.of("123")));verify(service,never()).removeByIds(anyList());}
 @Test void unusedRegionCanBeDeleted(){var service=mock(RegionService.class);when(service.getById("123")).thenReturn(region("123","122","district"));controller(service,0).delAllByIds(List.of("123"));verify(service).removeByIds(List.of("123"));}
 @Test void creationRequiresExistingValidParentAndIgnoresClientIdentity(){var service=mock(RegionService.class);var c=controller(service,0);var input=region("injected","122","district");assertThrows(cn.lili.common.exception.ServiceException.class,()->c.save(input));var parent=region("122","121","city");when(service.getById("122")).thenReturn(parent);c.save(input);assertNull(input.getId());assertEquals("0,122",input.getPath());assertFalse(input.getDeleteFlag());verify(service).save(input);}
 @Test void referencedCodeOrHierarchyCannotBeChanged(){var service=mock(RegionService.class);var old=region("123","122","district");when(service.getById("123")).thenReturn(old);var edit=region("123","122","district");edit.setAdCode("990998");assertThrows(cn.lili.common.exception.ServiceException.class,()->controller(service,1).update("123",edit));verify(service,never()).updateById(any(Region.class));}
}
