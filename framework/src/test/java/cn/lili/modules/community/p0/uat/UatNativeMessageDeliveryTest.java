package cn.lili.modules.community.p0.uat;
import cn.lili.modules.community.p0.stage2.Stage2Settings;
import cn.lili.modules.member.service.MemberService;
import cn.lili.modules.store.service.StoreService;
import cn.lili.modules.message.entity.dos.*;
import cn.lili.modules.message.service.*;
import cn.lili.modules.community.p0.support.Stage1Exception;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class UatNativeMessageDeliveryTest {
 final MemberMessageService members=mock(MemberMessageService.class);final StoreMessageService stores=mock(StoreMessageService.class);
 final UatNativeMessageDelivery service=spy(new UatNativeMessageDelivery(mock(Stage2Settings.class),mock(MemberService.class),mock(StoreService.class),members,stores));
 Message input(){Message m=new Message();m.setTitle("本地通知");m.setContent("测试");m.setMessageClient("member");m.setMessageRange("MEMBER");m.setUserIds(new String[]{"101"});m.setUserNames(new String[]{"forged"});return m;}
 @Test void serverRecipientsAndAuditFieldsCannotBeForged(){doReturn(List.of(Map.of("id","101","name","actual"))).when(service).recipients("member");Message m=input();m.setId("999");m.setCreateBy("forged");Message safe=service.prepare(m);assertNull(safe.getId());assertNull(safe.getCreateBy());assertArrayEquals(new String[]{"actual"},safe.getUserNames());}
 @Test void missingUnknownAndWrongClientTargetsAreRejected(){doReturn(List.of(Map.of("id","101","name","actual"))).when(service).recipients("member");Message m=input();m.setUserIds(new String[]{"999"});assertThrows(Stage1Exception.class,()->service.prepare(m));m.setUserIds(new String[0]);assertThrows(Stage1Exception.class,()->service.prepare(m));m.setMessageClient("sms");assertThrows(Stage1Exception.class,()->service.prepare(m));}
 @Test void onlyNativeRecipientServicePersistsTheInbox(){Message m=input();m.setId("200");when(members.save(anyList())).thenReturn(true);service.deliver(m);verify(members).save(argThat((List<MemberMessage> rows)->rows.size()==1&&rows.getFirst().getMemberId().equals("101")&&rows.getFirst().getMessageId().equals("200")));verifyNoInteractions(stores);}
 @Test void failedRecipientPersistenceDoesNotReportSuccess(){when(members.save(anyList())).thenReturn(false);assertThrows(Stage1Exception.class,()->service.deliver(input()));}
}
