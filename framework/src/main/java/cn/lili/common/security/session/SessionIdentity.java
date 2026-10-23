package cn.lili.common.security.session;

import static cn.lili.common.security.session.SessionException.Code.INVALID_CONTEXT;

/**
 * Server-established identity, never a request DTO. The caller must obtain it from
 * trusted authentication; SessionAuthorization must also approve its current state.
 * bindingId identifies the application mapping, not a client-selected Member/Store.
 */
public record SessionIdentity(String subject, SessionDomain domain, String application,
                              String bindingId, Long bindingVersion) {
    public SessionIdentity {
        if (domain == null || !validText(subject) || !validText(application)
                || (bindingId == null) != (bindingVersion == null)
                || (bindingId != null && (!validText(bindingId) || bindingVersion < 1))
                || (domain != SessionDomain.MANAGER && bindingId == null)) {
            throw new SessionException(INVALID_CONTEXT);
        }
    }

    private static boolean validText(String value) {
        return value != null && !value.isBlank() && value.length() <= 128
                && value.equals(value.trim()) && value.chars().noneMatch(Character::isISOControl);
    }

    @Override
    public String toString() {
        return "SessionIdentity[REDACTED]";
    }
}
