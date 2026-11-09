package cn.lili.modules.community.p0.stage3;
import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.stage2.Stage2EntranceGuard;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class Stage3RoutesTest {
    @Test void finiteRoutesRejectWrongDomainEncodingAndOutOfScopeWrites(){
        assertNotNull(Stage3Routes.find(SessionDomain.RESIDENT,"POST","/buyer/p0/s3/orders/9007199254740993/receive"));
        assertNull(Stage3Routes.find(SessionDomain.STORE,"POST","/buyer/p0/s3/orders/1/receive"));
        for(String path:new String[]{"/store/p0/s3/orders/1/remark","/store/p0/s3/orders/1/ship/correct","/manager/p0/s3/orders/1/receive","/store/p0/s3/verify/lookup?token=raw","/store/p0/s3/orders/%31/ship","/store/p0/s3/orders/1//ship","/store/p0/s3/orders/../ship","/store/p0/s3/orders/1;foo/ship"})assertFalse(Stage3Routes.allowed("POST",path),path);
        assertFalse(Stage3Routes.allowed("GET","/store/p0/s3/verify/lookup"));assertFalse(Stage3Routes.allowed("PUT","/store/p0/s3/orders/1/ship"));
    }
    @Test void stage2DefaultDoesNotOpenStage3OrNativeFulfillment(){
        assertFalse(Stage2EntranceGuard.allowed("GET","/buyer/p0/s3/orders"));
        assertFalse(Stage2EntranceGuard.allowed("POST","/store/order/order/1/fulfill"));
        assertFalse(Stage2EntranceGuard.allowed("POST","/buyer/order/order/1/receiving"));
    }
}
