package cn.lili.common.security.context;

import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.enums.SecurityEnum;
import cn.lili.common.security.token.SecretKeyUtil;
import cn.lili.common.security.session.http.SessionHttpFilter;
import org.springframework.security.core.context.SecurityContextHolder;
import cn.lili.common.utils.StringUtils;
import com.google.gson.Gson;
import io.jsonwebtoken.Claims;
import org.springframework.web.context.support.WebApplicationContextUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 用户上下文
 *
 * @author Chopper
 * @version v4.0
 * @since 2020/11/14 20:27
 */
public class UserContext {

    /**
     * 根据request获取用户信息
     *
     * @return 授权用户
     */
    public static AuthUser getCurrentUser() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) return null;
        if (authentication.getDetails() instanceof SessionHttpFilter.Principal principal) return principal.user();
        if (authentication.getDetails() instanceof AuthUser user) return user;
        return null;
    }

    /**
     * 根据request获取用户信息
     *
     * @return 授权用户
     */
    public static String getUuid() {
        if (RequestContextHolder.getRequestAttributes() != null) {
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
            return request.getHeader(SecurityEnum.UUID.getValue());
        }
        return null;
    }


    /**
     * 根据jwt获取token重的用户信息
     *
     * @param cache       缓存
     * @param accessToken token
     * @return 授权用户
     */
    public static AuthUser getAuthUser(Cache cache, String accessToken) {
        try {
            return getAuthUser(cache, accessToken, requestKey());
        } catch (Exception e) {
            return null;
        }
    }

    /** Explicit context-owned verifier for non-HTTP callers such as WebSocket endpoints. */
    public static AuthUser getAuthUser(Cache cache, String accessToken, SecretKeyUtil secretKeyUtil) {
        try {
            AuthUser authUser = getAuthUser(accessToken, secretKeyUtil);
            if (authUser == null) {
                return null;
            }

            if (!cache.hasKey(CachePrefix.ACCESS_TOKEN.getPrefix(authUser.getRole(), authUser.getId()) + accessToken)) {
                throw new ServiceException(ResultCode.USER_AUTHORITY_ERROR);
            }
            return authUser;
        } catch (Exception e) {
            return null;
        }
    }

    public static String getCurrentUserToken() {
        if (RequestContextHolder.getRequestAttributes() != null) {
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
            return request.getHeader(SecurityEnum.HEADER_TOKEN.getValue());
        }
        return null;
    }

    /**
     * 根据jwt获取token重的用户信息
     *
     * @param accessToken token
     * @return 授权用户
     */
    public static AuthUser getAuthUser(String accessToken) {
        return accessToken != null && accessToken.equals(getCurrentUserToken()) ? getCurrentUser() : null;
    }

    /** Legacy IM decoding only; NOT a business authorization API. HTTP uses the verified context above. */
    @Deprecated
    public static AuthUser getAuthUser(String accessToken, SecretKeyUtil secretKeyUtil) {
        try {
            //获取token的信息
            Claims claims =
                    secretKeyUtil.parseClaims(accessToken);
            //获取存储在claims中的用户信息
            String json = claims.get(SecurityEnum.USER_CONTEXT.getValue()).toString();
            return new Gson().fromJson(json, AuthUser.class);
        } catch (Exception e) {
            return null;
        }
    }


    private static SecretKeyUtil requestKey() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes)) {
            throw new IllegalStateException("JWT_REQUEST_CONTEXT_REQUIRED");
        }
        return WebApplicationContextUtils
                .getRequiredWebApplicationContext(attributes.getRequest().getServletContext())
                .getBean(SecretKeyUtil.class);
    }

    /**
     * 写入邀请人信息
     */
    public static void settingInviter(String memberId, Cache cache) {
        if (RequestContextHolder.getRequestAttributes() != null) {
            HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.getRequestAttributes()).getRequest();
            //邀请人id
            String inviterId = request.getHeader(SecurityEnum.INVITER.getValue());
            if (StringUtils.isNotEmpty(inviterId)) {
                cache.put(CachePrefix.INVITER.getPrefix() + memberId, inviterId);
            }
        }
    }


}
