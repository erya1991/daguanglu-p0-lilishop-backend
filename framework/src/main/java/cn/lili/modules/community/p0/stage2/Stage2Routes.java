package cn.lili.modules.community.p0.stage2;

import cn.lili.common.security.session.SessionDomain;
import java.util.*;
import java.util.regex.Pattern;

/** Closed method/path/domain catalogue. Merely matching the prefix grants nothing. */
public final class Stage2Routes {
    public static final String BASE = "/buyer/p0/s2";
    public record Route(String method, String template, String action, Pattern pattern) { }
    private static final List<Route> ROUTES = new ArrayList<>();
    static {
        for (String path : List.of("/regions", "/catalog", "/goods", "/goods/{id}", "/goods/{id}/images/{fileId}", "/cart", "/addresses", "/addresses/{id}", "/checkout/intents/{key}", "/pending-transactions", "/trades/{id}/summary")) add("GET", path, "read");
        add("POST", "/cart/items", "cart.write"); add("PUT", "/cart/items/{id}", "cart.write"); add("POST", "/cart/remove", "cart.write");
        add("POST", "/addresses", "address.write"); add("PUT", "/addresses/{id}", "address.write"); add("DELETE", "/addresses/{id}", "address.write");
        add("POST", "/addresses/{id}/default", "address.write"); add("POST", "/addresses/default/clear", "address.write");
        add("POST", "/checkout/options", "read"); add("POST", "/checkout/quotes", "quote"); add("POST", "/checkout/intents", "create");
        for (String action : List.of("payment/prepare", "payment/launch", "payment/refresh", "cancel")) add("POST", "/trades/{id}/" + action, action);
    }
    private Stage2Routes() { }
    private static void add(String method, String path, String action) {
        String template = BASE + path;
        String regex = Pattern.quote(template).replace("{id}", "\\E[1-9][0-9]{0,18}\\Q").replace("{fileId}", "\\E[1-9][0-9]{0,18}\\Q").replace("{key}", "\\E[A-Za-z0-9_-]{8,64}\\Q");
        ROUTES.add(new Route(method, template, action, Pattern.compile(regex)));
    }
    public static boolean path(String path) { return path != null && (path.equals(BASE) || path.startsWith(BASE + "/")); }
    public static boolean canonical(String path) { return path != null && !path.contains("%") && !path.contains(";") && !path.contains("//") && !path.contains("\\") && !path.contains(".."); }
    public static Route find(SessionDomain domain, String method, String path) {
        if (domain != SessionDomain.RESIDENT || !canonical(path)) return null;
        return ROUTES.stream().filter(r -> r.method.equals(method) && r.pattern.matcher(path).matches()).findFirst().orElse(null);
    }
    public static List<Route> all() { return List.copyOf(ROUTES); }
}
