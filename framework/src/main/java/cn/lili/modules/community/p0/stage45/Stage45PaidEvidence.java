package cn.lili.modules.community.p0.stage45;
import cn.lili.modules.community.p0.stage2.*;import cn.lili.modules.community.p0.support.*;import com.google.gson.*;import org.springframework.stereotype.Component;import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;import org.springframework.beans.factory.ObjectProvider;import static cn.lili.modules.community.p0.support.Stage1Json.*;
/** Same evidence contract for both modes. Missing REAL source never falls back to Mock or a PAID enum. */
@Component @ConditionalOnProperty(name="p0.stage45.enabled",havingValue="true")
public class Stage45PaidEvidence {
 private final PersistentPaymentMock mock;private final ObjectProvider<Stage45RealPaidSource> real;
 public Stage45PaidEvidence(PersistentPaymentMock mock,ObjectProvider<Stage45RealPaidSource> real){this.mock=mock;this.real=real;}
 public boolean trusted(String mode,JsonObject request,JsonObject result){try{if(!mode.equals(request.get("mode").getAsString())||!mode.equals(result.get("mode").getAsString()))return false;if("MOCK".equals(mode))return mock.trusted(result);var source=real.getIfAvailable();return "REAL".equals(mode)&&source!=null&&source.trusted(request,result);}catch(RuntimeException e){return false;}}
 public JsonObject require(String mode,JsonObject request,JsonObject result){Stage1Exception.require(trusted(mode,request,result),409,"SC23_SOURCE_UNTRUSTED");return Stage2PaymentFacts.requireAuthenticated(result,request,mode);}
}
