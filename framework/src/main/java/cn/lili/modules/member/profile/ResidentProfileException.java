package cn.lili.modules.member.profile;

/** Public codes only; never retain SQL, phone, proof or upstream credential diagnostics. */
public final class ResidentProfileException extends RuntimeException {
    public enum Code { INVALID_REQUEST, SESSION_REQUIRED, PROFILE_CONFLICT, IDENTITY_UNAVAILABLE,
        B04_PENDING, CONTENT_POLICY_PENDING, NICKNAME_REJECTED, AVATAR_REJECTED, STORAGE_PENDING,
        PHONE_PENDING, PHONE_REJECTED, PHONE_UNAVAILABLE, LINK_REQUIRED, REQUEST_CONFLICT,
        PROOF_SOURCE_PENDING, PROOF_REJECTED, CONSENT_REQUIRED, HISTORY_PENDING, HISTORY_CONFLICT,
        LINK_PENDING, OPERATION_UNAVAILABLE }
    private final Code code;
    public ResidentProfileException(Code code) { super(code.name()); this.code = code; }
    public Code getCode() { return code; }
}
