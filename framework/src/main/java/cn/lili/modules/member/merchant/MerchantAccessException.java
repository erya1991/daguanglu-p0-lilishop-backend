package cn.lili.modules.member.merchant;

/** Public, non-identifying codes only. Never retain SQL, credentials or ownership evidence. */
public final class MerchantAccessException extends RuntimeException {
    public enum Code { DATA_UNAVAILABLE, RELATION_CONFLICT, PROOF_SOURCE_PENDING, DENIED }
    private final Code code;
    public MerchantAccessException(Code code) { super(code.name()); this.code = code; }
    public Code getCode() { return code; }
}
