package cn.lili.modules.community.p0.channel;
import cn.lili.modules.community.p0.support.Stage1Exception;
import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
class ChannelUserActionsTest {
    private final String url="https://pay.weixin.qq.com/public/apply4ec_sign/s?applymentId=1234567890&sign=0123456789abcdef";
    @Test void linksAreDisclosedOnlyForCurrentRequiredActionAndNotInNormalSummary(){
        var response=object(map("applyment_id","1234567890","applyment_state","NEED_SIGN","sign_url",url,"sign_state","UNSIGNED","applyment_state_desc","请通过 "+url+" 验证 13800138000","audit_detail",List.of()));
        assertFalse(write(ChannelUserActions.summary(response)).contains("0123456789abcdef"));assertFalse(write(ChannelUserActions.summary(response)).contains("13800138000"));
        assertEquals(url,ChannelUserActions.disclose(response,"REAL").get("url"));assertNull(ChannelUserActions.disclose(response,"MOCK").get("url"));
        response.addProperty("applyment_state","FINISH");assertThrows(Stage1Exception.class,()->ChannelUserActions.disclose(response,"REAL"));
        response.addProperty("applyment_state","REJECTED");assertThrows(Stage1Exception.class,()->ChannelUserActions.disclose(response,"REAL"));response.addProperty("wxpay_sign_required",true);assertEquals(url,ChannelUserActions.disclose(response,"REAL").get("url"));
        response.addProperty("applyment_id","1234567891");assertEquals("CHANNEL_ACTION_IDENTITY_MISMATCH",assertThrows(Stage1Exception.class,()->ChannelUserActions.disclose(response,"REAL")).getMessage());
    }
    @Test void malformedRedirectAndUnreviewedTargetsFailClosed(){
        for(String value:List.of(url.replace("https:","http:"),url.replace("pay.weixin.qq.com","pay.weixin.qq.com.evil.test"),url.replace("pay.weixin.qq.com","evil.test@pay.weixin.qq.com"),url+"&redirect=https://evil.test",url+"#secret",url.replace("/public/","/%70ublic/"),url.replace("/public/","/public/../"),url.replace("?","?callback=%2f%2fevil.test&"),url.replace("pay.weixin.qq.com","pay.weixin.qq.com:443")))assertFalse(ChannelUserActions.allowed(value));
        assertTrue(ChannelUserActions.allowed(url));assertThrows(Stage1Exception.class,()->ChannelUserActions.disclose(object(map("applyment_state","ACCOUNT_NEED_VERIFY","legal_validation_url","https://evil.test")),"REAL"));
    }
}
