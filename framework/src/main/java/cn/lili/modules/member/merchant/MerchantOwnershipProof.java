package cn.lili.modules.member.merchant;

import cn.lili.common.security.session.SessionIdentity;

/**
 * A previously approved independent-account proof, bound to the exact committed mapping.
 * There is deliberately NO production success provider in P05. Phone equality, old weak/default
 * passwords, a mobile-reset password, UnionID and client Store/role selectors are not proof.
 */
@FunctionalInterface
public interface MerchantOwnershipProof {
    Verified current(SessionIdentity identity);
    enum Kind { QUALIFIED_ACCOUNT_CREDENTIAL, APPROVED_EVIDENCE }
    record Verified(String application, String bindingId, long bindingVersion, String memberId,
                    String auditReference, Kind kind) {
        @Override public String toString() { return "MerchantOwnershipProof[REDACTED]"; }
    }
}
