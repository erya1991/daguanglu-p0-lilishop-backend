package cn.lili.modules.community.p0.stage45;
import org.junit.jupiter.api.Test;
import cn.lili.common.security.session.SessionDomain;
import static org.junit.jupiter.api.Assertions.*;
class Stage45RoutesTest {
 @Test void residentCannotReachFinancialRecoveryOrMerchantReview(){
  assertNull(Stage45Routes.find(SessionDomain.RESIDENT,"POST","/manager/p0/s45/tasks/123/recover"));
  assertNull(Stage45Routes.find(SessionDomain.RESIDENT,"POST","/buyer/p0/s45/aftersales/123/approve"));
  assertNull(Stage45Routes.find(SessionDomain.STORE,"POST","/store/p0/s45/tasks/123/recover"));
  assertEquals("aftersale.apply",Stage45Routes.find(SessionDomain.RESIDENT,"POST","/buyer/p0/s45/orders/123/aftersales").action());
 }
 @Test void legacyMutationsAndNoncanonicalPathsAreNotAdmitted(){
  for(String path:new String[]{"/buyer/p0/s45/orders/123/aftersales/","/buyer/p0/s45/orders/../123/aftersales","/buyer/p0/s45/orders/%31/aftersales","/buyer/p0/s45/orders/01/aftersales","/store/order/afterSale/refund/123"})assertFalse(Stage45Routes.allowed("POST",path),path);
 }
 @Test void distinctPermissionsAreRequiredForBusinessReviewAndMoney(){
  assertEquals("aftersale.resolve",Stage45Routes.find(SessionDomain.MANAGER,"POST","/manager/p0/s45/aftersales/123/resolve").action());
  assertEquals("funds.recover",Stage45Routes.find(SessionDomain.MANAGER,"POST","/manager/p0/s45/tasks/123/recover").action());
  assertEquals("evaluation.moderate",Stage45Routes.find(SessionDomain.MANAGER,"POST","/manager/p0/s45/evaluations/123/moderate").action());
 }
}
