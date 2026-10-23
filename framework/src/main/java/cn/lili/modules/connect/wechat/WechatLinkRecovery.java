package cn.lili.modules.connect.wechat;

/** Called only AFTER a fresh official WeChat Code verification. Null means no pending handoff. */
public interface WechatLinkRecovery {
    ResidentWechatLoginService.LoginResult recover(WechatCodeGateway.Identity authenticated);
}
