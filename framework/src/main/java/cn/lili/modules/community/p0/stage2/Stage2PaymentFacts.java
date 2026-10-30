package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.support.Stage1Exception;
import com.google.gson.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Paid facts are channel facts, separate from the immutable request and local receipt time. */
public final class Stage2PaymentFacts {
    private Stage2PaymentFacts() {}

    static JsonObject firstMockPayment(JsonObject request, Instant paidAt) {
        var subs = new JsonArray();
        for (var raw : request.getAsJsonArray("subOrders")) {
            var sub = raw.getAsJsonObject().deepCopy();
            sub.addProperty("transactionId", "S2MT" + UUID.randomUUID().toString().replace("-", ""));
            sub.addProperty("currency", "CNY");
            sub.addProperty("state", "SUCCEEDED");
            sub.addProperty("paidAt", paidAt.toString());
            subs.add(sub);
        }
        return object(map("combineNo", request.get("combineNo"), "currency", "CNY",
            "amountCent", request.get("amountCent"), "paidAt", paidAt.toString(), "subOrders", subs));
    }

    /** The caller authenticates the envelope and checks the original request digest first. */
    public static JsonObject require(JsonObject result, JsonObject request) {
        try {
            if (!"MOCK".equals(text(result, "mode")) || !"SUCCEEDED".equals(text(result, "state"))
                || !hash(request).equals(hash(result.get("request")))) throw invalid();
            var paid = result.getAsJsonObject("paid");
            if (!text(request, "combineNo").equals(text(paid, "combineNo"))
                || !"CNY".equals(text(paid, "currency"))
                || !text(request, "currency").equals(text(paid, "currency"))
                || money(paid) != money(request)) throw invalid();
            Instant paidAt = Instant.parse(text(paid, "paidAt"));
            var expected = new HashMap<String, JsonObject>();
            for (var raw : request.getAsJsonArray("subOrders")) {
                var sub = raw.getAsJsonObject();
                if (expected.put(text(sub, "orderId"), sub) != null) throw invalid();
            }
            var actual = paid.getAsJsonArray("subOrders");
            if (actual == null || actual.isEmpty() || actual.size() != expected.size()) throw invalid();
            var seen = new HashSet<String>();
            var transactions = new HashSet<String>();
            long sum = 0;
            Instant lastPaidAt = Instant.MIN;
            for (var raw : actual) {
                var sub = raw.getAsJsonObject();
                String order = text(sub, "orderId"), transaction = text(sub, "transactionId");
                var original = expected.get(order);
                if (original == null || !seen.add(order) || transaction.length() > 100
                    || !transactions.add(transaction) || !"SUCCEEDED".equals(text(sub, "state"))
                    || !"CNY".equals(text(sub, "currency")) || money(sub) != money(original)) throw invalid();
                for (String field : List.of("storeId", "subNo", "subMchid"))
                    if (!text(original, field).equals(text(sub, field))) throw invalid();
                Instant childPaidAt = Instant.parse(text(sub, "paidAt"));
                if (childPaidAt.isAfter(paidAt)) throw invalid();
                if (childPaidAt.isAfter(lastPaidAt)) lastPaidAt = childPaidAt;
                sum = Stage2Money.add(sum, money(sub));
            }
            if (sum != money(paid) || !paidAt.equals(lastPaidAt)) throw invalid();
            var normalized=paid.deepCopy();var sorted=new JsonArray();
            actual.asList().stream().map(JsonElement::getAsJsonObject)
                .sorted(Comparator.comparing(sub -> text(sub,"orderId"))).forEach(sub -> sorted.add(sub.deepCopy()));
            normalized.add("subOrders",sorted);return normalized;
        } catch (RuntimeException malformed) {
            throw invalid();
        }
    }

    public static JsonObject verifiedOrNull(Object result, JsonObject request) {
        if (result == null) return null;
        try { return require(object(result), request); }
        catch (RuntimeException incomplete) { return null; }
    }

    private static String text(JsonObject value, String field) {
        var item = value.get(field);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()
            || item.getAsString().isBlank()) throw invalid();
        return item.getAsString();
    }
    private static long money(JsonObject value) {
        var item = value.get("amountCent");
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber()) throw invalid();
        long amount = new BigDecimal(item.getAsString()).longValueExact();
        if (amount <= 0 || amount > 9_007_199_254_740_991L) throw invalid();
        return amount;
    }
    private static Stage1Exception invalid() { return new Stage1Exception(409, "PAID_FACTS_INCOMPLETE_OR_MISMATCH"); }
}
