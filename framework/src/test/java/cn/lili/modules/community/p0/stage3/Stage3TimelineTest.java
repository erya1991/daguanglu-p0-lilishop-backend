package cn.lili.modules.community.p0.stage3;
import cn.lili.modules.community.p0.support.Stage1Db;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.List;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class Stage3TimelineTest {
 @Test void fulfillmentHistoryRetainsCompletionWithoutExposingInternalFundsEvents(){
  var db=mock(Stage1Db.class);var at=Instant.parse("2026-11-09T07:37:46Z");
  var completion=map("event_type","COMPLETED","actor_domain","STORE","actor_id","12","created_at",at);
  when(db.rows(anyString(),eq("11"))).thenReturn(List.of(completion,map("event_type","S45_FUNDS_UPDATE","actor_domain","SYSTEM","actor_id",null,"created_at",at),map("event_type","S45_REFUND_SUCCESS","actor_domain","SYSTEM","actor_id",null,"created_at",at)));
  var service=new Stage3Orders(db,null,null,null,null,null,null,null);
  var timeline=service.fulfillmentTimeline("11");
  assertEquals(List.of(map("type","COMPLETED","actorDomain","STORE","actorId","12","at",at)),timeline);
  assertEquals("COMPLETED",completion.get("event_type"));
 }
}
