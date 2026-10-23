package cn.lili.modules.connect.wechat;

import cn.lili.common.security.session.SessionIdentity;

/** NO production success provider until an approved, independently verified source exists.
 * Implementations must reject default/weak/mobile-reset/unknown-provenance credentials.
 * targetMemberId is resolved server-side, never copied from the linking request. */
public interface MemberOwnershipProof {
    Verified verify(SessionIdentity source,String proofReference,String requestId);
    enum Kind { QUALIFIED_ACCOUNT_CREDENTIAL, APPROVED_EVIDENCE }
    record Verified(String targetMemberId,String auditReference,Kind kind,long expiresAt) {
        @Override public String toString() {return "OwnershipProof[REDACTED]";}
    }
}
