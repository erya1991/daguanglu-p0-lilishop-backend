package cn.lili.modules.connect.wechat;

/** Server-only authentication seam. No production mock/fallback implementation. */
@FunctionalInterface
public interface WechatCodeGateway {
    Identity authenticate(String loginCode);

    record Identity(String appId, String openId, String unionId) {
        public Identity {
            if (!valid(appId) || !valid(openId) || (unionId != null && !valid(unionId))) {
                throw new ResidentLoginException(ResidentLoginException.Code.WECHAT_REJECTED);
            }
        }
        private static boolean valid(String value) {
            return value != null && value.matches("[A-Za-z0-9_-]{1,128}");
        }
        @Override public String toString() { return "WechatIdentity[REDACTED]"; }
    }
}
