package cn.lili.common.security.session.http;

import cn.lili.common.security.session.SessionIdentity;

/**
 * P03/P05 must verify the persisted application mapping, subject, binding version and
 * non-PENDING handoff. STORE additionally requires the approved independent merchant
 * ownership proof. No production implementation is invented in P02-2.
 */
@FunctionalInterface
public interface SessionIdentityVerifier {
    void check(SessionIdentity identity);
}
