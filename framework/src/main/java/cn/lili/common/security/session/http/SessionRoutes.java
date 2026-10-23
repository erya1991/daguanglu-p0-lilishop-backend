package cn.lili.common.security.session.http;

import cn.lili.common.security.session.SessionDomain;
import jakarta.servlet.http.HttpServletRequest;

/** Only Session transport and existing identity reads are activated in P02-2. */
public final class SessionRoutes {
    public static final String APPLICANT = "/store/passport/applicant";
    private SessionRoutes() { }

    public static String refresh(SessionDomain domain) { return base(domain) + "/refresh"; }
    public static String logout(SessionDomain domain) { return base(domain) + "/logout"; }
    private static String base(SessionDomain domain) {
        return switch (domain) {
            case RESIDENT -> "/buyer/passport/member";
            case APPLICANT -> APPLICANT;
            case STORE -> "/store/passport/login";
            case MANAGER -> "/manager/passport/user";
        };
    }

    public static boolean requiresAuthentication(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.equals("/buyer/passport/member") || path.equals("/manager/passport/user/info")
                || path.equals("/manager/permission/menu/memberMenu")
                || path.equals("/store/member/user") || path.equals("/store/member/user/info")
                || path.equals(logout(SessionDomain.RESIDENT)) || path.equals(logout(SessionDomain.STORE))
                || path.equals(logout(SessionDomain.MANAGER)) || path.startsWith(APPLICANT + "/");
    }

    public static boolean allowed(SessionDomain domain, String method, String path) {
        if (method.equals("POST") && path.equals(logout(domain))) return true;
        if (!method.equals("GET")) return false;
        return switch (domain) {
            case RESIDENT -> path.equals("/buyer/passport/member");
            case APPLICANT -> path.equals(APPLICANT + "/identity");
            case STORE -> path.equals("/store/member/user") || path.equals("/store/member/user/info");
            case MANAGER -> path.equals("/manager/passport/user/info") || path.equals("/manager/permission/menu/memberMenu");
        };
    }

    public static boolean isLegacyRefresh(String method, String path, SessionDomain domain) {
        return method.equals("GET") && path.startsWith(refresh(domain) + "/");
    }
}
