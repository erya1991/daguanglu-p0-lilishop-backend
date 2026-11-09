package cn.lili.modules.permission.manager;

import java.util.*;
import java.util.regex.Pattern;

/** Transport ceiling for the integrated native settings. URL grants cannot reopen old commerce writes. */
public final class NativeManagerRoutes {
    private record Route(String method, Pattern path) { }
    private static final List<Route> ROUTES = new ArrayList<>();
    static {
        add("GET", "/manager/passport/user/(info|getByCondition)");
        add("PUT", "/manager/passport/user/(edit|admin/edit|editPassword|enable/[0-9]+)");
        add("POST", "/manager/passport/user/(register|logout|resetPassword/[0-9,]+)");
        add("DELETE", "/manager/passport/user/[0-9,]+");
        for (String kind : List.of("menu", "storeMenu", "role", "department")) {
            String base="/manager/permission/"+kind;
            add("GET",base); add("POST",base); add("PUT",base+"/[0-9]+"); add("DELETE",base+"/[0-9,]+");
        }
        add("GET", "/manager/permission/(menu|storeMenu)/tree");
        add("GET", "/manager/permission/menu/memberMenu");
        add("GET", "/manager/permission/department/[0-9]+");
        add("GET", "/manager/permission/(userRole|roleMenu|departmentRole)/[0-9]+");
        add("PUT", "/manager/permission/(userRole|departmentRole)/[0-9]+");
        add("POST", "/manager/permission/roleMenu/[0-9]+");
        add("GET", "/manager/setting/setting/get/[A-Z_]+");
        add("PUT", "/manager/setting/setting/put/[A-Z_]+");
        add("GET", "/manager/setting/settingx/paymentSupport");
        add("GET", "/manager/setting/messageTemplate/page");
        add("GET", "/manager/other/message(/recipients)?");
        add("POST", "/manager/other/message");
        add("DELETE", "/manager/other/message/[0-9]+");
        add("GET", "/manager/other/(memberMessage|storeMessage)");
        add("GET", "/manager/wechat/(wechatMessage|wechatMPMessage)(/[0-9]+)?");
        add("PUT", "/manager/wechat/(wechatMessage|wechatMPMessage)/[0-9]+");
        add("GET", "/manager/setting/noticeMessage(/[0-9]+)?");
        add("PUT", "/manager/setting/noticeMessage/[0-9]+(/(email/)?(true|false|OPEN|CLOSE))?");
        add("GET", "/manager/setting/region/(item/)?[0-9]+");
        add("PUT", "/manager/setting/region/[0-9]+");
        add("POST", "/manager/setting/region/save");
        add("DELETE", "/manager/setting/region/[0-9,]+");
        add("GET", "/manager/setting/log/getAllByPage");
        add("DELETE", "/manager/setting/log(/[0-9,]+)?");
        add("GET", "/manager/setting/timed-task");
        // Legacy scheduled order/stock/refund/fund mutations are intentionally not admitted.
        for (String kind : List.of("appVersion", "verificationSource", "sensitiveWords", "logistics")) {
            String base="/manager/other/"+kind;
            add("GET",base+"(/[0-9]+)?"); add("POST",base); add("PUT",base+"/[0-9]+");
            add("DELETE",base+(kind.equals("sensitiveWords")?"/delByIds":"")+"/[0-9,]+");
        }
        add("GET", "/manager/other/logistics/getByPage");
        add("GET", "/manager/common/file(Directory)?");
        add("POST", "/manager/common/file/rename");
        add("POST", "/manager/common/upload/file");
        add("DELETE", "/manager/common/file/delete/[0-9,]+");
        add("POST", "/manager/common/fileDirectory");
        add("PUT", "/manager/common/fileDirectory");
        add("DELETE", "/manager/common/fileDirectory/[0-9]+");
        add("GET", "/manager/sms/sms(/[0-9]+)?");
        add("GET", "/manager/sms/sign/(querySmsSignPage|[0-9]+)");
        add("GET", "/manager/sms/template/querySmsTemplatePage");
    }
    private NativeManagerRoutes() { }
    private static void add(String method,String pattern) { ROUTES.add(new Route(method,Pattern.compile(pattern))); }
    public static boolean allowed(String method,String path) {
        return path!=null && !path.contains("//") && ROUTES.stream().anyMatch(r->r.method.equals(method)&&r.path.matcher(path).matches());
    }
    public static boolean self(String method,String path) {
        return method.equals("GET") && Set.of("/manager/passport/user/info","/manager/permission/menu/memberMenu").contains(path)
            || method.equals("POST") && path.equals("/manager/passport/user/logout")
            || method.equals("PUT") && Set.of("/manager/passport/user/edit","/manager/passport/user/editPassword").contains(path);
    }
    public static boolean authorityMutation(String method,String path) {
        return !method.equals("GET") && allowed(method,path) && (path.startsWith("/manager/permission/") || path.startsWith("/manager/passport/user/"));
    }
    public static boolean targetParameter(String method,String path,String name) {
        return name.equals("userId") && (method.equals("GET") && path.equals("/manager/passport/user/getByCondition"));
    }
}
