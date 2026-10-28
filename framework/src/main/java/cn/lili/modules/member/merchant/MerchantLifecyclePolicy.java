package cn.lili.modules.member.merchant;

import cn.lili.common.security.session.SessionIdentity;
import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.store.entity.dos.Store;
import java.util.List;

/** Exact, persisted lifecycle evidence; unknown historical relations remain rejected. */
public interface MerchantLifecyclePolicy {
    boolean pendingApplicant(SessionIdentity identity, Member member, List<Store> owned);
    boolean restrictedStore(Store store);
    default boolean revokedApplicant(SessionIdentity identity,Member member,cn.lili.modules.member.entity.dos.Clerk clerk){return false;}
}
