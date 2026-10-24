package cn.lili.modules.connect.wechat;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.common.security.session.SessionIdentity;
import cn.lili.common.utils.SnowFlake;
import cn.lili.modules.connect.entity.WechatIdentity;
import cn.lili.modules.connect.mapper.WechatIdentityMapper;
import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.member.mapper.MemberMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.security.SecureRandom;
import java.util.*;
import java.util.function.Supplier;
import static cn.lili.modules.connect.wechat.ResidentLoginException.Code.*;

/** Atomic Member + mapping creation; B04 must confirm InnoDB and binary composite UNIQUE first. */
@Service
public class WechatIdentityService {
    private final WechatIdentityMapper mappings;
    private final MemberMapper members;
    private final ResidentWechatProperties properties;
    private final MerchantWechatProperties merchant;
    private final TransactionTemplate transaction;

    @org.springframework.beans.factory.annotation.Autowired
    public WechatIdentityService(WechatIdentityMapper mappings, MemberMapper members,
                                 ResidentWechatProperties properties, MerchantWechatProperties merchant,
                                 PlatformTransactionManager manager) {
        this.mappings = mappings; this.members = members; this.properties = properties; this.merchant = merchant;
        transaction = new TransactionTemplate(manager);
        transaction.setTimeout(10);
    }
    public WechatIdentityService(WechatIdentityMapper mappings, MemberMapper members,
                                 ResidentWechatProperties properties, PlatformTransactionManager manager) {
        this(mappings, members, properties, new MerchantWechatProperties(), manager);
    }

    public SessionIdentity resolve(WechatCodeGateway.Identity identity) {
        return resolve(identity, SessionDomain.RESIDENT);
    }
    /** Login establishes a personal mapping first; only the merchant grant resolver may select STORE. */
    public SessionIdentity resolve(WechatCodeGateway.Identity identity, SessionDomain domain) {
        if (identity == null || (domain != SessionDomain.RESIDENT && domain != SessionDomain.APPLICANT)) {
            throw new ResidentLoginException(IDENTITY_CONFLICT);
        }
        requireSchema(domain);
        // Never return a Session identity from an uncommitted outer transaction.
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new ResidentLoginException(LOGIN_UNAVAILABLE);
        try {
            return committed(() -> {
                WechatIdentity found = find(identity.appId(), identity.openId());
                if (found != null) return checked(found, identity.appId(), identity.openId(), "ACTIVE", domain);
                Member member = minimalMember();
                if (members.insert(member) != 1) throw new ResidentLoginException(LOGIN_UNAVAILABLE);
                WechatIdentity mapping = new WechatIdentity();
                mapping.setId(SnowFlake.getIdStr());
                mapping.setAppId(identity.appId()); mapping.setOpenId(identity.openId());
                mapping.setMemberId(member.getId()); mapping.setUnionId(identity.unionId());
                mapping.setBindingVersion(1L); mapping.setBindingState("ACTIVE");
                mapping.setCreateTime(new Date()); mapping.setUpdateTime(mapping.getCreateTime());
                if (mappings.insert(mapping) != 1) throw new ResidentLoginException(LOGIN_UNAVAILABLE);
                return checked(mapping, identity.appId(), identity.openId(), "ACTIVE", domain);
            }); // execute returns only after the transaction manager successfully commits.
        } catch (DuplicateKeyException race) {
            // Losing Member INSERT is rolled back too. Read the committed winner in a NEW transaction.
            // A username/id collision without a winner is rejected, never silently treated as a login.
            try { return committed(() -> checked(find(identity.appId(), identity.openId()), identity.appId(), identity.openId(), "ACTIVE", domain)); }
            catch (ResidentLoginException known) { throw known; }
            catch (RuntimeException ignored) { throw new ResidentLoginException(LOGIN_UNAVAILABLE); }
        } catch (ResidentLoginException known) { throw known; }
        catch (RuntimeException ignored) { throw new ResidentLoginException(LOGIN_UNAVAILABLE); }
    }

