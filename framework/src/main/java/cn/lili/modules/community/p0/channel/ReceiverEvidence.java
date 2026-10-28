package cn.lili.modules.community.p0.channel;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Only evidence obtained from the selected channel mode can authorize a receiver. */
public final class ReceiverEvidence {
    private ReceiverEvidence(){}
    public static boolean current(Map<String,Object> row,String mode,long configVersion,Instant at){
        if(row==null||!"VERIFIED".equals(id(row,"state"))||!mode.equals(id(row,"source_kind"))||row.get("config_version")==null||number(row,"config_version")!=configVersion)return false;
        String source=id(row,"trust_source");
        boolean trusted="MOCK".equals(mode)?"MOCK_CONTRACT".equals(source):"REAL".equals(mode)&&source!=null&&Set.of("DEFAULT_PLATFORM_VERIFIED","API_REGISTERED_VERIFIED").contains(source);
        Instant verified=instant(row.get("verified_at"));
        return trusted&&verified!=null&&!verified.isAfter(at)&&at.isBefore(verified.plusSeconds(86400));
    }
}
