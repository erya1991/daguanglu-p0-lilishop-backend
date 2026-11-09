package cn.lili.controller.manager.passport;
import cn.lili.cache.Cache;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.session.*;
import cn.lili.common.security.session.http.*;
import cn.lili.modules.permission.manager.NativeManagerAccess;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.junit.jupiter.api.*;
import org.springframework.aop.aspectj.AspectJExpressionPointcut;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.web.context.request.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class NativeManagerMutationGuardTest {
 @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();SecurityContextHolder.clearContext();}
 @Test void nativeAuditRecordsTargetAndOutcomeWithoutCredentials(){var audit=mock(cn.lili.modules.permission.service.SystemLogService.class);var guard=new NativeManagerMutationGuard(mock(JdbcTemplate.class),mock(PlatformTransactionManager.class),mock(NativeManagerAccess.class),mock(CurrentSessionAuthorization.class),mock(Cache.class),audit);var request=new MockHttpServletRequest("POST","/manager/passport/user/resetPassword/123");request.addParameter("id","123");request.addParameter("password","must-never-be-recorded");request.addParameter("token","must-never-be-recorded");org.springframework.test.util.ReflectionTestUtils.invokeMethod(guard,"audit",request,"operator",System.currentTimeMillis(),"REJECTED");verify(audit).saveLog(argThat(e->e.getUsername().equals("operator")&&e.getCustomerLog().equals("REJECTED")&&e.getRequestParam().contains("123")&&!e.getRequestParam().contains("must-never-be-recorded")));}
 @Test void pointcutCoversStageBusinessControllersAsWellAsNativeSettings()throws Exception{
  var pointcut=new AspectJExpressionPointcut();pointcut.setExpression(NativeManagerMutationGuard.class.getMethod("guard",ProceedingJoinPoint.class).getAnnotation(Around.class).value());
  for(var type:List.of(AdminUserManagerController.class,cn.lili.modules.community.p0.catalog.GoodsManagementController.class,cn.lili.modules.community.p0.stage3.Stage3Controller.class,cn.lili.modules.community.p0.stage45.Stage45Controller.class)){
   for(var method:type.getDeclaredMethods())if(java.lang.reflect.Modifier.isPublic(method.getModifiers()))assertTrue(pointcut.matches(method,type),type.getSimpleName()+"."+method.getName());
  }
 }
 @Test void aQueuedStageCommandRechecksCurrentAuthorityAfterTheSharedLock()throws Throwable{
  var request=new MockHttpServletRequest("POST","/manager/p0/s1/goods/123/approve");RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
  var identity=mock(SessionIdentity.class);when(identity.domain()).thenReturn(SessionDomain.MANAGER);when(identity.subject()).thenReturn("123");var session=mock(SessionService.VerifiedSession.class);when(session.identity()).thenReturn(identity);when(session.issuedAt()).thenReturn(100L);
  var user=mock(AuthUser.class);var auth=new UsernamePasswordAuthenticationToken("manager","unused",List.of());auth.setDetails(new SessionHttpFilter.Principal(session,user));SecurityContextHolder.getContext().setAuthentication(auth);
  var jdbc=mock(JdbcTemplate.class);var tx=mock(PlatformTransactionManager.class);when(tx.getTransaction(any())).thenReturn(new SimpleTransactionStatus());var access=mock(NativeManagerAccess.class);var authorization=mock(CurrentSessionAuthorization.class);when(authorization.current(identity)).thenReturn(user);
  var denied=new IllegalStateException("REVOKED_WHILE_QUEUED");doThrow(denied).when(authorization).checkRoute(user,"POST",request.getRequestURI(),true);
  var join=mock(ProceedingJoinPoint.class);var guard=new NativeManagerMutationGuard(jdbc,tx,access,authorization,mock(Cache.class),mock(cn.lili.modules.permission.service.SystemLogService.class));assertSame(denied,assertThrows(IllegalStateException.class,()->guard.guard(join)));
  var order=inOrder(jdbc,access,authorization);order.verify(jdbc).queryForObject("SELECT id FROM li_p0_manager_guard WHERE id=1 FOR UPDATE",Integer.class);order.verify(access).checkIssuedAt("123",100L);order.verify(authorization).current(identity);order.verify(authorization).checkRoute(user,"POST",request.getRequestURI(),true);verifyNoInteractions(join);verify(tx).rollback(any());
 }
}
