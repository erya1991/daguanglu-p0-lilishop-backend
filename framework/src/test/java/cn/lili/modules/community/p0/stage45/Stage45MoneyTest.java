package cn.lili.modules.community.p0.stage45;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class Stage45MoneyTest {
 @Test void returnsAreCumulativeRatherThanIndividuallyRounded(){long paid=3,returned=0;long refunded=0;for(long amount:List.of(33L,33L,34L)){long target=Stage45Money.target(100,refunded,amount,300);assertEquals(1,Stage45Money.requiredReturn(paid,returned,target));returned++;refunded+=amount;}assertEquals(3,returned);}
 @Test void laterBatchCannotReuseEarlierUnsettledCredit(){assertEquals(100,Stage45Money.requiredReturn(500,0,Stage45Money.target(10000,0,2000,500)));assertEquals(100,Stage45Money.requiredReturn(500,100,Stage45Money.target(10000,2000,2000,500)));}
 @Test void costOfFixedFeeIsExcludedAndSharesConserveTheOriginalLine(){assertEquals(500,Stage45Money.commission(10000,500));assertEquals(List.of(34L,33L,33L),Stage45Money.shares(100,3));assertEquals(100,Stage45Money.shares(100,3).stream().mapToLong(Long::longValue).sum());}
 @Test void deadlineIncludesTheWholeFifteenthBeijingDay(){var completed=Instant.parse("2026-11-02T01:02:03Z");assertEquals(Instant.parse("2026-11-17T16:00:00Z"),Stage45Money.deadline(completed));assertTrue(Stage45Money.withinDeadline(Instant.parse("2026-11-17T15:59:59.999999Z"),completed));assertFalse(Stage45Money.withinDeadline(Instant.parse("2026-11-17T16:00:00Z"),completed));}
 @Test void capacityReservesASeparateFutureFeeAndCountsUnknownAndExternal(){assertDoesNotThrow(()->Stage45Money.capacity(48,0,0,1,1));assertThrows(RuntimeException.class,()->Stage45Money.capacity(49,0,0,1,1));assertThrows(RuntimeException.class,()->Stage45Money.capacity(45,3,2,1,0));assertThrows(RuntimeException.class,()->Stage45Money.capacity(Long.MAX_VALUE,1,1,1,1));}
 @Test void largeCentValuesDoNotOverflowBasisPointMultiplication(){assertEquals(9007199254740991L,Stage45Money.commission(9007199254740991L,10000));assertThrows(RuntimeException.class,()->Stage45Money.target(100,90,11,500));}
}
