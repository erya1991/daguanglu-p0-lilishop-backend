package cn.lili.modules.connect.wechat;

import cn.lili.common.security.session.*;
import cn.lili.common.security.session.http.SessionApplications;
import cn.lili.modules.member.merchant.MerchantAccessException;
import cn.lili.modules.member.merchant.MerchantAuthorizationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static cn.lili.modules.connect.wechat.ResidentLoginException.Code.*;

/** One official Code gateway, one Member/mapping service, one Session core. No phone/UnionID claiming. */
@Service
public final class MerchantWechatLoginService {
    private final WechatCodeGateway gateway;
    private final WechatIdentityService identities;
    private final SessionService sessions;
    private final SessionApplications applications;
    private final MerchantWechatProperties properties;
    private final MerchantAuthorizationService grants;

    public MerchantWechatLoginService(WechatCodeGateway gateway, WechatIdentityService identities,
            SessionService sessions, SessionApplications applications, MerchantWechatProperties properties,
            MerchantAuthorizationService grants) {
        this.gateway = gateway; this.identities = identities; this.sessions = sessions;
        this.applications = applications; this.properties = properties; this.grants = grants;
    }

    public LoginResult login(String loginCode) {
        ResidentWechatLoginService.requireCode(loginCode);
        properties.requireSecret();
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new ResidentLoginException(LOGIN_UNAVAILABLE);
        try {
            String app = applications.application(SessionDomain.APPLICANT);
            var authenticated = gateway.authenticate(loginCode, SessionDomain.APPLICANT);
            if (authenticated == null || !app.equals(authenticated.appId())) throw new ResidentLoginException(WECHAT_REJECTED);
            var personal = identities.resolve(authenticated, SessionDomain.APPLICANT);
            if (personal == null || personal.domain() != SessionDomain.APPLICANT || !app.equals(personal.application())) {
                throw new ResidentLoginException(IDENTITY_CONFLICT);
            }
            // A missing/failed Clerk query is NOT an empty relationship. Proof failures never downgrade to Applicant.
            var context = grants.resolve(personal);
            if (context == null || !personal.subject().equals(context.memberId())
                    || (context.domain() != SessionDomain.APPLICANT && context.domain() != SessionDomain.STORE)) {
                throw new ResidentLoginException(IDENTITY_CONFLICT);
            }
            var identity = new SessionIdentity(personal.subject(), context.domain(), app,
                    personal.bindingId(), personal.bindingVersion());
            // DB mapping is committed; SessionAuthorization independently rechecks mapping, proof and current grants.
            var tokens = sessions.create(identity);
            return new LoginResult(identity.subject(), identity.domain().name(),
                    context.domain() == SessionDomain.APPLICANT ? null : context.role().name(), tokens);
        } catch (ResidentLoginException known) { throw new ResidentLoginException(known.getCode()); }
        catch (MerchantAccessException known) { throw new MerchantAccessException(known.getCode()); }
        catch (SessionException known) { throw new SessionException(known.getCode()); }
        catch (RuntimeException ignored) { throw new ResidentLoginException(LOGIN_UNAVAILABLE); }
    }

    public record LoginResult(String memberId, String identity, String role, SessionTokens session) {
        @Override public String toString() { return "MerchantLoginResult[REDACTED]"; }
    }
}
