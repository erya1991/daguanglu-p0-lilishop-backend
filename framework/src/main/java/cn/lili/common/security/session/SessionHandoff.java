package cn.lili.common.security.session;

/** Persisted, independently proven Resident mapping transition; not a client credential. */
public record SessionHandoff(SessionIdentity source,SessionIdentity target,String sourceSid,String requestId) {
    @Override public String toString() {return "SessionHandoff[REDACTED]";}
}
