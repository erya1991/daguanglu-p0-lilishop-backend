package cn.lili.modules.permission.manager;

import cn.lili.common.security.session.SessionTokens;

/** Explicit wire marker; never log this credential response. */
public record ManagerSessionResponse(String sessionProtocol, String sid, String accessToken,
                                     String refreshToken, long accessExpiresAt, long absoluteExpiresAt) {
    public static final String PROTOCOL = "STAGE0_SESSION";
    public static ManagerSessionResponse from(SessionTokens tokens) {
        return new ManagerSessionResponse(PROTOCOL, tokens.sid(), tokens.accessToken(),
                tokens.refreshToken(), tokens.accessExpiresAt(), tokens.absoluteExpiresAt());
    }
    @Override public String toString() { return "ManagerSessionResponse[REDACTED]"; }
}
