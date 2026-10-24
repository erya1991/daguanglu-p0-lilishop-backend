package cn.lili.common.security.session.http;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.permission.manager.ManagerAccessPolicy;
import jakarta.servlet.http.HttpServletRequest;

/** Only Session transport and existing identity reads are activated in P02-2. */
public final class SessionRoutes {
    public static final String RESIDENT_LOGIN = "/buyer/passport/connect/miniProgram/login";
    public static final String RESIDENT_EDIT = "/buyer/passport/member/editOwn";
    public static final String RESIDENT_PHONE = "/buyer/passport/member/wechat-phone";
    public static final String RESIDENT_AVATAR = "/buyer/common/upload/avatar";
    public static final String RESIDENT_LINK = "/buyer/passport/connect/miniProgram/link-existing";
    public static final String APPLICANT = "/store/passport/applicant";
    public static final String MERCHANT_LOGIN = "/store/passport/wechat/login";
    public static final String MANAGER_LOGIN = "/manager/passport/user/login";
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
        return managerManagementPath(path) || residentWritePath(path) || path.equals("/buyer/passport/member") || path.equals("/manager/passport/user/info")
                || path.equals("/manager/permission/menu/memberMenu")
                || path.equals("/store/member/user") || path.equals("/store/member/user/info")
                || path.equals(logout(SessionDomain.RESIDENT)) || path.equals(logout(SessionDomain.STORE))
                || path.equals(logout(SessionDomain.MANAGER)) || path.startsWith(APPLICANT + "/");
    }

    /** Authentication floor for existing W01 Controllers, even under a broad historical ignore.
     * This grants no operation; the current backend permissions must still authorize each request.
     */
    public static boolean managerManagementPath(String path) {
        String account = "/manager/passport/user";
        if (path.equals(account) || path.startsWith(account + "/")) {
            return !path.equals(account + "/login") && !path.equals(account + "/refresh")
                    && !path.startsWith(account + "/refresh/");
        }
        for (String resource : new String[]{"role", "userRole", "roleMenu", "menu", "departmentRole"}) {
            String base = "/manager/permission/" + resource;
            if (path.equals(base) || path.startsWith(base + "/")) return true;
        }
        return false;
    }

    /** Only W01 mutations and their confirmed equivalent legacy entrances. */
    public static boolean managerMaintenanceWrite(String method, String path) {
        String account = "/manager/passport/user";
        if ((method.equals("PUT") && path.equals(account + "/admin/edit"))
                || (method.equals("POST") && path.equals(account + "/register"))
                || (method.equals("PUT") && oneSegment(account + "/enable/", path))
                || (method.equals("POST") && oneSegment(account + "/resetPassword/", path))
                || (method.equals("DELETE") && oneSegment(account + "/", path))) return true;
        String permission = "/manager/permission/";
        if (method.equals("PUT") && oneSegment(permission + "userRole/", path)) return true;
        if (method.equals("POST") && oneSegment(permission + "roleMenu/", path)) return true;
        for (String resource : new String[]{"role", "menu", "departmentRole"}) {
            String base = permission + resource;
            if (method.equals("POST") && path.equals(base)) return true;
            if ((method.equals("POST") || method.equals("PUT") || method.equals("DELETE"))
                    && oneSegment(base + "/", path)) return true;
        }
        return false;
    }
    private static boolean oneSegment(String prefix, String path) {
        return path.startsWith(prefix) && path.length() > prefix.length()
                && path.indexOf('/', prefix.length()) == -1;
    }

    public static boolean allowed(SessionDomain domain, String method, String path) {
        if (domain == SessionDomain.MANAGER) return ManagerAccessPolicy.allowedRoute(method, path);
        if (method.equals("POST") && path.equals(logout(domain))) return true;
        if(domain==SessionDomain.RESIDENT && residentWritePath(path)) return method.equals(path.equals(RESIDENT_EDIT)?"PUT":"POST");
        if (!method.equals("GET")) return false;
        return switch (domain) {
            case RESIDENT -> path.equals("/buyer/passport/member");
            case APPLICANT -> path.equals(APPLICANT + "/identity");
            case STORE -> path.equals("/store/member/user") || path.equals("/store/member/user/info");
            case MANAGER -> path.equals("/manager/passport/user/info") || path.equals("/manager/permission/menu/memberMenu");
        };
    }

    public static boolean residentWritePath(String path) {
        return path.equals(RESIDENT_EDIT) || path.equals(RESIDENT_PHONE) || path.equals(RESIDENT_AVATAR) || path.equals(RESIDENT_LINK);
    }

    /** These Stage 0 reads expose only the newly verified merchant identity, never a legacy JWT. */
    public static boolean merchantIdentityPath(String path) {
        return path.equals("/store/member/user") || path.equals("/store/member/user/info");
    }

    public static boolean isLegacyRefresh(String method, String path, SessionDomain domain) {
        return method.equals("GET") && path.startsWith(refresh(domain) + "/");
    }
}
