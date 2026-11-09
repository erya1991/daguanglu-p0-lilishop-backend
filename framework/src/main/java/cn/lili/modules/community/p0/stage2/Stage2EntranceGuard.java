package cn.lili.modules.community.p0.stage2;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.common.security.session.http.SessionRoutes;
import cn.lili.modules.community.p0.support.Stage1Routes;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.*;
import org.springframework.core.Ordered;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.Set;

public final class Stage2EntranceGuard extends OncePerRequestFilter {
    private final boolean stage3,stage45;
    private boolean integrated;
    public Stage2EntranceGuard(){this(false,false);}
    public Stage2EntranceGuard(boolean stage3){this(stage3,false);}
    public Stage2EntranceGuard(boolean stage3,boolean stage45){this.stage3=stage3;this.stage45=stage45;}
    public Stage2EntranceGuard(boolean stage3,boolean stage45,boolean integrated){this(stage3,stage45);this.integrated=integrated;}
    private boolean permitted(String method,String path){
        if(integrated && (method.equals("POST")&&Set.of("/buyer/passport/uat/login","/store/passport/uat/login").contains(path)||Set.of("GET","POST").contains(method)&&Set.of("/buyer/common/slider/LOGIN","/store/common/slider/LOGIN").contains(path)))return true;
        if(integrated && Stage2Routes.canonical(path) && (cn.lili.modules.permission.manager.NativeManagerRoutes.allowed(method,path) || Stage1Routes.all().stream().anyMatch(r->r.method().equals(method)&&r.pattern().matcher(path).matches())))return true;
        if(stage45&&cn.lili.modules.community.p0.stage45.Stage45Routes.path(path))return cn.lili.modules.community.p0.stage45.Stage45Routes.allowed(method,path);
        if(stage3&&cn.lili.modules.community.p0.stage3.Stage3Routes.path(path))return cn.lili.modules.community.p0.stage3.Stage3Routes.allowed(method,path);
        if(stage3&&Stage2Routes.canonical(path)&&Stage1Routes.path(path)&&Stage1Routes.all().stream().anyMatch(r->Set.of("staff.manage","invitation.accept").contains(r.action())&&r.method().equals(method)&&r.pattern().matcher(path).matches()))return true;
        return allowed(method,path);
    }
    private static final Set<String> SOURCE_ACTIONS = Set.of("consumer.read", "goods.read", "goods.write", "goods.submit", "goods.publish", "stock.read", "stock.write", "cost.read", "cost.write", "region.read", "point.read", "point.write", "fulfillment.read", "fulfillment.configure", "fulfillment.inspect", "operation.read", "store.read", "summary.read", "cooperation.read", "file.read");
    public static boolean allowed(String method, String path) {
        if (!Stage2Routes.canonical(path)) return false;
        if (Stage2Routes.path(path)) return method.equals("OPTIONS") || Stage2Routes.find(SessionDomain.RESIDENT, method, path) != null;
        if (path.equals("/internal/p0/s2/mock/scenario") || path.equals("/internal/p0/s2/payment/notification")) return method.equals("POST");
        if (Stage1Routes.path(path)) return Stage1Routes.all().stream().anyMatch(r -> SOURCE_ACTIONS.contains(r.action()) && r.method().equals(method) && r.pattern().matcher(path).matches());
        if (path.equals("/manager/common/slider/LOGIN")) return Set.of("GET", "POST").contains(method);
        if (method.equals("POST")) {
            if (Set.of(SessionRoutes.RESIDENT_LOGIN, SessionRoutes.MERCHANT_LOGIN, SessionRoutes.MANAGER_LOGIN,SessionRoutes.RESIDENT_PHONE,SessionRoutes.RESIDENT_AVATAR,SessionRoutes.RESIDENT_LINK).contains(path)) return true;
            for (var domain : SessionDomain.values()) if (path.equals(SessionRoutes.refresh(domain)) || path.equals(SessionRoutes.logout(domain))) return true;
        }
        if(method.equals("PUT")&&path.equals(SessionRoutes.RESIDENT_EDIT))return true;
        return method.equals("GET") && Set.of("/actuator/health", "/buyer/passport/member", SessionRoutes.APPLICANT + "/identity", "/store/member/user", "/store/member/user/info", "/manager/passport/user/info", "/manager/permission/menu/memberMenu").contains(path);
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (permitted(request.getMethod(), request.getRequestURI())) { chain.doFilter(request, response); return; }
        response.setStatus(403); response.setContentType("application/json;charset=UTF-8"); response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"success\":false,\"code\":\"STAGE2_ENTRANCE_DISABLED\",\"message\":\"当前环境未开放此入口\"}");
    }
    @Configuration @ConditionalOnProperty(name="p0.stage2.enabled", havingValue="true")
    public static class Registration {
        @Bean public FilterRegistrationBean<Stage2EntranceGuard> stage2EntranceGuard(org.springframework.core.env.Environment env) {
            var bean = new FilterRegistrationBean<>(new Stage2EntranceGuard(env.getProperty("p0.stage3.enabled",Boolean.class,false),env.getProperty("p0.stage45.enabled",Boolean.class,false),env.getProperty("p0.uat.enabled",Boolean.class,false))); bean.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
            bean.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC, DispatcherType.FORWARD); bean.addUrlPatterns("/*"); return bean;
        }
    }
}
