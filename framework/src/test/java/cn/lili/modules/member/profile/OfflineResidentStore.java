package cn.lili.modules.member.profile;

import cn.lili.modules.member.mapper.*;
import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.connect.entity.*;
import cn.lili.modules.connect.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.gson.Gson;
import org.springframework.transaction.*;
import org.springframework.transaction.support.SimpleTransactionStatus;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/** Offline rollback/serialization model ONLY. No MySQL locks, indexes, schema or SQL are certified here. */
@SuppressWarnings("unchecked")
public class OfflineResidentStore implements PlatformTransactionManager {
    public final ResidentProfileMapper profiles=mock(ResidentProfileMapper.class);
    public final MemberMapper members=mock(MemberMapper.class);
    public final WechatIdentityMapper mappings=mock(WechatIdentityMapper.class);
    public final MemberIdentityLinkMapper links=mock(MemberIdentityLinkMapper.class);
    public final Map<String,ResidentProfileRow> people=new ConcurrentHashMap<>();
    public final Map<String,WechatIdentity> identities=new ConcurrentHashMap<>();
    public final Map<String,MemberIdentityLink> attempts=new ConcurrentHashMap<>();
    public final Map<String,Long> histories=new ConcurrentHashMap<>();
    public final AtomicInteger edits=new AtomicInteger(),phoneWrites=new AtomicInteger(),switches=new AtomicInteger();
    public volatile String fail;
    private final ReentrantLock mutex=new ReentrantLock();
    private static final Gson JSON=new Gson();
    public static final String APP="wx0000000000000001";
    public OfflineResidentStore() {
        people.put("123",person("123")); people.put("456",person("456"));
        WechatIdentity identity=new WechatIdentity();identity.setId("789");identity.setAppId(APP);identity.setOpenId("synthetic-open");
        identity.setMemberId("123");identity.setBindingVersion(1L);identity.setBindingState("ACTIVE");identities.put("789",identity);
        when(profiles.read(anyString())).thenAnswer(i -> copy(people.get(i.getArgument(0)),ResidentProfileRow.class));
        when(profiles.lock(anyString())).thenAnswer(i -> profiles.read(i.getArgument(0)));
        when(profiles.phoneOwners(anyString())).thenAnswer(i -> people.values().stream().filter(p -> i.getArgument(0).equals(p.getMobile())).map(ResidentProfileRow::getId).toList());
        when(profiles.edit(anyString(),anyLong(),any(),any())).thenAnswer(i -> {
            fault("edit"); var p=people.get(i.getArgument(0));if(p.getVersion()!=(long)i.getArgument(1)) return 0;
            p.setNickname(i.getArgument(2));p.setAvatar(i.getArgument(3));p.setVersion(p.getVersion()+1);edits.incrementAndGet();return 1;
        });
        when(profiles.phone(anyString(),anyLong(),anyString(),anyString(),anyString())).thenAnswer(i -> {
            fault("phone");var p=people.get(i.getArgument(0));if(p.getVersion()!=(long)i.getArgument(1)) return 0;
            p.setMobile(i.getArgument(2));p.setPhoneRequestId(i.getArgument(3));p.setPhoneRequestHash(i.getArgument(4));p.setVersion(p.getVersion()+1);phoneWrites.incrementAndGet();return 1;
        });
        when(members.selectById(anyString())).thenAnswer(i -> {
            var p=people.get(i.getArgument(0));if(p==null)return null;
            Member m=new Member();m.setId(p.getId());m.setUsername("synthetic-user-"+p.getId());m.setDisabled(p.getDisabled());m.setDeleteFlag(p.getDeleteFlag());m.setNickName(p.getNickname());return m;
        });
        when(mappings.selectById(anyString())).thenAnswer(i -> copy(identities.get(i.getArgument(0)),WechatIdentity.class));
        when(mappings.selectList(any(QueryWrapper.class))).thenAnswer(i -> {
            QueryWrapper<WechatIdentity> q=i.getArgument(0);q.getSqlSegment();var params=q.getParamNameValuePairs();
            return identities.values().stream().filter(m -> m.getAppId().equals(params.get("MPGENVAL1")) && m.getOpenId().equals(params.get("MPGENVAL2")))
                    .map(m -> copy(m,WechatIdentity.class)).toList();
        });
        when(links.lockMapping(anyString())).thenAnswer(i -> mappings.selectById(i.getArgument(0)));
        when(links.request(anyString(),anyString())).thenAnswer(i -> attempts.values().stream()
                .filter(a -> a.getMappingId().equals(i.getArgument(0)) && a.getRequestId().equals(i.getArgument(1))).map(a -> copy(a,MemberIdentityLink.class)).toList());
        when(links.pending(anyString())).thenAnswer(i -> attempts.values().stream().filter(a -> a.getMappingId().equals(i.getArgument(0)) && "PENDING".equals(a.getState()))
                .map(a -> copy(a,MemberIdentityLink.class)).toList());
        when(links.insert(any(MemberIdentityLink.class))).thenAnswer(i -> {var a=(MemberIdentityLink)i.getArgument(0);attempts.put(a.getId(),copy(a,MemberIdentityLink.class));return 1;});
        when(links.selectById(anyString())).thenAnswer(i -> copy(attempts.get(i.getArgument(0)),MemberIdentityLink.class));
        when(links.history(anyString())).thenAnswer(i -> {fault("history");return histories.getOrDefault(i.getArgument(0),0L);});
        when(links.switchMapping(anyString(),anyString(),anyString(),anyLong())).thenAnswer(i -> {
            fault("switch");var m=identities.get(i.getArgument(0));if(!m.getMemberId().equals(i.getArgument(1)) || m.getBindingVersion()!=(long)i.getArgument(3) || !m.getBindingState().equals("ACTIVE")) return 0;
            m.setMemberId(i.getArgument(2));m.setBindingVersion(m.getBindingVersion()+1);m.setBindingState("PENDING");switches.incrementAndGet();return 1;
        });
        when(links.activate(anyString(),anyString(),anyLong())).thenAnswer(i -> {var m=identities.get(i.getArgument(0));
            if(!m.getMemberId().equals(i.getArgument(1)) || m.getBindingVersion()!=(long)i.getArgument(2) || !"PENDING".equals(m.getBindingState()))return 0;
            m.setBindingState("ACTIVE");return 1;});
        when(links.done(anyString(),anyString())).thenAnswer(i -> {fault("done");var a=attempts.get(i.getArgument(0));a.setState("DONE");a.setTargetSid(i.getArgument(1));return 1;});
    }
    private void fault(String operation) {if(operation.equals(fail))throw new IllegalStateException("synthetic-private-repository-cause");}
    public static ResidentProfileRow person(String id) {var p=new ResidentProfileRow();p.setId(id);p.setNickname("住户");p.setDisabled(true);p.setDeleteFlag(false);p.setVersion(0L);return p;}
    public static <T>T copy(T value,Class<T> type) {return value==null?null:JSON.fromJson(JSON.toJson(value),type);}
    @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
        assertEquals(TransactionDefinition.ISOLATION_SERIALIZABLE,definition.getIsolationLevel());
        mutex.lock();var tx=new Tx();people.forEach((k,v)->tx.people.put(k,copy(v,ResidentProfileRow.class)));
        identities.forEach((k,v)->tx.identities.put(k,copy(v,WechatIdentity.class)));attempts.forEach((k,v)->tx.attempts.put(k,copy(v,MemberIdentityLink.class)));return tx;
    }
    @Override public void commit(TransactionStatus status) {if("commit".equals(fail)){rollback(status);throw new TransactionSystemException("synthetic-commit");}mutex.unlock();}
    @Override public void rollback(TransactionStatus status) {var tx=(Tx)status;people.clear();people.putAll(tx.people);identities.clear();identities.putAll(tx.identities);attempts.clear();attempts.putAll(tx.attempts);mutex.unlock();}
    static class Tx extends SimpleTransactionStatus {Map<String,ResidentProfileRow> people=new HashMap<>();Map<String,WechatIdentity> identities=new HashMap<>();Map<String,MemberIdentityLink> attempts=new HashMap<>();}
}
