package cn.lili.modules.community.p0.stage45;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.stage2.Stage2Routes;
import java.util.*;
import java.util.regex.Pattern;

public final class Stage45Routes {
    public static final String BUYER="/buyer/p0/s45",STORE="/store/p0/s45",MANAGER="/manager/p0/s45";
    public record Route(SessionDomain domain,String method,Pattern pattern,String action) {}
    private static final List<Route> ROUTES=new ArrayList<>();
    static {
        for(var d:List.of(SessionDomain.RESIDENT,SessionDomain.STORE,SessionDomain.MANAGER)) {
            String b=base(d);
            add(d,"GET",b+"/context","context.read");
            add(d,"GET",b+"/operations/{op}","context.read");
            add(d,"POST",b+"/operations/{op}/abandon","context.read");
            add(d,"GET",b+"/aftersales","aftersale.read");
            add(d,"GET",b+"/aftersales/{id}","aftersale.read");
            add(d,"GET",b+"/orders/{id}/eligibility","aftersale.read");
            add(d,"GET",b+"/evaluations","evaluation.read");
            add(d,"GET",b+"/evaluations/{id}","evaluation.read");
            add(d,"GET",b+"/evaluation-summary","evaluation.read");
            if(d!=SessionDomain.MANAGER){add(d,"GET",b+"/messages","message.read");add(d,"POST",b+"/messages/{id}/read","message.read");}
            add(d,"POST",b+"/attachments","attachment.write");
            add(d,"GET",b+"/attachments/{id}","attachment.read");
            add(d,"GET",b+"/orders/{id}/items/{id}/image","attachment.read");
        }
        add(SessionDomain.RESIDENT,"POST",BUYER+"/orders/{id}/aftersales","aftersale.apply");
        for(String a:List.of("cancel","return","dispute","evidence"))add(SessionDomain.RESIDENT,"POST",BUYER+"/aftersales/{id}/"+a,"aftersale."+a);
        add(SessionDomain.RESIDENT,"POST",BUYER+"/orders/{id}/evaluations","evaluation.write");
        add(SessionDomain.RESIDENT,"GET",BUYER+"/goods/{id}/evaluations","evaluation.public");
        for(String a:List.of("approve","reject"))add(SessionDomain.STORE,"POST",STORE+"/aftersales/{id}/"+a,"aftersale.review");
        for(String a:List.of("receive","exception"))add(SessionDomain.STORE,"POST",STORE+"/aftersales/{id}/"+a,"aftersale.receive");
        add(SessionDomain.MANAGER,"POST",MANAGER+"/aftersales/{id}/resolve","aftersale.resolve");
        add(SessionDomain.MANAGER,"POST",MANAGER+"/evaluations/{id}/moderate","evaluation.moderate");
        for(var d:List.of(SessionDomain.STORE,SessionDomain.MANAGER)) {
            add(d,"GET",base(d)+"/funds","funds.read");
            add(d,"GET",base(d)+"/orders/{id}/funds","funds.read");
        }
        add(SessionDomain.MANAGER,"GET",MANAGER+"/channels","channel.read");
        add(SessionDomain.MANAGER,"GET",MANAGER+"/channels/{id}","channel.read");
        add(SessionDomain.MANAGER,"POST",MANAGER+"/channels/{id}/sync","channel.sync");
        add(SessionDomain.MANAGER,"GET",MANAGER+"/rules","rule.read");
        add(SessionDomain.MANAGER,"GET",MANAGER+"/rules/{id}","rule.read");
        for(var d:List.of(SessionDomain.STORE,SessionDomain.MANAGER))add(d,"GET",base(d)+"/tasks/{id}","funds.read");
        add(SessionDomain.MANAGER,"GET",MANAGER+"/exceptions","funds.read");
        add(SessionDomain.MANAGER,"POST",MANAGER+"/tasks/{id}/recover","funds.recover");
        add(SessionDomain.MANAGER,"POST",MANAGER+"/orders/{id}/late-refund","funds.recover");
        add(SessionDomain.MANAGER,"GET",MANAGER+"/reconciliation-accounts","reconciliation.read");
        add(SessionDomain.MANAGER,"GET",MANAGER+"/reconciliations","reconciliation.read");
        add(SessionDomain.MANAGER,"GET",MANAGER+"/reconciliations/{id}","reconciliation.read");
        add(SessionDomain.MANAGER,"POST",MANAGER+"/reconciliations","reconciliation.create");
        add(SessionDomain.MANAGER,"POST",MANAGER+"/reconciliation-lines/{id}/review","reconciliation.review");
    }
    private Stage45Routes() {}
    public static String base(SessionDomain d){return d==SessionDomain.RESIDENT?BUYER:d==SessionDomain.STORE?STORE:MANAGER;}
    private static void add(SessionDomain d,String m,String p,String a){ROUTES.add(new Route(d,m,Pattern.compile("^"+p.replace("{id}","[1-9][0-9]{0,18}").replace("{op}","[A-Za-z0-9_-]{8,64}")+"$"),a));}
    public static boolean path(String p){return p!=null&&List.of(BUYER,STORE,MANAGER).stream().anyMatch(b->p.equals(b)||p.startsWith(b+"/"));}
    public static Route find(SessionDomain d,String m,String p){return Stage2Routes.canonical(p)?ROUTES.stream().filter(r->r.domain()==d&&r.method().equals(m)&&r.pattern().matcher(p).matches()).findFirst().orElse(null):null;}
    public static boolean allowed(String m,String p){return Arrays.stream(SessionDomain.values()).anyMatch(d->find(d,m,p)!=null);}
    public static List<Route> all(){return List.copyOf(ROUTES);}
}
