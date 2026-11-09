package cn.lili.modules.community.p0.stage45;

import cn.lili.modules.community.p0.stage2.Stage2Money;
import cn.lili.modules.community.p0.support.Stage1Exception;
import java.math.BigInteger;
import java.time.*;
import java.util.*;

public final class Stage45Money {
    private Stage45Money() {}
    public static long bounded(long value) {
        Stage1Exception.require(value>=0 && value<=Stage2Money.MAX_SAFE_CENT,422,"MONEY_OUT_OF_RANGE");
        return value;
    }
    public static long commission(long goods,long bps) {
        bounded(goods);
        Stage1Exception.require(bps>=0 && bps<=10000,422,"RATE_OUT_OF_RANGE");
        return BigInteger.valueOf(goods).multiply(BigInteger.valueOf(bps)).divide(BigInteger.valueOf(10000)).longValueExact();
    }
    public static List<Long> shares(long total,int quantity) {
        bounded(total);
        Stage1Exception.require(quantity>0 && quantity<=99,422,"QUANTITY_INVALID");
        var result=new ArrayList<Long>(quantity);
        for(int i=0;i<quantity;i++)result.add(total/quantity+(i<total%quantity?1:0));
        return List.copyOf(result);
    }
    public static long target(long goods,long successful,long batch,long bps) {
        Stage1Exception.require(successful>=0 && batch>=0 && successful<=goods && batch<=goods-successful,409,"REFUND_LIMIT");
        return commission(goods-successful-batch,bps);
    }
    public static long requiredReturn(long paid,long returned,long target) {
        Stage1Exception.require(paid>=returned && returned>=0 && target>=0,409,"COMMISSION_FACT_CONFLICT");
        return Math.max(0,paid-returned-target);
    }
    public static Instant deadline(Instant completedAt) {
        return completedAt==null?null:completedAt.atZone(ZoneId.of("Asia/Shanghai")).toLocalDate()
            .plusDays(16).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant();
    }
    public static boolean withinDeadline(Instant now,Instant completedAt) {
        return completedAt==null || now.isBefore(deadline(completedAt));
    }
    public static void capacity(long used,long unresolved,long external,int newRequests,int futureFee) {
        Stage1Exception.require(used>=0 && unresolved>=0 && external>=0,409,"CHANNEL_CAPACITY_UNKNOWN");
        Stage1Exception.require(used<=50&&unresolved<=50&&external<=50&&newRequests>=0&&futureFee>=0&&used+unresolved+external+newRequests+futureFee<=50,409,"CHANNEL_CAPACITY_EXHAUSTED");
    }
}
