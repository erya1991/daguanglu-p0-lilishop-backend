package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.support.*;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Unsupported trusted protocol facts cannot be resolved by local settlement or another user click. */
class Stage2PaymentIsolationTest {
    private static final String ISOLATED="CHANNEL_RESULT_OUT_OF_SCOPE";
    private final Stage1Db db=mock(Stage1Db.class);
    private final Stage2Transactions tx=mock(Stage2Transactions.class);
    private final Stage2TradeService trades=mock(Stage2TradeService.class);
    private final PersistentPaymentMock channel=mock(PersistentPaymentMock.class);
    private final Stage2Inventory stock=mock(Stage2Inventory.class);
    private final Clock clock=Clock.fixed(Instant.parse("2026-10-31T20:00:00Z"),ZoneOffset.UTC);
    private final Map<String,Object> state=map("trade_id","7","member_id","1","status","PAID","payment_status","SUCCEEDED","protection",ISOLATED,"close_status","NONE","pay_deadline",clock.instant().plusSeconds(900));
    private Stage2PaymentService service(){
        when(db.required(anyString(),any(Object[].class))).thenReturn(state);
        when(trades.stores("7")).thenReturn(List.of("9"));
        when(tx.member()).thenReturn("1");
        doAnswer(inv->((Supplier<?>)inv.getArgument(2)).get()).when(tx).historical(anyString(),anyCollection(),any());
        doAnswer(inv->((Supplier<?>)inv.getArgument(2)).get()).when(tx).locked(anyString(),anyCollection(),any());
        return new Stage2PaymentService(db,tx,trades,mock(Stage2QuoteService.class),stock,channel,new Stage2Settings(),clock);
    }
    @Test void paidIsolationDoesNotTryLocalSettlementOrQueryTheChannel(){
        var service=service();when(db.one(contains("li_p0_payment"),any(Object[].class))).thenReturn(map("status","SUCCEEDED"));
        service.recover("7");
        verifyNoInteractions(stock,channel);
        verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void unsupportedResultBeforeKnownPaymentCannotBeAutoClosedOrQueried(){
        state.putAll(map("status","CLOSE_CONFIRMING","payment_status","UNKNOWN","close_status","REQUESTED"));
        var service=service();when(db.one(contains("li_p0_payment"),any(Object[].class))).thenReturn(map("status","UNPAID"));
        service.recover("7");verifyNoInteractions(stock,channel);verify(db,never()).update(anyString(),any(Object[].class));
    }
    @Test void ownerCancelIsRefusedBeforeRecordingAnotherCloseIntent(){
        state.putAll(map("status","WAIT_PAY","payment_status","UNPAID"));var service=service();
        var error=assertThrows(Stage1Exception.class,()->service.cancel("7",map("operationId","operation_isolated_1")));
        assertEquals(409,error.status());verify(db,never()).update(anyString(),any(Object[].class));verifyNoInteractions(stock,channel);
    }
    @Test void ownerPrepareIsRefusedWithoutAnotherExternalCall(){
        state.putAll(map("status","WAIT_PAY","payment_status","UNPAID"));var service=service();
        var error=assertThrows(Stage1Exception.class,()->service.prepare("7",map("operationId","operation_isolated_2")));
        assertEquals(409,error.status());verify(db,never()).update(anyString(),any(Object[].class));verifyNoInteractions(stock,channel);
    }
    @Test void privateSummaryAllowsOnlyOriginalFactReadForAnIsolatedUnpaidTrade(){
        state.putAll(map("status","WAIT_PAY","payment_status","UNPAID","intent_id","intent_existing_1","mode","MOCK","amount_cent",1301,"created_at",clock.instant(),"verified_at",clock.instant()));
        when(db.required(anyString(),any(Object[].class))).thenReturn(state);
        var service=new Stage2TradeService(db,tx,mock(Stage2QuoteService.class),mock(Stage2Cart.class),stock,null,null,null,null,clock);
        var summary=object(service.summary("7","1"));assertEquals(List.of("REFRESH"),summary.getAsJsonArray("allowedActions").asList().stream().map(x->x.getAsString()).toList());assertEquals(ISOLATED,summary.get("protection").getAsString());
    }
}
