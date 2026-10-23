package cn.lili.common.security.session;

/**
 * Required server-side integration point, called on creation, Access and Refresh.
 * Implementations must validate the trusted application/domain, current account and
 * mapping version, and applicable Clerk/Store/Manager grants; reject pending handoffs.
 * A client claim or merely finding an enabled Member is not authentication evidence.
 * P02-1 supplies no production implementation and has no allow-by-default fallback.
 */
@FunctionalInterface
public interface SessionAuthorization {
    void check(SessionIdentity identity);
}
