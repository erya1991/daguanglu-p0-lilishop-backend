package cn.lili.common.security.session;

import java.time.Duration;

/** Stage 0 domains. These do not change the legacy UserEnums or token policy. */
public enum SessionDomain {
    RESIDENT, APPLICANT, STORE, MANAGER;

    public Duration absoluteLifetime() {
        return this == RESIDENT || this == STORE ? Duration.ofDays(15) : Duration.ofDays(1);
    }
}
