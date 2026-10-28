package cn.lili.modules.permission.manager;

import cn.lili.common.security.session.*;
import cn.lili.common.security.session.http.SessionApplications;
import cn.lili.modules.permission.service.AdminUserService;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.security.token.Token;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** CAPTCHA remains in the existing Controller. No legacy token is issued on this path. */
@Service
@RequiredArgsConstructor
public class ManagerSessionLogin {
    private final AdminUserService admins;
    private final ManagerAccessPolicy policy;
    private final SessionApplications applications;
    private final SessionService sessions;
    private cn.lili.common.security.session.http.SessionAccessExtension extension;

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public void setExtension(cn.lili.common.security.session.http.SessionAccessExtension extension) { this.extension=extension; }

    public boolean enabled() { return policy.enabled() || extension!=null; }

    /** Unrequested historical protocol remains available only to non-A/B accounts. */
    public Token legacyLogin(String username, String password) {
        var admin = admins.authenticate(username, password);
        if (admin == null) throw new SessionException(SessionException.Code.DENIED);
        if (extension!=null && extension.ownsManager(admin.getId())
                || !Boolean.TRUE.equals(admin.getIsSuper()) && policy.isConfiguredRoleMember(admin.getId()))
            throw new ServiceException("MANAGER_SESSION_PROTOCOL_REQUIRED");
        return admins.login(username, password); // One legacy issuance; authenticate never issues credentials.
    }

    public ManagerSessionResponse login(String username, String password) {
        if (!enabled()) throw new SessionException(SessionException.Code.AUTHORIZATION_REQUIRED);
        String application = applications.application(SessionDomain.MANAGER);
        var admin = admins.authenticate(username, password);
        if (admin == null) throw new SessionException(SessionException.Code.DENIED);
        if(extension!=null && extension.ownsManager(admin.getId())) extension.checkManager(admin.getId());
        else {
            var current = policy.current(admin.getId());
            if (!admin.getId().equals(current.admin().getId())) throw new SessionException(SessionException.Code.DENIED);
        }
        var identity = new SessionIdentity(admin.getId(), SessionDomain.MANAGER, application, null, null);
        return ManagerSessionResponse.from(sessions.create(identity));
    }
}
