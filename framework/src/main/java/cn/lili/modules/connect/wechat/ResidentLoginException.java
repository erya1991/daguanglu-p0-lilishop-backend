package cn.lili.modules.connect.wechat;

/** Safe public codes only: never retain gateway/SQL causes or request values. */
public final class ResidentLoginException extends RuntimeException {
    public enum Code { INVALID_REQUEST, PHONE_NOT_SUPPORTED, CONFIG_REQUIRED, B04_PENDING,
        WECHAT_REJECTED, WECHAT_UNAVAILABLE, IDENTITY_CONFLICT, LOGIN_UNAVAILABLE }
    private final Code code;
    public ResidentLoginException(Code code) { super(code.name()); this.code = code; }
    public Code getCode() { return code; }
}
