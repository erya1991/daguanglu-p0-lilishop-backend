package cn.lili.modules.community.p0.stage2;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;

class Stage2PaymentFactsTest {
    private static final Instant PAID_AT = Instant.parse("2026-10-29T01:00:00Z");
    static JsonObject request() {
        return object(map("combineNo", "S2C9007199254740993", "currency", "CNY", "amountCent", 3801,
            "subOrders", List.of(map("orderId", "9007199254740993", "storeId", "11", "subNo", "S2S1", "subMchid", "sub-a", "amountCent", 1301),
                map("orderId", "9007199254740994", "storeId", "12", "subNo", "S2S2", "subMchid", "sub-b", "amountCent", 2500))));
    }
    static JsonObject success(JsonObject request) {
        return object(map("mode", "MOCK", "state", "SUCCEEDED", "request", request.deepCopy(),
            "paid", Stage2PaymentFacts.firstMockPayment(request, PAID_AT), "observedAt", PAID_AT.plusSeconds(90)));
    }
    @Test void completeFactsKeepLargeStringIdsAndChannelTimeIndependentOfReceiptTime() {
        var request = request(); var result = success(request);
        var paid = Stage2PaymentFacts.require(result, request);
        assertEquals(PAID_AT.toString(), paid.get("paidAt").getAsString());
        assertEquals("9007199254740993", paid.getAsJsonArray("subOrders").get(0).getAsJsonObject().get("orderId").getAsString());
        assertNotEquals(result.get("observedAt"), paid.get("paidAt"));
        var reordered = paid.getAsJsonArray("subOrders"); var first = reordered.remove(0); reordered.add(first);
        assertDoesNotThrow(() -> Stage2PaymentFacts.require(result, request));
    }
    @Test void missingOrMismatchedPaidEvidenceNeverBecomesCompleteByRequestEcho() {
        List<Consumer<JsonObject>> mutations = List.of(
            r -> r.remove("paid"), r -> r.getAsJsonObject("paid").remove("paidAt"),
            r -> r.getAsJsonObject("paid").addProperty("paidAt", "not-an-instant"),
            r -> r.getAsJsonObject("paid").addProperty("combineNo", "other"),
            r -> r.getAsJsonObject("paid").addProperty("currency", "USD"),
            r -> r.getAsJsonObject("paid").addProperty("amountCent", 3800),
            r -> r.getAsJsonObject("paid").getAsJsonArray("subOrders").remove(1),
            r -> child(r, 1).addProperty("orderId", "9007199254740993"),
            r -> child(r, 0).remove("transactionId"), r -> child(r, 0).remove("paidAt"),
            r -> child(r, 1).addProperty("transactionId", child(r, 0).get("transactionId").getAsString()),
            r -> child(r, 0).addProperty("subMchid", "other"), r -> child(r, 0).addProperty("storeId", "other"),
            r -> child(r, 0).addProperty("subNo", "other"), r -> child(r, 0).addProperty("currency", "USD"),
            r -> child(r, 0).addProperty("amountCent", 1301.9), r -> child(r, 0).addProperty("state", "UNPAID"),
            r -> child(r, 0).addProperty("paidAt", PAID_AT.plusSeconds(1).toString()),
            r -> r.addProperty("mode", "REAL"), r -> r.getAsJsonObject("request").addProperty("amountCent", 3800));
        for (int i = 0; i < mutations.size(); i++) {
            var request = request(); var result = success(request); mutations.get(i).accept(result);
            final int variant = i;
            assertThrows(RuntimeException.class, () -> Stage2PaymentFacts.require(result, request), "variant " + variant);
            assertNull(Stage2PaymentFacts.verifiedOrNull(result, request), "variant " + variant);
        }
    }
    private static JsonObject child(JsonObject result, int index) { return result.getAsJsonObject("paid").getAsJsonArray("subOrders").get(index).getAsJsonObject(); }
}
