package cn.lili.common.security.session;

import cn.lili.common.properties.JWTTokenProperties;
import cn.lili.common.security.session.http.*;
import cn.lili.common.security.token.SecretKeyUtil;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Stage1SessionCutoffTest {
    @AfterEach void clear(){org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    @Test void bothAccessAndRefreshRespectPersistentCutoffAndNewLoginGetsNewLifetime(){
        var clock=new MutableClock();var memory=new Memory();var cutoff=new AtomicLong(-1);
        var policy=new SessionAuthorization(){public void check(SessionIdentity identity){}public void checkRecord(SessionRecord record){if(record.issuedAt()<=cutoff.get())throw new SessionException(SessionException.Code.DENIED);}};
        var service=new SessionService(memory,keys(),policy,clock);var identity=new SessionIdentity("123",SessionDomain.STORE,"s1_test_merchant","321",1L);
        var first=service.create(identity);assertEquals(clock.millis(),service.verifyAccess(first.accessToken(),SessionDomain.STORE,identity.application()).issuedAt());
        cutoff.set(clock.millis());assertThrows(SessionException.class,()->service.verifyAccess(first.accessToken(),SessionDomain.STORE,identity.application()));assertThrows(SessionException.class,()->service.refresh(first.refreshToken(),SessionDomain.STORE,identity.application()));
        assertEquals(0,memory.records.get(first.sid()).generation());clock.time+=10;
        var second=service.create(identity);var rotated=service.refresh(second.refreshToken(),SessionDomain.STORE,identity.application());assertEquals(second.sid(),rotated.sid());assertEquals(clock.millis(),service.verifyAccess(rotated.accessToken(),SessionDomain.STORE,identity.application()).issuedAt());
        assertThrows(SessionException.class,()->service.verifyAccess(first.accessToken(),SessionDomain.STORE,identity.application()));
    }
    @Test void applicantIdentityShortCircuitDoesNotConsumeBusinessRoutes() throws Exception {
        var service=new SessionService(new Memory(),keys(),identity->{},Clock.systemUTC());var identity=new SessionIdentity("123",SessionDomain.APPLICANT,"s1_test_merchant","321",1L);var tokens=service.create(identity);
        var authorization=mock(CurrentSessionAuthorization.class);var apps=new SessionApplications();apps.setMerchantApplication(identity.application());apps.setResidentApplication("s1_test_resident");
        var filter=new SessionHttpFilter(service,authorization,apps,SessionDomain.APPLICANT);var calls=new AtomicInteger();
        var request=new MockHttpServletRequest("POST","/store/passport/applicant/p0/s1/application");request.addHeader("accessToken",tokens.accessToken());var response=new MockHttpServletResponse();
        filter.doFilter(request,response,(req,res)->calls.incrementAndGet());assertEquals(1,calls.get());assertEquals(200,response.getStatus());
        var own=new MockHttpServletRequest("GET","/store/passport/applicant/identity");own.addHeader("accessToken",tokens.accessToken());var summary=new MockHttpServletResponse();filter.doFilter(own,summary,(req,res)->calls.incrementAndGet());assertEquals(1,calls.get());assertTrue(summary.getContentAsString().contains("RESTRICTED"));
        var legacy=new MockHttpServletRequest("POST","/store/passport/applicant/p0/s1/application");legacy.addHeader("accessToken","legacy-value");var denied=new MockHttpServletResponse();filter.doFilter(legacy,denied,(req,res)->calls.incrementAndGet());assertEquals(403,denied.getStatus());assertEquals(1,calls.get());
    }
    @Test void deniedBusinessPermissionDoesNotInvalidateVerifiedLogin() throws Exception {
        for (RuntimeException denial : List.of(new cn.lili.modules.community.p0.support.Stage1Exception(403,"FORBIDDEN"), new SessionException(SessionException.Code.DENIED))) {
            var service=new SessionService(new Memory(),keys(),identity->{},Clock.systemUTC());
            var identity=new SessionIdentity("123",SessionDomain.STORE,"s1_test_merchant","321",1L);var pair=service.create(identity);
            var auth=mock(CurrentSessionAuthorization.class);var apps=new SessionApplications();apps.setMerchantApplication(identity.application());
            when(auth.current(identity)).thenReturn(cn.lili.common.security.AuthUser.builder().id("123").role(cn.lili.common.security.enums.UserEnums.STORE).build());
            doThrow(denial).when(auth).checkRoute(any(),eq("GET"),eq("/store/p0/s3/workbench"),eq(true));
            var filter=new SessionHttpFilter(service,auth,apps,SessionDomain.STORE);var calls=new AtomicInteger();
            var denied=new MockHttpServletRequest("GET","/store/p0/s3/workbench");denied.addHeader("accessToken",pair.accessToken());var response=new MockHttpServletResponse();
            filter.doFilter(denied,response,(req,res)->calls.incrementAndGet());
            assertEquals(403,response.getStatus());assertTrue(response.getContentAsString().contains("FORBIDDEN"));assertFalse(response.getContentAsString().contains("SESSION_REJECTED"));assertEquals(0,calls.get());
            var self=new MockHttpServletRequest("GET","/store/member/user/info");self.addHeader("accessToken",pair.accessToken());var allowed=new MockHttpServletResponse();
            filter.doFilter(self,allowed,(req,res)->calls.incrementAndGet());assertEquals(200,allowed.getStatus());assertEquals(1,calls.get());
            service.revoke(service.verifyAccess(pair.accessToken(),SessionDomain.STORE,identity.application()));
            var revoked=new MockHttpServletResponse();filter.doFilter(self,revoked,(req,res)->calls.incrementAndGet());assertEquals(403,revoked.getStatus());assertTrue(revoked.getContentAsString().contains("SESSION_REJECTED"));assertEquals(1,calls.get());
        }
    }
    private static SecretKeyUtil keys(){byte[] b=new byte[32];new SecureRandom().nextBytes(b);var p=new JWTTokenProperties();p.setSecretKey(Base64.getEncoder().encodeToString(b));return new SecretKeyUtil(p);}
    private static class MutableClock extends Clock {long time=System.currentTimeMillis()/1000*1000;public ZoneId getZone(){return ZoneOffset.UTC;}public Clock withZone(ZoneId zone){return this;}public Instant instant(){return Instant.ofEpochMilli(time);}public long millis(){return time;}}
    private static class Memory implements SessionService.Store {
        final Map<String,SessionRecord> records=new HashMap<>();public boolean create(SessionRecord r){return records.putIfAbsent(r.sid(),r)==null;}public SessionRecord load(SessionDomain d,String app,String sid){return records.get(sid);}public boolean rotate(SessionRecord before,SessionRecord after){return records.replace(before.sid(),before,after);}public boolean revoke(SessionRecord r){records.put(r.sid(),r.revoked());return true;}
    }
}
