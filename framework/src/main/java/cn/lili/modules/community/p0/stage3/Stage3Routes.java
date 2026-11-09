package cn.lili.modules.community.p0.stage3;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.stage2.Stage2Routes;
import java.util.*;
import java.util.regex.Pattern;

public final class Stage3Routes {
    private Stage3Routes() {}
    public static final String BUYER="/buyer/p0/s3", STORE="/store/p0/s3", MANAGER="/manager/p0/s3";
    public record Route(SessionDomain domain,String method,Pattern pattern,String action) {}
    private static final List<Route> ROUTES=new ArrayList<>();
    static {
        for(var domain:List.of(SessionDomain.RESIDENT,SessionDomain.STORE)) {
            String base=domain==SessionDomain.RESIDENT?BUYER:STORE;
            add(domain,"GET",base+"/orders","order.read");
            add(domain,"GET",base+"/orders/{id}","order.read");
            add(domain,"GET",base+"/operations/{op}","order.read");
            add(domain,"GET",base+"/messages","message.read");
            add(domain,"POST",base+"/messages/{id}/read","message.read");
        }
        add(SessionDomain.RESIDENT,"GET",BUYER+"/orders/{id}/credential","order.read");
        add(SessionDomain.RESIDENT,"POST",BUYER+"/orders/{id}/receive","order.receive");
        for(String action:List.of("prepare","dispatch","delivered","ship","exceptions"))
            add(SessionDomain.STORE,"POST",STORE+"/orders/{id}/"+action,action.equals("exceptions")?"order.exception":"fulfillment.write");
        add(SessionDomain.STORE,"GET",STORE+"/context","context.read");
        add(SessionDomain.STORE,"GET",STORE+"/points","verification.lookup");
        add(SessionDomain.STORE,"GET",STORE+"/workbench","order.read");
        add(SessionDomain.STORE,"POST",STORE+"/verify/lookup","verification.lookup");
        add(SessionDomain.STORE,"POST",STORE+"/verify/confirm","verification.confirm");
        add(SessionDomain.MANAGER,"GET",MANAGER+"/trades","order.read");
        add(SessionDomain.MANAGER,"GET",MANAGER+"/orders/{id}","order.read");
        add(SessionDomain.MANAGER,"GET",MANAGER+"/verifications","verification.read");
        add(SessionDomain.MANAGER,"GET",MANAGER+"/verifications/{id}","verification.read");
    }
    private static void add(SessionDomain d,String method,String path,String action) {
        String regex=path.replace("{id}","[1-9][0-9]{0,18}").replace("{op}","[A-Za-z0-9_-]{8,64}");
        ROUTES.add(new Route(d,method,Pattern.compile("^"+regex+"$"),action));
    }
    public static boolean path(String p) { return p!=null&&(p.equals(BUYER)||p.startsWith(BUYER+"/")||p.equals(STORE)||p.startsWith(STORE+"/")||p.equals(MANAGER)||p.startsWith(MANAGER+"/")); }
    public static Route find(SessionDomain d,String method,String path) {
        if(!Stage2Routes.canonical(path))return null;
        return ROUTES.stream().filter(r->r.domain()==d&&r.method().equals(method)&&r.pattern().matcher(path).matches()).findFirst().orElse(null);
    }
    public static boolean allowed(String method,String path) { return Arrays.stream(SessionDomain.values()).anyMatch(d->find(d,method,path)!=null); }
    public static List<Route> all() { return List.copyOf(ROUTES); }
}
