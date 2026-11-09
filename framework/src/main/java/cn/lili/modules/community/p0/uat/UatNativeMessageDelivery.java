package cn.lili.modules.community.p0.uat;

import cn.lili.modules.community.p0.stage2.Stage2Settings;
import cn.lili.modules.community.p0.support.Stage1Exception;
import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.member.service.MemberService;
import cn.lili.modules.store.entity.dos.Store;
import cn.lili.modules.store.service.StoreService;
import cn.lili.modules.message.entity.dos.*;
import cn.lili.modules.message.service.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import java.util.*;

/** Local native inbox delivery. Never starts the legacy outbound queue or external providers. */
@Service @RequiredArgsConstructor
@ConditionalOnProperty(name="p0.uat.enabled",havingValue="true")
public class UatNativeMessageDelivery {
    private final Stage2Settings resources;
    private final MemberService members;
    private final StoreService stores;
    private final MemberMessageService memberMessages;
    private final StoreMessageService storeMessages;
    public List<Map<String,String>> recipients(String client) {
        UatResourceScope.registered(resources);
        require(Set.of("member","store").contains(client));
        if(client.equals("store"))return stores.list(new QueryWrapper<Store>().eq("store_disable","OPEN")).stream()
            .map(s->Map.of("id",s.getId(),"name",s.getStoreName())).toList();
        return members.list(new QueryWrapper<Member>().eq("disabled",1)).stream()
            .map(m->Map.of("id",m.getId(),"name",m.getUsername())).toList();
    }
    public Message prepare(Message input) {
        require(input!=null && input.getTitle()!=null && !input.getTitle().isBlank() && input.getTitle().length()<=50
            && input.getContent()!=null && !input.getContent().isBlank() && input.getContent().length()<=1000);
        require(input.getMessageClient()!=null && Set.of("member","store").contains(input.getMessageClient()));
        boolean all="ALL".equals(input.getMessageRange());
        require(all || (input.getMessageClient().equals("member")?"MEMBER":"APPOINT").equals(input.getMessageRange()));
        List<Map<String,String>> available=recipients(input.getMessageClient());
        Set<String> ids=all?new LinkedHashSet<>():new LinkedHashSet<>(Arrays.asList(input.getUserIds()==null?new String[0]:input.getUserIds()));
        require(all || (!ids.isEmpty() && ids.size()<=100 && available.stream().map(r->r.get("id")).toList().containsAll(ids)));
        List<Map<String,String>> selected=available.stream().filter(r->all||ids.contains(r.get("id"))).toList();
        Message result=new Message(); // Client IDs and audit fields never cross into persistence.
        result.setTitle(input.getTitle());result.setContent(input.getContent());result.setMessageClient(input.getMessageClient());result.setMessageRange(input.getMessageRange());
        result.setUserIds(selected.stream().map(r->r.get("id")).toArray(String[]::new));
        result.setUserNames(selected.stream().map(r->r.get("name")).toArray(String[]::new));
        return result;
    }
    public void deliver(Message message) {
        List<MemberMessage> memberRows=new ArrayList<>();List<StoreMessage> storeRows=new ArrayList<>();
        for(int i=0;i<message.getUserIds().length;i++) {
            if(message.getMessageClient().equals("store")) {
                StoreMessage row=new StoreMessage();row.setStoreId(message.getUserIds()[i]);row.setStoreName(message.getUserNames()[i]);
                row.setMessageId(message.getId());row.setTitle(message.getTitle());row.setContent(message.getContent());row.setStatus("UN_READY");storeRows.add(row);
            } else {
                MemberMessage row=new MemberMessage();row.setMemberId(message.getUserIds()[i]);row.setMemberName(message.getUserNames()[i]);
                row.setMessageId(message.getId());row.setTitle(message.getTitle());row.setContent(message.getContent());row.setStatus("UN_READY");memberRows.add(row);
            }
        }
        if(!memberRows.isEmpty())require(memberMessages.save(memberRows));
        if(!storeRows.isEmpty())require(storeMessages.save(storeRows));
    }
    private static void require(boolean valid){Stage1Exception.require(valid,422,"NATIVE_MESSAGE_INVALID");}
}
