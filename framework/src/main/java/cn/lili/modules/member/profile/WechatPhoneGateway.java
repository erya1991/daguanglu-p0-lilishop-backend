package cn.lili.modules.member.profile;

/** phoneCode is a separate, user-consented WeChat capability, never a loginCode or client number. */
@FunctionalInterface
public interface WechatPhoneGateway {
    String verify(String phoneCode);
}
