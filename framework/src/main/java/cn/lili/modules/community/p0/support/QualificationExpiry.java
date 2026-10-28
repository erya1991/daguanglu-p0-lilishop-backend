package cn.lili.modules.community.p0.support;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/** One Shanghai calendar policy for qualification history and current reminders. */
public final class QualificationExpiry {
    private QualificationExpiry() { }
    public static LocalDate validThrough(Instant exclusiveEnd) {
        return exclusiveEnd==null?null:exclusiveEnd.atZone(Stage1Rules.ZONE).toLocalDate().minusDays(1);
    }
    public static String warning(Instant exclusiveEnd,Instant at) {
        if(exclusiveEnd==null)return null;
        long days=ChronoUnit.DAYS.between(at.atZone(Stage1Rules.ZONE).toLocalDate(),validThrough(exclusiveEnd));
        return days>30?null:days<0?"EXPIRED":days<=7?"DUE_7":"DUE_30";
    }
}
