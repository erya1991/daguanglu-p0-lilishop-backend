package cn.lili.modules.connect.wechat;

import cn.lili.common.security.session.*;
import cn.lili.common.security.session.http.SessionApplications;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static cn.lili.modules.connect.wechat.ResidentLoginException.Code.*;

@Service
public final class ResidentWechatLoginService {
    private final WechatCodeGateway gateway;
    private final WechatIdentityService identities;
    private final SessionService sessions;
    private final SessionApplications applications;
    private final ResidentWechatProperties properties;
    private org.springframework.beans.factory.ObjectProvider<WechatLinkRecovery> recovery;

    @org.springframework.beans.factory.annotation.Autowired
    public ResidentWechatLoginService(WechatCodeGateway gateway, WechatIdentityService identities,
            SessionService sessions, SessionApplications applications, ResidentWechatProperties properties,
            org.springframework.beans.factory.ObjectProvider<WechatLinkRecovery> recovery) {
        this(gateway,identities,sessions,applications,properties);
        this.recovery=recovery;
    }

    public ResidentWechatLoginService(WechatCodeGateway gateway, WechatIdentityService identities,
                                      SessionService sessions, SessionApplications applications, ResidentWechatProperties properties) {
        this.gateway = gateway; this.identities = identities; this.sessions = sessions;
        this.applications = applications; this.properties = properties;
    }
    public LoginResult login(String loginCode) {
        requireCode(loginCode);
        properties.requireSecret();
        if (TransactionSynchronizationManager.isActualTransactionActive()) throw new ResidentLoginException(LOGIN_UNAVAILABLE);
        try {
            String app = applications.application(SessionDomain.RESIDENT);
            var authenticated = gateway.authenticate(loginCode);
            if (authenticated == null || !app.equals(authenticated.appId())) throw new ResidentLoginException(WECHAT_REJECTED);
            var handler=recovery==null?null:recovery.getIfUnique();
            if(handler!=null) {
                var recovered=handler.recover(authenticated);
                if(recovered!=null) return recovered;
            }
            var identity = identities.resolve(authenticated);
            if (identity == null || identity.domain() != SessionDomain.RESIDENT || !app.equals(identity.application())) {
                throw new ResidentLoginException(IDENTITY_CONFLICT);
            }
            var tokens = sessions.create(identity); // Only after successful DB commit; authorization re-reads mapping.
            return new LoginResult(identity.subject(), "RESIDENT", tokens);
        } catch (ResidentLoginException known) { throw known; }
        catch (SessionException known) { throw new SessionException(known.getCode()); }
        catch (RuntimeException ignored) { throw new ResidentLoginException(LOGIN_UNAVAILABLE); }
    }
    public static void requireCode(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_-]{1,512}")) throw new ResidentLoginException(INVALID_REQUEST);
    }
    public record LoginResult(String memberId, String identity, SessionTokens session) {
        @Override public String toString() { return "ResidentLoginResult[REDACTED]"; }
    }
}
