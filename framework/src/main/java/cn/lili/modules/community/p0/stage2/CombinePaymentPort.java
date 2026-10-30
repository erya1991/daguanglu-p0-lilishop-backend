package cn.lili.modules.community.p0.stage2;

import com.google.gson.JsonObject;

/** Same immutable combine/sub-order identity for prepare/query/close and recovery. */
public interface CombinePaymentPort {
    JsonObject prepare(JsonObject request);
    JsonObject launch(JsonObject request);
    JsonObject query(JsonObject request);
    JsonObject close(JsonObject request);
}
