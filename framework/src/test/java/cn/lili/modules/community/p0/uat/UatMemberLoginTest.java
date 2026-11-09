package cn.lili.modules.community.p0.uat;
import cn.lili.common.security.session.*;
import cn.lili.common.security.session.http.SessionApplications;
import cn.lili.common.utils.StringUtils;
import cn.lili.modules.community.p0.stage2.Stage2Settings;
import cn.lili.modules.connect.wechat.WechatIdentityService;
import cn.lili.modules.member.entity.dos.Member;
import cn.lili.modules.member.service.MemberService;
import cn.lili.modules.member.merchant.MerchantAuthorizationService;
import cn.lili.modules.verification.service.VerificationService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.nio.file.*;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;
class UatMemberLoginTest {
 @TempDir Path temp;
 final Stage2Settings settings=mock(Stage2Settings.class);
 final MemberService members=mock(MemberService.class);
 final VerificationService captcha=mock(VerificationService.class);
 final WechatIdentityService identities=mock(WechatIdentityService.class);
 final MerchantAuthorizationService merchants=mock(MerchantAuthorizationService.class);
 final SessionApplications apps=mock(SessionApplications.class);
 final SessionService sessions=mock(SessionService.class);
 final UatMemberLoginService service=new UatMemberLoginService(settings,members,captcha,identities,merchants,apps,sessions);
 final String username="local_person",password="synthetic-test-password",uuid="synthetic-test-uuid-123";
 void fixture(String run)throws Exception{
  Files.createDirectories(temp.resolve("private"));Files.writeString(temp.resolve("private/uat-identities.json"),"{\"runId\":\""+run+"\",\"entries\":[{\"username\":\"local_person\",\"application\":\"uat-app\",\"source\":\"UAT_SYNTHETIC\",\"memberId\":\"101\",\"bindingId\":\"201\",\"bindingVersion\":1}]}");
  when(settings.getRunId()).thenReturn("UAT_20261109033718462");when(apps.application(any())).thenReturn("uat-app");when(captcha.check(eq(uuid),any())).thenReturn(true);
  Member member=new Member();member.setId("101");member.setUsername(username);member.setDisabled(true);member.setDeleteFlag(false);member.setPassword(new BCryptPasswordEncoder().encode(StringUtils.md5(password)));when(members.findByUsername(username)).thenReturn(member);
  when(merchants.resolve(any())).thenReturn(new MerchantAuthorizationService.Context(SessionDomain.APPLICANT,"101",null,null,null,MerchantAuthorizationService.Role.APPLICANT,null));
  when(sessions.create(any())).thenReturn(new SessionTokens("sid","access","refresh",100,200));
 }
 @Test void memberPasswordAndBindingProduceExistingResidentSession()throws Exception{fixture("UAT_20261109033718462");try(var scope=mockStatic(UatResourceScope.class)){scope.when(()->UatResourceScope.registered(settings)).thenReturn(temp);var r=service.login(SessionDomain.RESIDENT,username,password,uuid,"127.0.0.1");assertEquals("RESIDENT",r.identity());assertEquals("101",r.memberId());assertEquals("UAT_SYNTHETIC",r.source());verify(identities).verify(new SessionIdentity("101",SessionDomain.RESIDENT,"uat-app","201",1L));verify(sessions).create(any());}}
 @Test void applicantHasNoInventedStoreRole()throws Exception{fixture("UAT_20261109033718462");try(var scope=mockStatic(UatResourceScope.class)){scope.when(()->UatResourceScope.registered(settings)).thenReturn(temp);var r=service.login(SessionDomain.APPLICANT,username,password,uuid,"127.0.0.1");assertEquals("APPLICANT",r.identity());assertNull(r.role());}}
 @Test void wrongPasswordCannotIssueSession()throws Exception{fixture("UAT_20261109033718462");try(var scope=mockStatic(UatResourceScope.class)){scope.when(()->UatResourceScope.registered(settings)).thenReturn(temp);assertThrows(SessionException.class,()->service.login(SessionDomain.RESIDENT,username,"wrong-password",uuid,"127.0.0.1"));verifyNoInteractions(sessions,identities);}}
 @Test void wrongRunAndForeignRemoteAreDenied()throws Exception{fixture("UAT_20990101000000000");try(var scope=mockStatic(UatResourceScope.class)){scope.when(()->UatResourceScope.registered(settings)).thenReturn(temp);assertThrows(SessionException.class,()->service.login(SessionDomain.RESIDENT,username,password,uuid,"127.0.0.1"));assertThrows(SessionException.class,()->service.login(SessionDomain.RESIDENT,username,password,uuid,"192.168.1.2"));verifyNoInteractions(sessions,identities);}}
 @Test void unresolvedCaptchaAndUnregisteredIdentityAreDenied()throws Exception{fixture("UAT_20261109033718462");try(var scope=mockStatic(UatResourceScope.class)){scope.when(()->UatResourceScope.registered(settings)).thenReturn(temp);when(captcha.check(eq(uuid),any())).thenReturn(false);assertThrows(SessionException.class,()->service.login(SessionDomain.RESIDENT,username,password,uuid,"127.0.0.1"));verifyNoInteractions(members,sessions);}}
}
