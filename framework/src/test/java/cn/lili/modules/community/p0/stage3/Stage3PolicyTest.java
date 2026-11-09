package cn.lili.modules.community.p0.stage3;
import cn.lili.modules.community.p0.support.Stage1Exception;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class Stage3PolicyTest {
    @Test void deliveryNeedsResidentReceiptAfterMerchantDelivered(){
        String s=Stage3Policy.initial("MERCHANT_DELIVERY");s=Stage3Policy.next("MERCHANT_DELIVERY",s,"prepare");s=Stage3Policy.next("MERCHANT_DELIVERY",s,"dispatch");
        assertEquals("DELIVERING",s);assertThrows(Stage1Exception.class,()->Stage3Policy.next("MERCHANT_DELIVERY","DELIVERING","receive"));
        s=Stage3Policy.next("MERCHANT_DELIVERY",s,"delivered");assertEquals("DELIVERED",s);assertEquals("COMPLETED",Stage3Policy.next("MERCHANT_DELIVERY",s,"receive"));
    }
    @Test void shipmentCannotBeCorrectedReversedOrCompletedByMerchant(){
        assertEquals("WAIT_SHIP",Stage3Policy.next("EXPRESS","WAIT_PREPARE","prepare"));assertEquals("SHIPPED",Stage3Policy.next("EXPRESS","WAIT_SHIP","ship"));
        for(String action:new String[]{"ship","delivered","dispatch","prepare","correct","cancel","verify"})assertThrows(Stage1Exception.class,()->Stage3Policy.next("EXPRESS","SHIPPED",action));
        assertEquals("COMPLETED",Stage3Policy.next("EXPRESS","SHIPPED","receive"));
    }
    @Test void lookupDoesNotTransitionAndPickupCannotSkipPreparation(){
        assertFalse(Stage3Policy.redeemable("COMMUNITY_PICKUP","WAIT_PREPARE"));assertThrows(Stage1Exception.class,()->Stage3Policy.next("COMMUNITY_PICKUP","WAIT_PREPARE","verify"));
        assertTrue(Stage3Policy.redeemable("COMMUNITY_PICKUP",Stage3Policy.next("COMMUNITY_PICKUP","WAIT_PREPARE","prepare")));
        assertThrows(Stage1Exception.class,()->Stage3Policy.next("COMMUNITY_PICKUP","WAIT_PICKUP","lookup"));
        assertEquals("COMPLETED",Stage3Policy.next("COMMUNITY_PICKUP","WAIT_PICKUP","verify"));
    }
    @Test void storeRedemptionHasNoForcedPreparationOrResidentSubstitute(){
        assertEquals("WAIT_VERIFY",Stage3Policy.initial("STORE_REDEMPTION"));assertThrows(Stage1Exception.class,()->Stage3Policy.next("STORE_REDEMPTION","WAIT_VERIFY","prepare"));
        assertThrows(Stage1Exception.class,()->Stage3Policy.next("STORE_REDEMPTION","WAIT_VERIFY","receive"));assertEquals("COMPLETED",Stage3Policy.next("STORE_REDEMPTION","WAIT_VERIFY","verify"));
        for(String method:Stage3Policy.METHODS)for(String action:new String[]{"prepare","dispatch","delivered","ship","receive","verify"})assertThrows(Stage1Exception.class,()->Stage3Policy.next(method,"COMPLETED",action));
    }
}
