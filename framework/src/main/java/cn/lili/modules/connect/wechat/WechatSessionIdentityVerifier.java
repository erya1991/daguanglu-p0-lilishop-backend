package cn.lili.modules.connect.wechat;

import cn.lili.common.security.session.*;
import cn.lili.common.security.session.http.SessionApplications;
import cn.lili.common.security.session.http.SessionIdentityVerifier;
import org.springframework.stereotype.Component;

/** Single shared production verifier. P05 must extend this dispatcher, not register competing beans. */
@Component
public final class WechatSessionIdentityVerifier implements SessionIdentityVerifier {
    private final WechatIdentityService identities;
    private final SessionApplications applications;
    private MerchantWechatProperties merchant;
    private cn.lili.modules.connect.mapper.MemberIdentityLinkMapper links;
    private cn.lili.modules.member.profile.ResidentProfileProperties profile;
    @org.springframework.beans.factory.annotation.Autowired
    public WechatSessionIdentityVerifier(WechatIdentityService identities,SessionApplications applications,
            cn.lili.modules.connect.mapper.MemberIdentityLinkMapper links,cn.lili.modules.member.profile.ResidentProfileProperties profile,
            MerchantWechatProperties merchant) {
        this(identities,applications,links,profile); this.merchant=merchant;
    }
    public WechatSessionIdentityVerifier(WechatIdentityService identities,SessionApplications applications,
            cn.lili.modules.connect.mapper.MemberIdentityLinkMapper links,cn.lili.modules.member.profile.ResidentProfileProperties profile) {
        this(identities,applications);this.links=links;this.profile=profile;
    }
    public WechatSessionIdentityVerifier(WechatIdentityService identities, SessionApplications applications) {
        this.identities = identities; this.applications = applications;
    }
    @Override public void check(SessionIdentity identity) {
        if (identity == null || (identity.domain() != SessionDomain.RESIDENT
                && identity.domain() != SessionDomain.APPLICANT && identity.domain() != SessionDomain.STORE)
                || (identity.domain() != SessionDomain.RESIDENT && merchant == null)) {
            throw new SessionException(SessionException.Code.AUTHORIZATION_REQUIRED);
        }
        try {
            if (identity.domain() != SessionDomain.RESIDENT) merchant.requireSchema();
            if (!applications.application(identity.domain()).equals(identity.application())) {
                throw new SessionException(SessionException.Code.DENIED);
            }
            identities.verify(identity);
        } catch (SessionException known) { throw new SessionException(known.getCode()); }
        catch (RuntimeException ignored) { throw new SessionException(SessionException.Code.DENIED); }
    }
    @Override public void checkHandoff(SessionHandoff handoff) {
        try {
            if(links==null || profile==null || handoff==null) throw new IllegalStateException();
            profile.requireHistory();
            if(!applications.application(SessionDomain.RESIDENT).equals(handoff.target().application())) throw new IllegalStateException();
            identities.verifyPending(handoff.target());
            var row=MemberLinkService.one(links.request(handoff.target().bindingId(),handoff.requestId()),true);
            if(!"PENDING".equals(row.getState()) || !MemberLinkService.handoff(handoff.target().application(),row).equals(handoff)
                    || !MemberLinkService.CONSENT_VERSION.equals(row.getConsentVersion()) || row.getConsentTime()==null
                    || row.getProofReference()==null || row.getProofReference().isBlank() || row.getProofKind()==null) throw new IllegalStateException();
            MemberOwnershipProof.Kind.valueOf(row.getProofKind());
        } catch(RuntimeException e) {throw new SessionException(SessionException.Code.DENIED);}
    }
}
