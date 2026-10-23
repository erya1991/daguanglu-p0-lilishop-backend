package cn.lili.modules.connect.wechat;

import cn.lili.common.security.session.*;
import cn.lili.common.utils.SnowFlake;
import cn.lili.modules.connect.entity.*;
import cn.lili.modules.connect.mapper.*;
import cn.lili.modules.member.mapper.ResidentProfileMapper;
import cn.lili.modules.member.profile.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import java.util.*;
import static cn.lili.modules.member.profile.ResidentProfileException.Code.*;

@Service
public class MemberLinkService implements WechatLinkRecovery {
    public static final String CONSENT_VERSION="member-link-v1";
    private final MemberIdentityLinkMapper links;
    private final WechatIdentityMapper mappings;
    private final WechatIdentityService identities;
    private final ResidentProfileMapper members;
    private final ResidentProfileProperties properties;
    private final ResidentTransactions transactions;
    private final ObjectProvider<MemberOwnershipProof> proofs;
    private final SessionService sessions;
    public MemberLinkService(MemberIdentityLinkMapper links,WechatIdentityMapper mappings,WechatIdentityService identities,
            ResidentProfileMapper members,ResidentProfileProperties properties,ResidentTransactions transactions,
            ObjectProvider<MemberOwnershipProof> proofs,SessionService sessions) {
        this.links=links;this.mappings=mappings;this.identities=identities;this.members=members;this.properties=properties;
        this.transactions=transactions;this.proofs=proofs;this.sessions=sessions;
    }
    public Result link(SessionService.VerifiedSession current,String requestId,String proofReference,String consentVersion,boolean confirmed) {
        properties.requireHistory(); ResidentProfileService.requestId(requestId);
        if(current==null || current.identity().domain()!=SessionDomain.RESIDENT) throw new ResidentProfileException(SESSION_REQUIRED);
        if(!confirmed || !CONSENT_VERSION.equals(consentVersion)) throw new ResidentProfileException(CONSENT_REQUIRED);
        var source=current.identity();
        var existing=transactions.run(() -> {
            links.lockMapping(source.bindingId());
            var previous=one(links.request(source.bindingId(),requestId),false);
            if(previous!=null && !previous.getSourceMemberId().equals(source.subject()) && !previous.getTargetMemberId().equals(source.subject())) {
                throw new ResidentProfileException(REQUEST_CONFLICT);
            }
            return previous;
        });
        if(existing!=null) return new Result(existing.getState(),"WECHAT_RELOGIN",null); // Never replay a B bearer to an old A request.
        if(proofReference==null || !proofReference.matches("[A-Za-z0-9_-]{16,128}")) throw new ResidentProfileException(PROOF_REJECTED);
        var provider=proofs.getIfUnique(); if(provider==null) throw new ResidentProfileException(PROOF_SOURCE_PENDING);
        MemberOwnershipProof.Verified proof;
        try { identities.verify(source); proof=provider.verify(source,proofReference,requestId); }
        catch(RuntimeException e) {throw new ResidentProfileException(PROOF_REJECTED);}
        if(proof==null || proof.kind()==null || proof.targetMemberId()==null || !proof.targetMemberId().matches("[0-9]{1,19}")
                || proof.targetMemberId().equals(source.subject()) || proof.auditReference()==null
                || !proof.auditReference().matches("[A-Za-z0-9_-]{1,128}") || proof.expiresAt()<=System.currentTimeMillis()) throw new ResidentProfileException(PROOF_REJECTED);
        String linkId=transactions.run(() -> {
            var mapping=links.lockMapping(source.bindingId());
            var previous=one(links.request(source.bindingId(),requestId),false);
            if(previous!=null) throw new ResidentProfileException(LINK_PENDING);
            if(proof.expiresAt()<=System.currentTimeMillis()) throw new ResidentProfileException(PROOF_REJECTED);
            identities.verify(source);
            if(mapping==null || !source.subject().equals(mapping.getMemberId()) || !Objects.equals(source.bindingVersion(),mapping.getBindingVersion())) throw new ResidentProfileException(IDENTITY_UNAVAILABLE);
            // Stable lock order; no order/store/asset migration. Both histories must be readable.
            var ids=new ArrayList<>(List.of(source.subject(),proof.targetMemberId())); ids.sort(Comparator.comparingLong(Long::parseLong));
            for(String id:ids) ResidentProfileService.valid(members.lock(id));
            Long a=links.history(source.subject()), b=links.history(proof.targetMemberId());
            if(a==null || b==null || a<0 || b<0) throw new ResidentProfileException(HISTORY_PENDING);
            if(a>0) throw new ResidentProfileException(HISTORY_CONFLICT);
            if(source.bindingVersion()>=Long.MAX_VALUE-1) throw new ResidentProfileException(IDENTITY_UNAVAILABLE);
            var row=new MemberIdentityLink(); row.setId(SnowFlake.getIdStr()); row.setMappingId(source.bindingId()); row.setRequestId(requestId);
            row.setSourceMemberId(source.subject());row.setTargetMemberId(proof.targetMemberId());
            row.setBeforeVersion(source.bindingVersion());row.setAfterVersion(source.bindingVersion()+1);
            row.setProofReference(proof.auditReference());row.setProofKind(proof.kind().name()); row.setConsentVersion(CONSENT_VERSION);
            row.setConsentTime(new Date());row.setSourceSid(current.sid());row.setState("PENDING");row.setCreateTime(new Date());row.setUpdateTime(new Date());
            if(links.insert(row)!=1 || links.switchMapping(source.bindingId(),source.subject(),proof.targetMemberId(),source.bindingVersion())!=1) throw new ResidentProfileException(LINK_PENDING);
            return row.getId();
        });
        return complete(source.application(),source.bindingId(),linkId);
    }
    private Result complete(String app,String mappingId,String linkId) {
        try { return transactions.run(() -> {
            var mapping=links.lockMapping(mappingId); var row=links.selectById(linkId);
            if(row==null || mapping==null || !app.equals(mapping.getAppId()) || !mappingId.equals(row.getMappingId())) throw new ResidentProfileException(LINK_PENDING);
            if("DONE".equals(row.getState())) return new Result("DONE","WECHAT_RELOGIN",null);
            SessionHandoff handoff=handoff(app,row);
            var tokens=sessions.handoff(handoff); // Only after first DB transaction committed PENDING/version fence.
            if(links.activate(mappingId,row.getTargetMemberId(),row.getAfterVersion())!=1 || links.done(row.getId(),tokens.sid())!=1) throw new ResidentProfileException(LINK_PENDING);
            return new Result("DONE","REPLACE_SESSION",tokens);
        }); } catch(RuntimeException e) {throw new ResidentProfileException(LINK_PENDING);}
    }
    @Override public ResidentWechatLoginService.LoginResult recover(WechatCodeGateway.Identity authenticated) {
        // P03-1 still works while R02 schema is not deployed. Its verifier rejects any PENDING mapping.
        if(!properties.isSchemaReady()) return null;
        MemberIdentityLink pending=transactions.run(() -> {
            var rows=mappings.selectList(new QueryWrapper<WechatIdentity>().eq("app_id",authenticated.appId()).eq("open_id",authenticated.openId()).last("LIMIT 2"));
            if(rows==null || rows.size()>1) throw new ResidentProfileException(IDENTITY_UNAVAILABLE);
            if(rows.isEmpty() || "ACTIVE".equals(rows.getFirst().getBindingState())) return null;
            var m=rows.getFirst();
            if(!authenticated.appId().equals(m.getAppId()) || !authenticated.openId().equals(m.getOpenId()) || !"PENDING".equals(m.getBindingState())) throw new ResidentProfileException(LINK_PENDING);
            var row=one(links.pending(m.getId()),true);
            return row;
        });
        if(pending==null) return null;
        var result=complete(authenticated.appId(),pending.getMappingId(),pending.getId());
        if(result.session()==null) return null; // A concurrent recovery completed; normal fresh login re-reads B.
        return new ResidentWechatLoginService.LoginResult(pending.getTargetMemberId(),"RESIDENT",result.session());
    }
    public static SessionHandoff handoff(String app,MemberIdentityLink row) {
        return new SessionHandoff(new SessionIdentity(row.getSourceMemberId(),SessionDomain.RESIDENT,app,row.getMappingId(),row.getBeforeVersion()),
                new SessionIdentity(row.getTargetMemberId(),SessionDomain.RESIDENT,app,row.getMappingId(),row.getAfterVersion()),row.getSourceSid(),row.getRequestId());
    }
    public static MemberIdentityLink one(List<MemberIdentityLink> rows,boolean required) {
        if(rows==null || rows.size()>1 || (required && rows.isEmpty())) throw new ResidentProfileException(LINK_PENDING);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public record Result(String state,String nextAction,SessionTokens session) { @Override public String toString(){return "MemberLinkResult[REDACTED]";} }
}
