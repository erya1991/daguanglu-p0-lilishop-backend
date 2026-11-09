package cn.lili.modules.community.p0.stage45;

import com.google.gson.JsonObject;

/** Provider-authenticated facts. A transport exception is UNKNOWN, never a successful result. */
public interface Stage45FundsPort {
    JsonObject send(JsonObject request);
    JsonObject query(JsonObject request);
    JsonObject capacity(JsonObject original,String kind,String shareRequest);
    boolean trusted(JsonObject result);
    JsonObject bill(String account,java.time.LocalDate date,java.time.Instant cutoff);
}
