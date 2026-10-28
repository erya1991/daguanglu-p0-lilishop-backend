package cn.lili.modules.community.p0.agreement;

import cn.lili.modules.community.p0.support.*;
import java.time.Instant;
import java.util.Map;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Current and historical windows derive from immutable dates and independently recorded lifecycle facts. */
public final class CooperationWindows {
    private CooperationWindows(){}
    public static Stage1Rules.Window agreement(Map<String,Object> row){
        if(row==null||row.get("confirmed_at")==null||row.get("cancelled_at")!=null)return null;
        return window(row,"confirmed_at","terminated_at");
    }
    public static Stage1Rules.Window rule(Map<String,Object> row){
        if(row==null||row.get("published_at")==null)return null;
        return window(row,"published_at","disabled_at");
    }
    private static Stage1Rules.Window window(Map<String,Object> row,String publication,String end){
        Instant from=max(instant(row.get("effective_from")),instant(row.get(publication)));
        Instant to=min(instant(row.get("effective_to")),instant(row.get(end)),instant(row.get("replaced_at")));
        return from!=null&&to!=null&&from.isBefore(to)?new Stage1Rules.Window(from,to):null;
    }
    public static Instant min(Instant... times){Instant result=null;for(var t:times)if(t!=null&&(result==null||t.isBefore(result)))result=t;return result;}
    public static Instant max(Instant a,Instant b){return a==null?b:b==null?a:a.isAfter(b)?a:b;}
    public static String state(Map<String,Object> row,boolean agreement,Instant at){
        if(agreement&&row.get("cancelled_at")!=null)return "CANCELLED";
        if(row.get(agreement?"confirmed_at":"published_at")==null)return "DRAFT";
        var cutoff=instant(row.get(agreement?"terminated_at":"disabled_at"));
        if(cutoff!=null&&!at.isBefore(cutoff))return agreement?"TERMINATED":"DISABLED";
        var replacement=instant(row.get("replaced_at"));if(replacement!=null&&!at.isBefore(replacement))return "REPLACED";
        var window=agreement?agreement(row):rule(row);
        return window==null||!at.isBefore(window.to())?"EXPIRED":at.isBefore(window.from())?"CONFIRMED":"EFFECTIVE";
    }
}
