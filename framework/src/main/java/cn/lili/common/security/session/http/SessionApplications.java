package cn.lili.common.security.session.http;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.common.security.session.SessionException;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Explicit server configuration. No placeholder AppID or client-header fallback. */
@Component
@ConfigurationProperties(prefix = "lili.stage0-session")
public class SessionApplications {
    private String residentApplication;
    private String merchantApplication;
    private String managerApplication;

    public String application(SessionDomain domain) {
        String value = switch (domain) {
            case RESIDENT -> residentApplication;
            case APPLICANT, STORE -> merchantApplication;
            case MANAGER -> managerApplication;
        };
        if (value == null || value.isBlank() || value.length() > 128 || !value.equals(value.trim())
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new SessionException(SessionException.Code.AUTHORIZATION_REQUIRED);
        }
        if (domain != SessionDomain.MANAGER && value.equals(
                domain == SessionDomain.RESIDENT ? merchantApplication : residentApplication)) {
            throw new SessionException(SessionException.Code.INVALID_CONTEXT);
        }
        return value;
    }

    public String getResidentApplication() { return residentApplication; }
    public void setResidentApplication(String value) { residentApplication = value; }
    public String getMerchantApplication() { return merchantApplication; }
    public void setMerchantApplication(String value) { merchantApplication = value; }
    public String getManagerApplication() { return managerApplication; }
    public void setManagerApplication(String value) { managerApplication = value; }
}
