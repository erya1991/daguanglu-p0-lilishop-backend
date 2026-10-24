package cn.lili.controller.seller.member;

import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.session.SessionDomain;
import cn.lili.common.security.session.http.SessionHttpFilter;
import cn.lili.common.utils.ResponseUtil;
import cn.lili.modules.member.merchant.MerchantAccessException;
import cn.lili.modules.member.merchant.MerchantAuthorizationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/** P05 exposes only the verified current merchant and single Store, never complete native entities. */
@RestController
@Tag(name = "店铺端,管理员接口")
@RequestMapping("/store/member/user")
public final class StoreUserController {
    private final MerchantAuthorizationService authorization;
    public StoreUserController(MerchantAuthorizationService authorization) { this.authorization = authorization; }

    @GetMapping("/info")
    @Operation(summary = "获取当前登录用户接口")
    public void getUserInfo(HttpServletResponse response) { read(response, true); }

    @GetMapping("")
    @Operation(summary = "获取当前登录店铺接口")
    public void getStoreInfo(HttpServletResponse response) { read(response, false); }

    private void read(HttpServletResponse response, boolean userInfo) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        try {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            require(authentication != null && authentication.isAuthenticated()
                    && authentication.getDetails() instanceof SessionHttpFilter.Principal);
            var principal = (SessionHttpFilter.Principal) authentication.getDetails();
            require(principal.session().identity().domain() == SessionDomain.STORE
                    && principal.user() != null && principal.user().getRole() == UserEnums.STORE);
            var current = authorization.resolve(principal.session().identity());
            require(current != null && current.domain() == SessionDomain.STORE
                    && Objects.equals(current.memberId(), principal.session().identity().subject())
                    && Objects.equals(current.clerkId(), principal.user().getClerkId())
                    && Objects.equals(current.storeId(), principal.user().getStoreId())
                    && (current.role() == MerchantAuthorizationService.Role.OWNER
                    || current.role() == MerchantAuthorizationService.Role.STAFF));
            Object result = userInfo
                    ? new IdentityInfo(current.memberId(), "STORE", current.role().name(), current.storeId(), current.clerkId())
                    : new StoreInfo(current.storeId(), current.storeName(), "STORE", current.role().name());
            ResponseUtil.output(response, 200, ResponseUtil.resultMap(true, 200, "SUCCESS", result));
        } catch (MerchantAccessException failure) {
            int status = switch (failure.getCode()) {
                case DATA_UNAVAILABLE, PROOF_SOURCE_PENDING -> 503;
                case RELATION_CONFLICT -> 409;
                case DENIED -> 403;
            };
            ResponseUtil.output(response, status, ResponseUtil.resultMap(false, status, failure.getCode().name()));
        } catch (RuntimeException ignored) {
            ResponseUtil.output(response, 503, ResponseUtil.resultMap(false, 503, "DATA_UNAVAILABLE"));
        }
    }

    private static void require(boolean value) {
        if (!value) throw new MerchantAccessException(MerchantAccessException.Code.DENIED);
    }

    public record IdentityInfo(String memberId, String identity, String role, String storeId, String clerkId) { }
    public record StoreInfo(String storeId, String storeName, String identity, String role) { }
}
