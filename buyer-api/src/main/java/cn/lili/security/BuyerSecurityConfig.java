package cn.lili.security;

import cn.lili.cache.Cache;
import cn.lili.common.properties.IgnoredUrlsProperties;
import cn.lili.common.security.CustomAccessDeniedHandler;
import cn.lili.common.security.token.SecretKeyUtil;
import cn.lili.common.security.session.SessionDomain;
import cn.lili.common.security.session.SessionService;
import cn.lili.common.security.session.http.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfigurationSource;

/**
 * 买家端 API 安全配置。
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class BuyerSecurityConfig {

    private final IgnoredUrlsProperties ignoredUrlsProperties;
    private final CustomAccessDeniedHandler accessDeniedHandler;
    private final Cache<String> cache;
    private final SecretKeyUtil secretKeyUtil;
    private final CorsConfigurationSource corsConfigurationSource;
    private final SessionService sessions;
    private final CurrentSessionAuthorization currentAuthorization;
    private final SessionApplications applications;

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration cfg) throws Exception {
        return cfg.getAuthenticationManager();
    }

    @Bean
    @Order(10)
    public SecurityFilterChain buyerSecurityFilterChain(HttpSecurity http,
                                                        AuthenticationManager authManager) throws Exception {
        String[] ignored = ignoredUrlsProperties.getUrls().toArray(new String[0]);
        http.securityMatcher("/buyer/**")
            .authorizeHttpRequests(a -> a
                .requestMatchers(HttpMethod.POST, SessionRoutes.RESIDENT_LOGIN).permitAll()
                .requestMatchers(SessionRoutes.RESIDENT_LOGIN).denyAll()
                .requestMatchers(req -> SessionRoutes.residentWritePath(req.getRequestURI()))
                    .hasAuthority("ROLE_STAGE0_RESIDENT")
                .requestMatchers(HttpMethod.POST, SessionRoutes.refresh(SessionDomain.RESIDENT)).permitAll()
                .requestMatchers(SessionRoutes::requiresAuthentication).authenticated()
                .requestMatchers(ignored).permitAll()
                .anyRequest().authenticated())
            .headers(h -> h.frameOptions(f -> f.disable()))
            .logout(l -> l.permitAll())
            .cors(c -> c.configurationSource(corsConfigurationSource))
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .exceptionHandling(e -> e
                .accessDeniedHandler(accessDeniedHandler)
                .authenticationEntryPoint((req, res, ex) ->
                    cn.lili.common.utils.ResponseUtil.output(res, 403,
                        cn.lili.common.utils.ResponseUtil.resultMap(false, 403, "未登录或token失效"))))
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable())
            .addFilterBefore(new SessionHttpFilter(sessions, currentAuthorization, applications, SessionDomain.RESIDENT), BasicAuthenticationFilter.class)
            .addFilter(new BuyerAuthenticationFilter(authManager, cache, ignoredUrlsProperties, secretKeyUtil, currentAuthorization));
        return http.build();
    }

    @Bean
    @Order(100)
    public SecurityFilterChain commonSecurityFilterChain(HttpSecurity http) throws Exception {
        String[] ignored = ignoredUrlsProperties.getUrls().toArray(new String[0]);
        http
            .headers(h -> h.frameOptions(f -> f.disable()))
            .authorizeHttpRequests(a -> a
                .requestMatchers(ignored).permitAll()
                .anyRequest().permitAll())
            .cors(c -> c.configurationSource(corsConfigurationSource))
            .csrf(c -> c.disable())
            .exceptionHandling(e -> e
                .accessDeniedHandler(accessDeniedHandler)
                .authenticationEntryPoint((req, res, ex) ->
                    cn.lili.common.utils.ResponseUtil.output(res, 403,
                        cn.lili.common.utils.ResponseUtil.resultMap(false, 403, "未登录或token失效"))))
            .formLogin(f -> f.disable())
            .httpBasic(b -> b.disable());
        return http.build();
    }
}
