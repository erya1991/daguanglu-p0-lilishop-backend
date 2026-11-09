package cn.lili.controller.seller.passport;
import cn.lili.common.security.session.*;
import cn.lili.common.utils.ResponseUtil;
import cn.lili.modules.community.p0.uat.UatMemberLoginService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.*;
import java.util.Set;
@RestController @ConditionalOnProperty(name={"p0.uat.enabled","p0.uat.local-login-enabled"},havingValue="true")
public class UatLoginController {
    private final UatMemberLoginService service;
    public UatLoginController(UatMemberLoginService service){this.service=service;}
    @PostMapping("/store/passport/uat/login")
    public void login(HttpServletRequest request,HttpServletResponse response){
        response.setHeader("Cache-Control","no-store");response.setHeader("Pragma","no-cache");
        try{
            if(!request.getParameterMap().keySet().equals(Set.of("username","password")))throw new IllegalArgumentException();
            for(String key:Set.of("username","password"))if(request.getParameterValues(key).length!=1)throw new IllegalArgumentException();
            for(String key:Set.of("memberId","storeId","userId","clerkId","role","isSuper","appId"))if(request.getHeader(key)!=null)throw new IllegalArgumentException();
            var result=service.login(SessionDomain.APPLICANT,request.getParameter("username"),request.getParameter("password"),request.getHeader("uuid"),request.getRemoteAddr());
            ResponseUtil.output(response,200,ResponseUtil.resultMap(true,200,"SUCCESS",result));
        }catch(Exception e){ResponseUtil.output(response,403,ResponseUtil.resultMap(false,403,"UAT_LOGIN_DENIED"));}
    }
}
