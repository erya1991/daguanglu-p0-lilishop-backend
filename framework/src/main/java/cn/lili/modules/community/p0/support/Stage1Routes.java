package cn.lili.modules.community.p0.support;

import cn.lili.common.security.session.SessionDomain;
import java.util.*;
import java.util.regex.Pattern;

/** Finite method/path/action catalogue. A matching shape is never an authorization decision. */
public final class Stage1Routes {
    public static final String MANAGER="/manager/p0/s1", STORE="/store/p0/s1",
            APPLICANT="/store/passport/applicant/p0/s1", BUYER="/buyer/p0/s1";
    public record Route(SessionDomain domain,String method,String template,String action,Pattern pattern) { }
    private static final List<Route> ROUTES=new ArrayList<>();
    static {
        add(SessionDomain.MANAGER,"POST","/residents/search","resident.read");
        add(SessionDomain.MANAGER,"GET","/residents/{id}","resident.read");
        add(SessionDomain.MANAGER,"GET","/residents/{id}/orders","resident.read");
        add(SessionDomain.MANAGER,"GET","/residents/{id}/aftersales","resident.read");
        add(SessionDomain.MANAGER,"GET","/residents/{id}/impact","resident.manage");
        add(SessionDomain.MANAGER,"POST","/residents/{id}/disable","resident.manage");
        add(SessionDomain.MANAGER,"POST","/residents/{id}/enable","resident.manage");
        add(SessionDomain.STORE,"PUT","/goods/{id}/stock","stock.write");
        read(SessionDomain.MANAGER,"ui/stage2-goods","goods.read");
        add(SessionDomain.MANAGER,"GET","/ui/stage2-stores","goods.read");
        // Stage1 Manager presentation: read-only, finite routes, original action grants.
        for(String name:List.of("applications"))read(SessionDomain.MANAGER,"ui/"+name,"admission.review");
        read(SessionDomain.MANAGER,"ui/stores","store.read");
        for(String name:List.of("staff","history"))add(SessionDomain.MANAGER,"GET","/ui/stores/{id}/"+name,"store.read");
        add(SessionDomain.MANAGER,"GET","/ui/stores/{id}/point","fulfillment.inspect");
        read(SessionDomain.MANAGER,"ui/goods","goods.read");
        add(SessionDomain.MANAGER,"GET","/ui/reviews","goods.review");
        add(SessionDomain.MANAGER,"GET","/ui/lookups/stores","store.read");
        add(SessionDomain.MANAGER,"GET","/ui/lookups/agreements","agreement.read");
        add(SessionDomain.MANAGER,"GET","/ui/lookups/categories","store.read");
        add(SessionDomain.MANAGER,"GET","/ui/lookups/units","goods.read");
        for(String name:List.of("channel-configs","receivers"))add(SessionDomain.MANAGER,"GET","/ui/lookups/"+name,"channel.receiver");
        add(SessionDomain.MANAGER,"GET","/ui/region-names","region.read");
        add(SessionDomain.APPLICANT,"GET","/application","application.read");
        add(SessionDomain.APPLICANT,"GET","/admission-options","application.read");
        add(SessionDomain.STORE,"GET","/admission-options","profile.read");
        add(SessionDomain.APPLICANT,"POST","/application","application.write");
        add(SessionDomain.APPLICANT,"PUT","/application/{id}/draft","application.write");
        add(SessionDomain.APPLICANT,"POST","/application/{id}/submit","application.write");
        for(String action:List.of("accept","reject")) add(SessionDomain.APPLICANT,"POST","/invitations/"+action,"invitation.accept");
        add(SessionDomain.APPLICANT,"POST","/invitations/preview","invitation.accept");
        read(SessionDomain.MANAGER,"applications","admission.review");
        commands(SessionDomain.MANAGER,"applications","admission.review","approve","reject");
        read(SessionDomain.MANAGER,"stores","store.read");
        commands(SessionDomain.MANAGER,"stores","store.supervise","close","reopen");
        commands(SessionDomain.MANAGER,"qualifications","admission.review","revoke");
        add(SessionDomain.MANAGER,"GET","/stores/{id}/eligibility","store.read");
        for(String type:List.of("fulfillment","points")) add(SessionDomain.MANAGER,"GET","/stores/{id}/"+type,"fulfillment.inspect");
        add(SessionDomain.STORE,"GET","/profile","profile.read");
        add(SessionDomain.STORE,"PUT","/profile","profile.write");
        add(SessionDomain.STORE,"GET","/profile-change/current","profile.read");
        add(SessionDomain.STORE,"POST","/profile-change/submit","profile.write");
        read(SessionDomain.STORE,"qualifications","profile.read");
        add(SessionDomain.STORE,"POST","/qualifications","profile.write");
        add(SessionDomain.STORE,"PUT","/qualifications/{id}/draft","profile.write");
        commands(SessionDomain.STORE,"qualifications","profile.write","submit");
        read(SessionDomain.STORE,"staff-invitations","staff.manage");
        add(SessionDomain.STORE,"POST","/staff-invitations","staff.manage");
        commands(SessionDomain.STORE,"staff-invitations","staff.manage","revoke","confirm","reject");
        for(String action:List.of("accept","reject")) add(SessionDomain.STORE,"POST","/invitations/"+action,"invitation.accept");
        add(SessionDomain.STORE,"POST","/invitations/preview","invitation.accept");
        read(SessionDomain.STORE,"staff","staff.manage");
        add(SessionDomain.STORE,"GET","/staff-role-options","staff.manage");
        add(SessionDomain.STORE,"PUT","/staff/{id}/roles","staff.manage");
        commands(SessionDomain.STORE,"staff","staff.manage","enable","disable","revoke");
        add(SessionDomain.STORE,"GET","/context","summary.read");
        for(String name:List.of("eligibility","workbench"))add(SessionDomain.STORE,"GET","/"+name,"summary.read");
        add(SessionDomain.STORE,"GET","/cooperation","cooperation.read");
        add(SessionDomain.STORE,"GET","/agreements/{id}","cooperation.read");
        read(SessionDomain.MANAGER,"agreements","agreement.read");
        add(SessionDomain.MANAGER,"POST","/agreements","agreement.write");
        add(SessionDomain.MANAGER,"PUT","/agreements/{id}/draft","agreement.write");
        add(SessionDomain.MANAGER,"GET","/agreements/{id}/versions","agreement.read");
        commands(SessionDomain.MANAGER,"agreements","agreement.write","confirm","cancel","terminate");
        read(SessionDomain.MANAGER,"commission-rules","rule.read");
        add(SessionDomain.MANAGER,"POST","/commission-rules","rule.write");
        add(SessionDomain.MANAGER,"PUT","/commission-rules/{id}/draft","rule.write");
        add(SessionDomain.MANAGER,"GET","/commission-rules/{id}/versions","rule.read");
        add(SessionDomain.MANAGER,"GET","/commission-rules/{id}/preview","rule.read");
        commands(SessionDomain.MANAGER,"commission-rules","rule.write","publish","disable");
        read(SessionDomain.MANAGER,"channel-configs","channel.configure");
        add(SessionDomain.MANAGER,"POST","/channel-configs","channel.configure");
        add(SessionDomain.MANAGER,"PUT","/channel-configs/{id}/draft","channel.configure");
        commands(SessionDomain.MANAGER,"channel-configs","channel.configure","enable","disable","check");
        read(SessionDomain.MANAGER,"submerchants","channel.onboard");
        add(SessionDomain.MANAGER,"GET","/submerchants/{id}/required-action","channel.onboard");
        add(SessionDomain.MANAGER,"POST","/submerchants","channel.onboard");
        add(SessionDomain.MANAGER,"PUT","/submerchants/{id}/materials","channel.onboard");
        add(SessionDomain.MANAGER,"PUT","/submerchants/{id}/receiver","channel.receiver");
        commands(SessionDomain.MANAGER,"submerchants","channel.onboard","submit","query","enable","disable");
        read(SessionDomain.MANAGER,"receivers","channel.receiver");
        add(SessionDomain.MANAGER,"POST","/receivers","channel.receiver");
        commands(SessionDomain.MANAGER,"receivers","channel.receiver","sync","enable","disable");
        for(String name:List.of("categories","units")) {
            read(SessionDomain.MANAGER,name,"catalog.configure");
            add(SessionDomain.MANAGER,"POST","/"+name,"catalog.configure");
            add(SessionDomain.MANAGER,"PUT","/"+name+"/{id}","catalog.configure");
            commands(SessionDomain.MANAGER,name,"catalog.configure","enable","disable");
            add(SessionDomain.STORE,"GET","/"+name,"goods.read");
        }
        add(SessionDomain.MANAGER,"GET","/qualification-types","catalog.configure");
        read(SessionDomain.STORE,"goods","goods.read");
        add(SessionDomain.STORE,"POST","/goods","goods.write");
        add(SessionDomain.STORE,"PUT","/goods/{id}/content","goods.write");
        commands(SessionDomain.STORE,"goods","goods.submit","submit");
        commands(SessionDomain.STORE,"goods","goods.publish","list","unlist");
        add(SessionDomain.STORE,"PUT","/skus/{id}/stock","stock.write");
        add(SessionDomain.STORE,"PUT","/skus/{id}/cost","cost.write");
        add(SessionDomain.STORE,"GET","/skus/{id}/stock-history","stock.read");
        add(SessionDomain.MANAGER,"GET","/goods/reviews","goods.review");
        add(SessionDomain.MANAGER,"GET","/goods/{id}","goods.read");
        add(SessionDomain.MANAGER,"GET","/goods","goods.read");
        commands(SessionDomain.MANAGER,"goods","goods.review","approve","reject");
        commands(SessionDomain.MANAGER,"goods","goods.supervise","force-off","release");
        add(SessionDomain.STORE,"GET","/fulfillment","fulfillment.read");
        add(SessionDomain.STORE,"PUT","/fulfillment","fulfillment.configure");
        read(SessionDomain.STORE,"points","point.read");
        add(SessionDomain.STORE,"POST","/points","point.write");
        add(SessionDomain.STORE,"PUT","/points/{id}","point.write");
        commands(SessionDomain.STORE,"points","point.write","enable","disable");
        add(SessionDomain.RESIDENT,"GET","/goods/{id}","consumer.read");
        add(SessionDomain.RESIDENT,"GET","/stores/{id}/goods","consumer.read");
        add(SessionDomain.RESIDENT,"POST","/precheck","consumer.read");
        for(SessionDomain d:List.of(SessionDomain.MANAGER,SessionDomain.STORE,SessionDomain.RESIDENT))
            add(d,"GET","/goods/{id}/images/{fileId}",d==SessionDomain.RESIDENT?"consumer.read":"goods.read");
        for(SessionDomain d:SessionDomain.values()) {
            add(d,"GET","/regions","region.read");
            add(d,"GET","/operations/{requestId}","operation.read");
            if(d!=SessionDomain.RESIDENT) {
                add(d,"POST","/operations/{requestId}/abandon","operation.read");
                add(d,"POST","/files","file.upload");
                add(d,"GET","/files/{id}/preview","file.read");
            }
        }
    }
    private Stage1Routes() { }
    public static String base(SessionDomain d){return switch(d){case MANAGER->MANAGER;case STORE->STORE;case APPLICANT->APPLICANT;case RESIDENT->BUYER;};}
    private static void read(SessionDomain d,String name,String action){add(d,"GET","/"+name,action);add(d,"GET","/"+name+"/{id}",action);}
    private static void commands(SessionDomain d,String name,String permission,String... actions){for(String action:actions)add(d,"POST","/"+name+"/{id}/"+action,permission);}
    private static void add(SessionDomain d,String method,String path,String action){
        String template=base(d)+path;
        String pattern=Pattern.quote(template).replace("{id}","\\E[1-9][0-9]{0,18}\\Q").replace("{fileId}","\\E[1-9][0-9]{0,18}\\Q").replace("{requestId}","\\E[A-Za-z0-9_-]{8,64}\\Q");
        ROUTES.add(new Route(d,method,template,action,Pattern.compile(pattern)));
    }
    public static boolean path(String path){return path!=null && Arrays.stream(SessionDomain.values()).map(Stage1Routes::base).anyMatch(b->path.equals(b)||path.startsWith(b+"/"));}
    public static Route find(SessionDomain d,String method,String path){if(path==null||path.contains("%")||path.contains(";")||path.contains("//"))return null;return ROUTES.stream().filter(r->r.domain==d&&r.method.equals(method)&&r.pattern.matcher(path).matches()).findFirst().orElse(null);}
    public static List<Route> all(){return List.copyOf(ROUTES);}
}
