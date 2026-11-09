package cn.lili.controller.manager.setting;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class NativeTimedTaskSafetyTest {
 @Test void reportsConfiguredDisabledSchedulesAndRefusesManualLegacyMutation(){
  var c=new TimedTaskManagerController();ReflectionTestUtils.setField(c,"environment",new MockEnvironment().withProperty("p0.uat.enabled","true").withProperty("lili.schedule.every-day-cron","-"));
  for(String field:List.of("everyMinuteExecutes","everyHourExecutes","everyDayExecutes"))ReflectionTestUtils.setField(c,field,List.of());
  var rows=c.list().getResult();assertFalse(rows.isEmpty());for(var row:rows){assertEquals("-",row.get("cron"));assertEquals(false,row.get("scheduled"));assertEquals(false,row.get("manualTriggerAllowed"));}
  for(String id:List.of("ORDER_AUTO_EVALUATE","ORDER_PROFIT_SHARING","CancelOrderTaskExecute")){var result=c.trigger(id);assertFalse(result.isSuccess());assertEquals("P0_LEGACY_TASK_DISABLED",result.getMessage());}
 }
}
