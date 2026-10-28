package cn.lili.modules.community.p0.agreement;
import cn.lili.modules.community.p0.support.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
class CooperationWindowsTest {
    @Test void lateConfirmationNeverBackdatesPermission(){
        var from=Instant.parse("2026-01-01T00:00:00Z");var confirmed=from.plusSeconds(3600);var to=from.plusSeconds(7200);
        var row=map("effective_from",from,"effective_to",to,"confirmed_at",confirmed);
        var window=CooperationWindows.agreement(row);assertFalse(window.contains(confirmed.minusNanos(1)));assertTrue(window.contains(confirmed));assertFalse(window.contains(to));
    }
    @Test void replacementAndTerminationDoNotEraseHistoricalWindow(){
        var from=Instant.parse("2026-01-01T00:00:00Z");var boundary=from.plusSeconds(3600);
        var old=map("effective_from",from,"effective_to",from.plusSeconds(7200),"confirmed_at",from,"replaced_at",boundary);
        var next=map("effective_from",boundary,"effective_to",from.plusSeconds(10800),"confirmed_at",from,"terminated_at",from.plusSeconds(8000));
        assertFalse(CooperationWindows.agreement(old).overlaps(CooperationWindows.agreement(next)));assertTrue(CooperationWindows.agreement(old).contains(boundary.minusNanos(1)));assertTrue(CooperationWindows.agreement(next).contains(boundary));
    }
    @Test void cancelledFutureAgreementNeverBecomesEffective(){
        var now=Instant.parse("2026-01-01T00:00:00Z");var row=map("effective_from",now.plusSeconds(3600),"effective_to",now.plusSeconds(7200),"confirmed_at",now,"cancelled_at",now.plusSeconds(60));
        assertNull(CooperationWindows.agreement(row));assertEquals("CANCELLED",CooperationWindows.state(row,true,now.plusSeconds(5000)));
    }
}
