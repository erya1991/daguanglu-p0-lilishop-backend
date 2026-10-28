package cn.lili.modules.community.p0.channel;

import cn.lili.modules.community.p0.support.*;
import com.google.gson.JsonObject;
import java.net.URI;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Disclosure only. No navigation, signing, account verification or money movement is performed here.
 * Target source: https://pay.wechatpay.cn/doc/v3/partner/4012691376 (ecommerce query response).
 */
public final class ChannelUserActions {
    private ChannelUserActions(){}
    private static String action(JsonObject response){var f=new Fields(response);String state=f.text("applyment_state",1,80,false);return "ACCOUNT_NEED_VERIFY".equals(state)?"LEGAL_VERIFICATION":"NEED_SIGN".equals(state)||"REJECTED".equals(state)&&response.has("wxpay_sign_required")&&response.get("wxpay_sign_required").isJsonPrimitive()&&response.get("wxpay_sign_required").getAsJsonPrimitive().isBoolean()&&response.get("wxpay_sign_required").getAsBoolean()?"ADMIN_SIGNING":"REJECTED".equals(state)?"CORRECT_MATERIALS":"NONE";}
    public static String safe(String value){return value==null?null:value.replaceAll("https?://\\S+","[protected link]").replaceAll("[0-9]{6,}","[masked]");}
    public static Map<String,Object> summary(JsonObject response){var f=new Fields(response);String action=action(response),url=candidate(response,action);return map("action",action,"description",safe(f.text("applyment_state_desc",0,1024,false)),"signState",f.text("sign_state",0,32,false),"details",f.list("audit_detail",0,100,false).asList().stream().map(item->{var detail=new Fields(item);return map("field",safe(detail.text("param_name",0,200,false)),"reason",safe(detail.text("reject_reason",0,1024,false)));}).toList(),"externalLinkAvailable",allowed(url),"linkState",url==null?"NOT_SUPPLIED":allowed(url)?"PROTECTED":"TARGET_UNVERIFIED");}
    public static Map<String,Object> disclose(JsonObject response,String mode){String action=action(response);Stage1Exception.require(Set.of("LEGAL_VERIFICATION","ADMIN_SIGNING").contains(action),409,"CHANNEL_ACTION_NOT_REQUIRED");String url=candidate(response,action);if("MOCK".equals(mode))return map("action",action,"mode","MOCK","url",null,"instruction","本地模拟待办；不会生成或打开真实签约、账户验证入口。");Stage1Exception.require(allowed(url),409,"CHANNEL_ACTION_TARGET_UNVERIFIED");String applyment=new Fields(response).text("applyment_id",1,64,true);Stage1Exception.require(Arrays.asList(URI.create(url).getRawQuery().split("&")).contains("applymentId="+applyment),409,"CHANNEL_ACTION_IDENTITY_MISMATCH");return map("action",action,"mode","REAL","url",url,"instruction",action.equals("ADMIN_SIGNING")?"仅由该申请单的微信渠道管理员完成签约，平台人员不能代签。":"仅由商户法人完成微信账户验证，平台人员不能代替确认。");}
    private static String candidate(JsonObject response,String action){return new Fields(response).text(action.equals("LEGAL_VERIFICATION")?"legal_validation_url":"sign_url",1,256,false);}
    static boolean allowed(String value){if(value==null||value.contains("\\")||value.chars().anyMatch(c->c<33||c>126))return false;try{URI uri=URI.create(value);return "https".equals(uri.getScheme())&&"pay.weixin.qq.com".equals(uri.getHost())&&uri.getRawUserInfo()==null&&uri.getPort()==-1&&uri.getRawFragment()==null&&"/public/apply4ec_sign/s".equals(uri.getRawPath())&&uri.getRawQuery()!=null&&uri.getRawQuery().matches("(?:applymentId=[0-9]{1,64}&sign=[A-Za-z0-9_-]{16,128}|sign=[A-Za-z0-9_-]{16,128}&applymentId=[0-9]{1,64})");}catch(IllegalArgumentException invalid){return false;}}
}
