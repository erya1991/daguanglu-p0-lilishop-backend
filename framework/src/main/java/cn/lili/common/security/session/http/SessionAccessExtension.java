package cn.lili.common.security.session.http;

import cn.lili.common.security.AuthUser;
import cn.lili.common.security.session.SessionRecord;

/** Optional application policy; never replaces verification or grants a legacy-token route. */
public interface SessionAccessExtension {
    boolean ownsManager(String id);
    void checkManager(String id);
    boolean checkRoute(AuthUser user, String method, String path, boolean verified);
    void checkRecord(SessionRecord record);
}
