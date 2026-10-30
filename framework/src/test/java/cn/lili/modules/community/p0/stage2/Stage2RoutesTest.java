package cn.lili.modules.community.p0.stage2;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.support.P0BusinessEnabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import static org.junit.jupiter.api.Assertions.*;

class Stage2RoutesTest {
    @Test void closedCatalogueRejectsWrongDomainsMethodsAndPathVariants() {
        for (var route : Stage2Routes.all()) {
            String path=route.template().replace("{id}","123").replace("{key}","intent_123").replace("{fileId}","456");
            assertNotNull(Stage2Routes.find(SessionDomain.RESIDENT,route.method(),path));
            assertTrue(Stage2EntranceGuard.allowed(route.method(),path));
            for (var domain : new SessionDomain[]{SessionDomain.MANAGER,SessionDomain.STORE,SessionDomain.APPLICANT}) assertNull(Stage2Routes.find(domain,route.method(),path));
            for (String variant : new String[]{path+"/",path+";x",path.replace("/buyer/","/buyer//"),path.replace("s2","%73%32"),path+"/../x"}) assertNull(Stage2Routes.find(SessionDomain.RESIDENT,route.method(),variant));
        }
        assertFalse(Stage2EntranceGuard.allowed("POST","/buyer/trade/order"));
        assertFalse(Stage2EntranceGuard.allowed("POST","/common/pay/JSAPI"));
        assertFalse(Stage2EntranceGuard.allowed("PUT","/manager/setting/setting"));
        assertFalse(Stage2EntranceGuard.allowed("POST","/manager/p0/s1/channel-configs"));
        assertNull(Stage2Routes.find(SessionDomain.RESIDENT,"PATCH","/buyer/p0/s2/cart"));
    }
    @Configuration @P0BusinessEnabled static class Shared { }
    @Test void sharedAssemblyHasNoImplicitDefaultOrStage1Dependency() {
        new ApplicationContextRunner().withUserConfiguration(Shared.class).run(c->assertEquals(0,c.getBeansOfType(Shared.class).size()));
        for (String property : new String[]{"p0.stage1.enabled=true","p0.stage2.enabled=true"})
            new ApplicationContextRunner().withPropertyValues(property).withUserConfiguration(Shared.class).run(c->assertEquals(1,c.getBeansOfType(Shared.class).size()));
    }
}
