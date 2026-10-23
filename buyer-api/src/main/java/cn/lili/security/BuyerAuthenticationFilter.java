package cn.lili.security;

import cn.hutool.core.util.StrUtil;
import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.common.properties.IgnoredUrlsProperties;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.enums.SecurityEnum;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.token.SecretKeyUtil;
import cn.lili.common.security.session.http.CurrentSessionAuthorization;
import cn.lili.common.security.session.http.SessionHttpFilter;
import cn.lili.common.security.session.http.SessionProtocol;
import cn.lili.common.security.session.http.SessionRoutes;
import cn.lili.common.utils.ResponseUtil;
import com.google.gson.Gson;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import org.springframework.util.PatternMatchUtils;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;


/**
 * 认证结果过滤器
 *
 * @author Chopper
 * @version v4.1
 * @since 2020/11/17 3:37 下午
 * @since
 */
@Slf4j
public class BuyerAuthenticationFilter extends BasicAuthenticationFilter {

    private final Cache cache;

    private final SecretKeyUtil secretKeyUtil;

    private final IgnoredUrlsProperties ignoredUrlsProperties;
    private final CurrentSessionAuthorization currentAuthorization;

    /**
     * 自定义构造器
     *
     * @param authenticationManager  认证管理器
     * @param cache                  缓存
     * @param ignoredUrlsProperties  忽略鉴权配置
     */
    public BuyerAuthenticationFilter(AuthenticationManager authenticationManager,
                                     Cache cache,
                                     IgnoredUrlsProperties ignoredUrlsProperties,
                                     SecretKeyUtil secretKeyUtil) {
        this(authenticationManager, cache, ignoredUrlsProperties, secretKeyUtil, null);
    }

    public BuyerAuthenticationFilter(AuthenticationManager authenticationManager, Cache cache,
                                     IgnoredUrlsProperties ignoredUrlsProperties, SecretKeyUtil secretKeyUtil,
                                     CurrentSessionAuthorization currentAuthorization) {
        super(authenticationManager);
        this.cache = cache;
        this.secretKeyUtil = java.util.Objects.requireNonNull(secretKeyUtil, "JWT_KEY_COMPONENT_REQUIRED");
        this.ignoredUrlsProperties = ignoredUrlsProperties;
        this.currentAuthorization = currentAuthorization;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (SessionHttpFilter.verifiedRequest()) {
            chain.doFilter(request, response);
            return;
        }
        // Narrow identity routes are protected even if an external ignored.urls is too broad.
        String requestUri = request.getRequestURI();
        for (String url : ignoredUrlsProperties.getUrls()) {
            if (!SessionRoutes.requiresAuthentication(request) && PatternMatchUtils.simpleMatch(url, requestUri)) {
                chain.doFilter(request, response);
                return;
            }
        }

        //从header中获取jwt
        String jwt = request.getHeader(SecurityEnum.HEADER_TOKEN.getValue());
        //如果没有token 则return
        if (StrUtil.isBlank(jwt)) {
            chain.doFilter(request, response);
            return;
        }

        try {
            UsernamePasswordAuthenticationToken authentication = getAuthentication(jwt, response);
            if (authentication != null) {
                SecurityContextHolder.getContext().setAuthentication(authentication);
                chain.doFilter(request, response);
            } else if (!response.isCommitted()) {
                ResponseUtil.output(response, 403, ResponseUtil.resultMap(false, 403, "登录已失效，请重新登录"));
            }
        } catch (Exception e) {
            log.error("Buyer authentication failed: {}", e.getClass().getSimpleName());
            if (!response.isCommitted()) {
                SecurityContextHolder.clearContext();
                ResponseUtil.output(response, 403, ResponseUtil.resultMap(false, 403, "登录已失效，请重新登录"));
            }
        }
    }

    /**
     * 解析用户
     *
     * @param jwt      token
     * @param response 响应
     * @return 认证信息
     */
    private UsernamePasswordAuthenticationToken getAuthentication(String jwt, HttpServletResponse response) {

        try {
            Claims claims =
                    secretKeyUtil.parseClaims(jwt);
            if (SessionProtocol.isStage0(claims)) return null;
            // 获取存储在claims中的用户信息
            String json = claims.get(SecurityEnum.USER_CONTEXT.getValue()).toString();
            AuthUser authUser = new Gson().fromJson(json, AuthUser.class);
            if (authUser == null || authUser.getRole() != UserEnums.MEMBER) return null;

            //校验redis中是否有权限
            if (cache.hasKey(CachePrefix.ACCESS_TOKEN.getPrefix(UserEnums.MEMBER, authUser.getId()) + jwt)) {
                if (currentAuthorization != null) authUser = currentAuthorization.legacy(authUser);
                //构造返回信息
                List<GrantedAuthority> auths = new ArrayList<>();
                auths.add(new SimpleGrantedAuthority("ROLE_" + authUser.getRole().name()));
                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(authUser.getUsername(), null, auths);
                authentication.setDetails(authUser);
                return authentication;
            }
        } catch (ExpiredJwtException e) {
            log.debug("JWT authentication rejected: {}", e.getClass().getSimpleName());
        } catch (Exception e) {
            log.error("JWT authentication rejected: {}", e.getClass().getSimpleName());
        }
        return null;
    }

}
