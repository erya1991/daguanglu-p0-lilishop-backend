package cn.lili.modules.member.merchant;

import cn.lili.common.security.AuthUser;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.session.SessionDomain;
import cn.lili.common.security.session.SessionIdentity;
import cn.lili.common.security.session.http.SessionApplications;
import cn.lili.modules.member.entity.dos.Clerk;
import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.member.mapper.MerchantGrantMapper;
import cn.lili.modules.member.service.ClerkService;
import cn.lili.modules.member.service.MemberService;
import cn.lili.modules.store.entity.dos.Store;
import cn.lili.modules.store.entity.enums.StoreStatusEnum;
import cn.lili.modules.store.service.StoreService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

import static cn.lili.modules.member.merchant.MerchantAccessException.Code.*;

/** Stage 0 read-only single-Store resolver. Never creates a Store, Clerk, role or account link. */
@Service
public final class MerchantAuthorizationService {
    private final SessionApplications applications;
    private final MemberService members;
    private final ClerkService clerks;
    private final StoreService stores;
    private final MerchantGrantMapper grants;
    private final ObjectProvider<MerchantOwnershipProof> proofs;
    private MerchantLifecyclePolicy lifecycle;

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setLifecycle(MerchantLifecyclePolicy lifecycle) { this.lifecycle=lifecycle; }

    public MerchantAuthorizationService(SessionApplications applications, MemberService members,
            ClerkService clerks, StoreService stores, MerchantGrantMapper grants,
            ObjectProvider<MerchantOwnershipProof> proofs) {
        this.applications=applications; this.members=members; this.clerks=clerks;
        this.stores=stores; this.grants=grants; this.proofs=proofs;
    }

    /** Input is a trusted mapping, not a request identity choice; output domain is decided here. */
    public Context resolve(SessionIdentity identity) {
        try { return checked(identity); }
        catch (MerchantAccessException known) { throw new MerchantAccessException(known.getCode()); }
        catch (RuntimeException ignored) { throw new MerchantAccessException(DATA_UNAVAILABLE); }
    }

    private Context checked(SessionIdentity identity) {
        require(identity!=null && (identity.domain()==SessionDomain.APPLICANT || identity.domain()==SessionDomain.STORE)
                && applications.application(SessionDomain.STORE).equals(identity.application()), DENIED);
        Member member=members.getById(identity.subject());
        require(member!=null && identity.subject().equals(member.getId()) && text(member.getUsername())
                && Boolean.TRUE.equals(member.getDisabled()) && Boolean.FALSE.equals(member.getDeleteFlag()), DENIED);
        List<Clerk> relations=clerks.list(new QueryWrapper<Clerk>().eq("member_id",member.getId()));
        require(relations!=null, DATA_UNAVAILABLE);
        List<Store> owned=stores.list(new QueryWrapper<Store>().eq("member_id",member.getId()));
        require(owned!=null,DATA_UNAVAILABLE);
        require(owned.size()<=1,RELATION_CONFLICT);
        if(relations.isEmpty()) {
            // A dangling known Store/owner relation is not evidence of being a normal no-store Applicant.
            require((owned.isEmpty() || lifecycle!=null && lifecycle.pendingApplicant(identity,member,owned))
                    && !Boolean.TRUE.equals(member.getHaveStore()) && !text(member.getStoreId()),RELATION_CONFLICT);
            return new Context(SessionDomain.APPLICANT,member.getId(),null,null,null,Role.APPLICANT,null);
        }
        require(relations.size()==1,RELATION_CONFLICT);
        Clerk clerk=relations.getFirst();
        if(clerk!=null&&owned.isEmpty()&&lifecycle!=null&&lifecycle.revokedApplicant(identity,member,clerk))
            return new Context(SessionDomain.APPLICANT,member.getId(),null,null,null,Role.APPLICANT,null);
        require(clerk!=null && text(clerk.getId()) && text(clerk.getStoreId())
                && member.getId().equals(clerk.getMemberId()) && Boolean.TRUE.equals(clerk.getStatus())
                && Boolean.FALSE.equals(clerk.getDeleteFlag()) && clerk.getShopkeeper()!=null,RELATION_CONFLICT);
        Store store=stores.getById(clerk.getStoreId());
        require(store!=null && clerk.getStoreId().equals(store.getId()) && text(store.getMemberId())
                && Boolean.FALSE.equals(store.getDeleteFlag()) && (StoreStatusEnum.OPEN.name().equals(store.getStoreDisable())
                    || lifecycle!=null && lifecycle.restrictedStore(store))
                && (store.getStoreEndTime()==null || store.getStoreEndTime().getTime()>System.currentTimeMillis()),RELATION_CONFLICT);
        boolean owner=Boolean.TRUE.equals(clerk.getShopkeeper());
        require(owner==member.getId().equals(store.getMemberId()),RELATION_CONFLICT);
        require(owner ? owned.size()==1 && owned.getFirst()!=null && store.getId().equals(owned.getFirst().getId())
                : owned.isEmpty(),RELATION_CONFLICT);
        if(!owner) {
            List<MerchantGrantMapper.Grant> current=grants.current(clerk.getId(),store.getId(),clerk.getDepartmentId());
            require(current!=null,DATA_UNAVAILABLE);
            require(!current.isEmpty(),DENIED);
            for(var grant:current) require(grant!=null && store.getId().equals(grant.getStoreId())
                    && text(grant.getRoleId()) && text(grant.getMenuId()) && text(grant.getPermission())
                    && grant.getWriteGrant()!=null,DENIED);
        }
        requireProof(identity);
        // Stage 0 gives only its explicit identity/read routes, even if a legacy Clerk has is_super=true.
        AuthUser user=AuthUser.builder().id(member.getId()).username(member.getUsername()).nickName(member.getNickName())
                .role(UserEnums.STORE).storeId(store.getId()).storeName(store.getStoreName()).clerkId(clerk.getId())
                .isSuper(false).build();
        return new Context(SessionDomain.STORE,member.getId(),clerk.getId(),store.getId(),store.getStoreName(),
                owner?Role.OWNER:Role.STAFF,user);
    }

    private void requireProof(SessionIdentity identity) {
        MerchantOwnershipProof provider=proofs.getIfUnique();
        if(provider==null) throw new MerchantAccessException(PROOF_SOURCE_PENDING);
        MerchantOwnershipProof.Verified proof=provider.current(identity);
        require(proof!=null && Objects.equals(identity.application(),proof.application())
                && Objects.equals(identity.bindingId(),proof.bindingId())
                && Objects.equals(identity.bindingVersion(),proof.bindingVersion())
                && Objects.equals(identity.subject(),proof.memberId()) && text(proof.auditReference())
                && proof.kind()!=null,PROOF_SOURCE_PENDING);
    }
    private static boolean text(String value) {return value!=null && !value.isBlank();}
    private static void require(boolean value,MerchantAccessException.Code code) {if(!value)throw new MerchantAccessException(code);}
    public enum Role { APPLICANT, OWNER, STAFF }
    public record Context(SessionDomain domain,String memberId,String clerkId,String storeId,String storeName,
                          Role role,AuthUser authUser) {
        @Override public String toString(){return "MerchantContext[REDACTED]";}
    }
}
