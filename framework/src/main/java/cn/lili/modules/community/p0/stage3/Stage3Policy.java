package cn.lili.modules.community.p0.stage3;

import cn.lili.modules.community.p0.support.Stage1Exception;
import java.util.*;

/** Fulfillment is independent of payment, after-sale, messages and inventory. */
public final class Stage3Policy {
    private Stage3Policy() {}
    public static final Set<String> METHODS=Set.of("MERCHANT_DELIVERY","EXPRESS","COMMUNITY_PICKUP","STORE_REDEMPTION");
    public static String initial(String method) { require(METHODS.contains(method));return method.equals("STORE_REDEMPTION")?"WAIT_VERIFY":"WAIT_PREPARE"; }
    public static String next(String method,String phase,String action) {
        require(METHODS.contains(method));
        if(action.equals("prepare")&&phase.equals("WAIT_PREPARE"))return switch(method) {
            case "MERCHANT_DELIVERY" -> "WAIT_DISPATCH";case "EXPRESS" -> "WAIT_SHIP";case "COMMUNITY_PICKUP" -> "WAIT_PICKUP";default -> throw invalid();
        };
        if(method.equals("MERCHANT_DELIVERY")&&action.equals("dispatch")&&phase.equals("WAIT_DISPATCH"))return "DELIVERING";
        if(method.equals("MERCHANT_DELIVERY")&&action.equals("delivered")&&phase.equals("DELIVERING"))return "DELIVERED";
        if(method.equals("EXPRESS")&&action.equals("ship")&&phase.equals("WAIT_SHIP"))return "SHIPPED";
        if(action.equals("receive")&&(method.equals("MERCHANT_DELIVERY")&&phase.equals("DELIVERED")||method.equals("EXPRESS")&&phase.equals("SHIPPED")))return "COMPLETED";
        if(action.equals("verify")&&(method.equals("COMMUNITY_PICKUP")&&phase.equals("WAIT_PICKUP")||method.equals("STORE_REDEMPTION")&&phase.equals("WAIT_VERIFY")))return "COMPLETED";
        throw invalid();
    }
    public static List<String> actions(String method,String phase,boolean resident) {
        List<String> result=new ArrayList<>();
        for(String action:resident?List.of("receive"):List.of("prepare","dispatch","delivered","ship"))try{next(method,phase,action);result.add(action);}catch(Stage1Exception ignored){}
        return List.copyOf(result);
    }
    public static boolean redeemable(String method,String phase) {
        return method.equals("COMMUNITY_PICKUP")&&phase.equals("WAIT_PICKUP")||method.equals("STORE_REDEMPTION")&&phase.equals("WAIT_VERIFY");
    }
    private static void require(boolean condition) { if(!condition)throw invalid(); }
    private static Stage1Exception invalid() { return new Stage1Exception(409,"FULFILLMENT_STATE_CONFLICT"); }
}
