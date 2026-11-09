package cn.lili.controller.manager.passport;

import cn.lili.common.security.session.http.CurrentSessionAuthorization;
import cn.lili.common.security.session.http.SessionHttpFilter;
import cn.lili.modules.permission.manager.*;
import cn.lili.cache.*;
import cn.lili.common.security.enums.UserEnums;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Serialize authority mutations and manager commands, then re-read authority under the same lock.
 * A rejected last-admin mutation rolls back its native table changes, never recreates grants. */
@Aspect @Component @RequiredArgsConstructor @ConditionalOnProperty(name="p0.uat.enabled",havingValue="true")
public class NativeManagerMutationGuard {
    private final JdbcTemplate jdbc;
    private final org.springframework.transaction.PlatformTransactionManager transactions;
    private final NativeManagerAccess access;
    private final CurrentSessionAuthorization authorization;
    private final Cache cache;
    private final cn.lili.modules.permission.service.SystemLogService audit;
    @Around("execution(public * cn.lili.controller.manager..*Controller.*(..)) || execution(public * cn.lili.modules.community.p0..*Controller.*(..))")
    public Object guard(ProceedingJoinPoint join) throws Throwable {
        var request=ManagerWebSupport.request();
        if(request==null||request.getMethod().equals("GET"))return join.proceed();
        String path=request.getRequestURI(),method=request.getMethod();
        boolean login=path.equals("/manager/passport/user/login");
        if(!login&&!ManagerWebSupport.active())return join.proceed();
        var operator=cn.lili.common.security.context.UserContext.getCurrentUser();
        String username=operator==null?"游客":operator.getUsername();
        long started=System.currentTimeMillis();
        try {Object outcomeResult=new TransactionTemplate(transactions).execute(status->{
            jdbc.queryForObject("SELECT id FROM li_p0_manager_guard WHERE id=1 FOR UPDATE",Integer.class);
            if(!login){
                var principal=(SessionHttpFilter.Principal)SecurityContextHolder.getContext().getAuthentication().getDetails();
                access.checkIssuedAt(principal.session().identity().subject(),principal.session().issuedAt());
                var current=authorization.current(principal.session().identity());
                authorization.checkRoute(current,method,path,true);
            }
            try {
                Object result=join.proceed();
                if(NativeManagerRoutes.authorityMutation(method,path)) {
                    access.requireManagementEntry();
                    cache.vagueDel(CachePrefix.USER_MENU.getPrefix(UserEnums.MANAGER));
                    cache.vagueDel(CachePrefix.PERMISSION_LIST.getPrefix(UserEnums.MANAGER));
                }
                return result;
            }catch(RuntimeException|Error e){throw e;}
            catch(Throwable e){throw new IllegalStateException("MANAGER_MUTATION_FAILED",e);}
        });
        audit(request,username,started,outcomeResult instanceof cn.lili.common.vo.ResultMessage<?> r&&!r.isSuccess()?"REJECTED":"COMMITTED");
        return outcomeResult;
        }catch(RuntimeException error){audit(request,username,started,"REJECTED");throw error;}
    }
    private void audit(jakarta.servlet.http.HttpServletRequest request,String username,long started,String outcome){
        if(!NativeManagerRoutes.allowed(request.getMethod(),request.getRequestURI()))return;
        var entry=new cn.lili.modules.permission.entity.vo.SystemLogVO();
        entry.setUsername(username).setName("原生管理操作").setRequestUrl(request.getRequestURI()).setRequestType(request.getMethod()).setCustomerLog(outcome).setCostTime((int)(System.currentTimeMillis()-started)).setIp(request.getRemoteAddr());
        java.util.Map<String,String[]> targets=new java.util.LinkedHashMap<>();
        for(String key:java.util.List.of("id","ids","userId","roleId","departmentId","username"))if(request.getParameterValues(key)!=null)targets.put(key,request.getParameterValues(key));
        entry.setMapToParams(targets);
        // Record actor, target and outcome after the native transaction; never credential responses.
        try{audit.saveLog(entry);}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).error("Native audit persistence failed: {} {}",request.getMethod(),request.getRequestURI());}
    }
}
