package cn.lili.common.security.session.http;

import cn.lili.common.security.AuthUser;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.session.*;
import cn.lili.modules.member.entity.dos.Clerk;
import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.member.service.ClerkService;
import cn.lili.modules.member.service.MemberService;
import cn.lili.modules.member.service.StoreMenuService;
import cn.lili.modules.member.token.StoreTokenGenerate;
import cn.lili.modules.permission.entity.dos.AdminUser;
import cn.lili.modules.permission.service.AdminUserService;
import cn.lili.modules.permission.service.MenuService;
import cn.lili.modules.store.entity.dos.Store;
import cn.lili.modules.store.entity.enums.StoreStatusEnum;
import cn.lili.modules.store.service.StoreService;
import cn.lili.modules.system.token.ManagerTokenGenerate;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.util.PatternMatchUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cn.lili.common.security.session.SessionException.Code.*;

/** Fresh identity/grant reads; does not trust JWT super flags or cached permission lists. */
@Component
@RequiredArgsConstructor
public class CurrentSessionAuthorization implements SessionAuthorization {
    private final SessionApplications applications;
    private final ObjectProvider<SessionIdentityVerifier> verifiers;
    private final MemberService members;
    private final ClerkService clerks;
    private final StoreService stores;
    private final AdminUserService admins;
    private final MenuService menus;
    private final StoreMenuService storeMenus;
    private final ManagerTokenGenerate managerTokens;
    private final StoreTokenGenerate storeTokens;

    @Override
    public void check(SessionIdentity identity) { current(identity); }

    @Override public void checkHandoff(SessionHandoff handoff) {
        require(handoff!=null && handoff.target().domain()==SessionDomain.RESIDENT
                && applications.application(SessionDomain.RESIDENT).equals(handoff.target().application()));
        var verifier=verifiers.getIfUnique();
        if(verifier==null) throw new SessionException(AUTHORIZATION_REQUIRED);
        verifier.checkHandoff(handoff);
        member(handoff.target().subject());
    }

    public AuthUser current(SessionIdentity identity) {
        require(applications.application(identity.domain()).equals(identity.application()));
        if (identity.domain() == SessionDomain.MANAGER) {
            require(identity.bindingId() == null && identity.bindingVersion() == null);
            return manager(identity.subject());
        }
        SessionIdentityVerifier verifier = verifiers.getIfUnique();
        if (verifier == null) throw new SessionException(AUTHORIZATION_REQUIRED);
        verifier.check(identity);
        Member member = member(identity.subject());
        if (identity.domain() == SessionDomain.APPLICANT) return null; // Never an ordinary AuthUser/STORE.
        return identity.domain() == SessionDomain.STORE ? store(member) : resident(member);
    }

    public AuthUser legacy(AuthUser tokenUser) {
        require(tokenUser != null && tokenUser.getRole() != null);
        AuthUser current = switch (tokenUser.getRole()) {
            case MANAGER -> manager(tokenUser.getId());
            case MEMBER -> resident(member(tokenUser.getId()));
            case STORE -> store(member(tokenUser.getId()));
            default -> throw new SessionException(DENIED);
        };
        if (tokenUser.getRole() == UserEnums.STORE) {
            require(Objects.equals(current.getStoreId(), tokenUser.getStoreId())
                    && Objects.equals(current.getClerkId(), tokenUser.getClerkId()));
        }
        return current;
    }

    public Map<String, List<String>> permissions(AuthUser user) {
        if (user.getRole() == UserEnums.MANAGER) {
            var current = menus.findAllMenu(user.getId());
            require(current != null);
            return managerTokens.permissionList(current);
        }
        // Bypass StoreMenuRoleService.findAllMenu: it reads the legacy member-scoped menu cache.
        var current = storeMenus.getUserRoleMenu(user.getClerkId());
        require(current != null);
        return storeTokens.permissionList(current);
    }

    public void checkRoute(AuthUser user, String method, String path, boolean stage0) {
        if (user == null || user.getRole() == UserEnums.MEMBER) return;
        if (stage0 && (method.equals("POST") || path.equals("/store/member/user/info")
                || path.equals("/store/member/user") || path.equals("/manager/passport/user/info"))) return;
        if (!stage0 && Boolean.TRUE.equals(user.getIsSuper())) return; // Current DB flag, never JWT flag.
        Map<String, List<String>> permission = permissions(user);
        require(permission != null && (matches(permission.get("SUPER"), path)
                || (method.equals("GET") && matches(permission.get("QUERY"), path))));
    }

    private Member member(String id) {
        Member member = members.getById(id);
        require(member != null && id.equals(member.getId()) && Boolean.TRUE.equals(member.getDisabled())
                && Boolean.FALSE.equals(member.getDeleteFlag()) && text(member.getUsername()));
        return member;
    }

    private AuthUser resident(Member member) {
        return AuthUser.builder().id(member.getId()).username(member.getUsername()).nickName(member.getNickName())
                .face(member.getFace()).role(UserEnums.MEMBER).isSuper(false).build();
    }

    private AuthUser manager(String id) {
        AdminUser admin = admins.getById(id);
        require(admin != null && id.equals(admin.getId()) && Boolean.TRUE.equals(admin.getStatus())
                && Boolean.FALSE.equals(admin.getDeleteFlag()) && admin.getIsSuper() != null && text(admin.getUsername()));
        return AuthUser.builder().id(id).username(admin.getUsername()).nickName(admin.getNickName())
                .face(admin.getAvatar()).role(UserEnums.MANAGER).isSuper(admin.getIsSuper()).build();
    }

    private AuthUser store(Member member) {
        List<Clerk> links = clerks.list(new QueryWrapper<Clerk>().eq("member_id", member.getId()));
        require(links != null && links.size() == 1);
        Clerk clerk = links.getFirst();
        require(clerk != null && text(clerk.getId()) && text(clerk.getStoreId())
                && member.getId().equals(clerk.getMemberId()) && Boolean.TRUE.equals(clerk.getStatus())
                && Boolean.FALSE.equals(clerk.getDeleteFlag()) && clerk.getShopkeeper() != null && clerk.getIsSuper() != null);
        Store store = stores.getById(clerk.getStoreId());
        require(store != null && clerk.getStoreId().equals(store.getId()) && text(store.getMemberId())
                && Boolean.FALSE.equals(store.getDeleteFlag()) && StoreStatusEnum.OPEN.name().equals(store.getStoreDisable())
                && (store.getStoreEndTime() == null || store.getStoreEndTime().getTime() > System.currentTimeMillis()));
        require(Boolean.TRUE.equals(clerk.getShopkeeper()) == member.getId().equals(store.getMemberId()));
        // A Staff must still have a current grant; never rely solely on a stale roleIds string.
        var grants = storeMenus.getUserRoleMenu(clerk.getId());
        require(grants != null && (Boolean.TRUE.equals(clerk.getShopkeeper()) || !grants.isEmpty()));
        return AuthUser.builder().id(member.getId()).username(member.getUsername()).nickName(member.getNickName())
                .role(UserEnums.STORE).storeId(store.getId()).storeName(store.getStoreName()).clerkId(clerk.getId())
                .isSuper(clerk.getIsSuper()).build();
    }

    private static boolean matches(List<String> values, String path) {
        return values != null && PatternMatchUtils.simpleMatch(values.toArray(String[]::new), path);
    }
    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static void require(boolean value) { if (!value) throw new SessionException(DENIED); }
}
