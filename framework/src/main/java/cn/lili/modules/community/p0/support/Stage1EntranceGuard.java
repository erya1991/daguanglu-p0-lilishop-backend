package cn.lili.modules.community.p0.support;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.common.security.session.http.SessionRoutes;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Set;

/** Only the explicitly enabled Stage1 runtime is narrowed. Historical runtimes retain their own chains. */
public final class Stage1EntranceGuard extends OncePerRequestFilter {
    static boolean allowed(String method,String path){
        if(path==null||path.contains("%")||path.contains(";")||path.contains("//")||path.contains("\\")||path.contains(".."))return false;
        if(Stage1Routes.path(path))return method.equals("OPTIONS")||Stage1Routes.all().stream().anyMatch(r->r.method().equals(method)&&r.pattern().matcher(path).matches());
        if(path.equals("/manager/common/slider/LOGIN")&&Set.of("GET","POST").contains(method))return true;
        if(method.equals("POST")){
            if(Set.of(SessionRoutes.RESIDENT_LOGIN,SessionRoutes.MERCHANT_LOGIN,SessionRoutes.MANAGER_LOGIN,SessionRoutes.RESIDENT_PHONE,SessionRoutes.RESIDENT_AVATAR,SessionRoutes.RESIDENT_LINK).contains(path))return true;
            for(var domain:SessionDomain.values())if(path.equals(SessionRoutes.refresh(domain))||path.equals(SessionRoutes.logout(domain)))return true;
        }
        if(method.equals("PUT")&&path.equals(SessionRoutes.RESIDENT_EDIT))return true;
        return method.equals("GET")&&Set.of("/actuator/health","/buyer/passport/member",SessionRoutes.APPLICANT+"/identity","/store/member/user","/store/member/user/info","/manager/passport/user/info","/manager/permission/menu/memberMenu").contains(path);
    }
    @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
        if(allowed(request.getMethod(),request.getRequestURI())){chain.doFilter(request,response);return;}
        response.setStatus(403);response.setContentType("application/json;charset=UTF-8");response.setHeader("Cache-Control","no-store");response.getWriter().write("{\"success\":false,\"code\":\"STAGE1_ENTRANCE_DISABLED\",\"message\":\"当前环境未开放此入口\",\"retryable\":false}");
    }
    @Configuration
    @ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
    public static class Registration {
        @Bean public FilterRegistrationBean<Stage1EntranceGuard> stage1EntranceGuard(){var bean=new FilterRegistrationBean<>(new Stage1EntranceGuard());bean.setOrder(Ordered.HIGHEST_PRECEDENCE+10);bean.setDispatcherTypes(DispatcherType.REQUEST,DispatcherType.ASYNC,DispatcherType.FORWARD);bean.addUrlPatterns("/*");return bean;}
    }
}
