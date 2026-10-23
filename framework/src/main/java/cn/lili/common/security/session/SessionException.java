package cn.lili.common.security.session;

/** Deliberately excludes credentials, identity details and upstream exception causes. */
public final class SessionException extends RuntimeException {
    public enum Code { INVALID_CONTEXT, INVALID_CREDENTIAL, AUTHORIZATION_REQUIRED, DENIED, STORE_UNAVAILABLE }

    private final Code code;

    public SessionException(Code code) {
        super("SESSION_" + code.name());
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