    private SessionIdentity committed(Supplier<SessionIdentity> operation) {
        return transaction.execute(status -> {
            try { return operation.get(); }
            // TransactionTemplate can log a callback exception during rollback: remove SQL/identity causes first.
            catch (DuplicateKeyException ignored) { throw new DuplicateKeyException("WECHAT_IDENTITY_CONFLICT"); }
            catch (ResidentLoginException known) { throw new ResidentLoginException(known.getCode()); }
            catch (RuntimeException ignored) { throw new ResidentLoginException(LOGIN_UNAVAILABLE); }
        });
    }

    public void verify(SessionIdentity identity) {
        verifyState(identity,"ACTIVE");
    }
    public void verifyPending(SessionIdentity identity) {
        if (identity == null || identity.domain() != SessionDomain.RESIDENT) throw new ResidentLoginException(IDENTITY_CONFLICT);
        verifyState(identity,"PENDING");
    }
    private void verifyState(SessionIdentity identity,String state) {
        if (identity == null) throw new ResidentLoginException(IDENTITY_CONFLICT);
        requireSchema(identity.domain());
        WechatIdentity mapping = mappings.selectById(identity.bindingId());
        if (mapping == null) throw new ResidentLoginException(IDENTITY_CONFLICT);
        SessionIdentity current = checked(mapping, identity.application(), mapping.getOpenId(), state, identity.domain());
        if (!current.equals(identity)) throw new ResidentLoginException(IDENTITY_CONFLICT);
        // Also reject duplicate/corrupt application keys if a database was provisioned without the required UNIQUE.
        if (!current.equals(checked(find(mapping.getAppId(), mapping.getOpenId()), mapping.getAppId(), mapping.getOpenId(), state, identity.domain()))) {
            throw new ResidentLoginException(IDENTITY_CONFLICT);
        }
    }

    private WechatIdentity find(String app, String open) {
        var rows = mappings.selectList(new QueryWrapper<WechatIdentity>().eq("app_id", app).eq("open_id", open).last("LIMIT 2"));
        if (rows == null || rows.size() > 1) throw new ResidentLoginException(IDENTITY_CONFLICT);
        return rows.isEmpty() ? null : rows.getFirst();
    }
    private void requireSchema(SessionDomain domain) {
        if (domain == SessionDomain.RESIDENT) properties.requireSchema();
        else if (domain == SessionDomain.APPLICANT || domain == SessionDomain.STORE) merchant.requireSchema();
        else throw new ResidentLoginException(IDENTITY_CONFLICT);
    }
    private SessionIdentity checked(WechatIdentity mapping,String app,String open,String state,SessionDomain domain) {
        if (mapping == null || !Objects.equals(app, mapping.getAppId()) || !Objects.equals(open, mapping.getOpenId())
                || open == null || !open.matches("[A-Za-z0-9_-]{1,128}")
                || !state.equals(mapping.getBindingState()) || mapping.getMemberId() == null
                || mapping.getId() == null || mapping.getBindingVersion() == null || mapping.getBindingVersion() < 1) {
            throw new ResidentLoginException(IDENTITY_CONFLICT);
        }
        Member member = members.selectById(mapping.getMemberId());
        if (member == null || !mapping.getMemberId().equals(member.getId()) || !Boolean.TRUE.equals(member.getDisabled())
                || !Boolean.FALSE.equals(member.getDeleteFlag()) || member.getUsername() == null || member.getUsername().isBlank()) {
            throw new ResidentLoginException(IDENTITY_CONFLICT);
        }
        return new SessionIdentity(member.getId(), domain, app, mapping.getId(), mapping.getBindingVersion());
    }
    private static Member minimalMember() {
        byte[] random = new byte[32]; new SecureRandom().nextBytes(random);
        String hash = new BCryptPasswordEncoder().encode(Base64.getEncoder().encodeToString(random));
        Arrays.fill(random, (byte) 0);
        Member member = new Member("wx_" + UUID.randomUUID().toString().replace("-", ""), hash, null);
        member.setId(SnowFlake.getIdStr()); member.setNickName("微信用户");
        member.setDeleteFlag(false); member.setExperience(0L);
        // grade_id is nullable in PREP's reviewed Member source. No grade/coupon/points/MQ bootstrap here.
        return member;
    }
}
