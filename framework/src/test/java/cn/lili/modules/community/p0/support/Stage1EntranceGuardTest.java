package cn.lili.modules.community.p0.support;
import cn.lili.common.security.session.SessionDomain;
import cn.lili.common.security.session.http.SessionRoutes;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;
import static org.junit.jupiter.api.Assertions.*;
class Stage1EntranceGuardTest {
    @Test void legacyBusinessAndEncodedVariantsCannotReachAnyControllerRegardlessOfIdentity()throws Exception{
        for(String path:new String[]{"/manager/goods/goods/1","/store/goods/goods/stock","/store/member/storeAddress/1","/manager/goods/goods/import","/buyer/trade/cart","/buyer/trade/order","/common/pay/APP","/manager/setting/setting","/store/p0/s1/goods/1;ignored","/store/p0/s1/%67oods","/store//p0/s1/goods"})for(String method:new String[]{"GET","POST","PUT","DELETE"}){
            var request=new MockHttpServletRequest(method,path);request.addHeader("isSuper","true");var response=new MockHttpServletResponse();var chain=new MockFilterChain();new Stage1EntranceGuard().doFilter(request,response,chain);assertEquals(403,response.getStatus(),method+" "+path);assertNull(chain.getRequest());
        }
    }
    @Test void catalogAndOriginalSessionTransportRemainSubjectToTheirExistingAuthentication(){
        for(var route:Stage1Routes.all())assertTrue(Stage1EntranceGuard.allowed(route.method(),route.template().replace("{id}","123").replace("{fileId}","456").replace("{requestId}","request_123")),route.template());
        for(var domain:SessionDomain.values()){assertTrue(Stage1EntranceGuard.allowed("POST",SessionRoutes.refresh(domain)));assertTrue(Stage1EntranceGuard.allowed("POST",SessionRoutes.logout(domain)));assertFalse(Stage1EntranceGuard.allowed("GET",SessionRoutes.refresh(domain)+"/token"));}
        assertTrue(Stage1EntranceGuard.allowed("POST",SessionRoutes.RESIDENT_LOGIN));assertTrue(Stage1EntranceGuard.allowed("POST",SessionRoutes.MERCHANT_LOGIN));assertTrue(Stage1EntranceGuard.allowed("PUT",SessionRoutes.RESIDENT_EDIT));assertFalse(Stage1EntranceGuard.allowed("DELETE",Stage1Routes.STORE+"/points/123"));
    }
}
