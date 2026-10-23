package cn.lili.common.security.session;

/** Return directly to the authenticated caller; never log or persist this value. */
public record SessionTokens(String sid, String accessToken, String refreshToken,
                            long accessExpiresAt, long absoluteExpiresAt) {
    @Override
    public String toString() {
        return "SessionTokens[REDACTED]";
    }
}
