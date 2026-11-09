package cn.lili.modules.community.p0.uat;
import cn.lili.common.security.session.*;
import cn.lili.common.security.session.http.SessionApplications;
import cn.lili.modules.community.p0.stage2.Stage2Settings;
import cn.lili.modules.connect.wechat.WechatIdentityService;
import cn.lili.modules.member.service.MemberService;
import cn.lili.modules.member.merchant.MerchantAuthorizationService;
import cn.lili.modules.verification.entity.enums.VerificationEnums;
import cn.lili.modules.verification.service.VerificationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import lombok.RequiredArgsConstructor;
import java.nio.file.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Explicit loopback UAT adapter. Only password-authenticated, run-registered Member mappings can enter the existing session resolver. */
@Service @RequiredArgsConstructor
@ConditionalOnProperty(name={"p0.uat.enabled","p0.uat.local-login-enabled"},havingValue="true")
public class UatMemberLoginService {
    private final Stage2Settings resources;
    private final MemberService members;
    private final VerificationService captcha;
    private final WechatIdentityService identities;
    private final MerchantAuthorizationService merchants;
    private final SessionApplications applications;
    private final SessionService sessions;
    public record Result(String memberId,String identity,String role,SessionTokens session,String source) {
        @Override public String toString(){return "UatLoginResult[REDACTED]";}
    }
    public Result login(SessionDomain requested,String username,String password,String uuid,String remote) {
        try {
            Path run=UatResourceScope.registered(resources);
            require(Set.of("127.0.0.1","::1","0:0:0:0:0:0:0:1").contains(remote));
            require(requested==SessionDomain.RESIDENT||requested==SessionDomain.APPLICANT);
            require(username!=null&&username.matches("[A-Za-z0-9_-]{3,64}")&&password!=null&&password.length()>=8&&password.length()<=128&&uuid!=null&&uuid.matches("[A-Za-z0-9-]{16,64}"));
            require(captcha.check(uuid,VerificationEnums.LOGIN));
            var member=members.findByUsername(username);
            require(member!=null&&Boolean.TRUE.equals(member.getDisabled())&&Boolean.FALSE.equals(member.getDeleteFlag())
                &&new BCryptPasswordEncoder().matches(cn.lili.common.utils.StringUtils.md5(password),member.getPassword()));
            String app=applications.application(requested);
            var registry=object(Files.readString(run.resolve("private/uat-identities.json")));
            require(resources.getRunId().equals(registry.get("runId").getAsString()));
            var matches=registry.getAsJsonArray("entries").asList().stream().map(v->v.getAsJsonObject()).filter(v->username.equals(v.get("username").getAsString())&&app.equals(v.get("application").getAsString())).toList();
            require(matches.size()==1);var entry=matches.getFirst();
            require("UAT_SYNTHETIC".equals(entry.get("source").getAsString())&&member.getId().equals(entry.get("memberId").getAsString()));
            // Binding/version checks are identical to normal sessions. Store, Clerk and role are resolved on the server.
            var personal=new SessionIdentity(member.getId(),requested,app,entry.get("bindingId").getAsString(),entry.get("bindingVersion").getAsLong());
            identities.verify(personal);
            String role=null;var identity=personal;
            if(requested==SessionDomain.APPLICANT){var current=merchants.resolve(personal);identity=new SessionIdentity(personal.subject(),current.domain(),app,personal.bindingId(),personal.bindingVersion());role=current.domain()==SessionDomain.STORE?current.role().name():null;}
            return new Result(identity.subject(),identity.domain().name(),role,sessions.create(identity),"UAT_SYNTHETIC");
        }catch(SessionException e){throw e;}catch(Exception e){throw new SessionException(SessionException.Code.DENIED);}
    }
    private static void require(boolean ok){if(!ok)throw new SessionException(SessionException.Code.DENIED);}
}
