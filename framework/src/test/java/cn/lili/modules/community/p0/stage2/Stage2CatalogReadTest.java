package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.catalog.ConsumerGoodsService;
import cn.lili.modules.community.p0.eligibility.EligibilityService;
import cn.lili.modules.community.p0.fulfillment.FulfillmentService;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Stage2CatalogReadTest {
    final Stage1Db db=mock(Stage1Db.class);
    final ConsumerGoodsService consumer=mock(ConsumerGoodsService.class);
    final EligibilityService eligibility=mock(EligibilityService.class);
    final Stage2Transactions transactions=mock(Stage2Transactions.class);
    final FulfillmentService fulfillment=mock(FulfillmentService.class);
    final Stage2Catalog catalog=new Stage2Catalog(db,consumer,eligibility,transactions,fulfillment);
    Stage2CatalogReadTest(){
        when(transactions.member()).thenReturn("101");
        when(db.rows(anyString(),any(Object[].class))).thenReturn(List.of(map("gid","401","store_id","201","category_id","301","methods","[\"MERCHANT_DELIVERY\"]"),map("gid","402","store_id","202","category_id","301","methods","[\"MERCHANT_DELIVERY\"]")));
        when(eligibility.evaluate(anyString(),anyString(),isNull())).thenReturn(map("MOCK_READY",true));
        when(fulfillment.effective(anyString())).thenReturn(Map.of("MERCHANT_DELIVERY",new JsonObject()));
        when(consumer.detail(anyString())).thenAnswer(i->map("id",i.getArgument(0)));
        when(db.required(anyString(),any(Object[].class))).thenReturn(map("store_name","公开店铺","category_id","301","category_name","公开分类"));
    }
    JsonObject page(){return object(catalog.list("",null,"COMPREHENSIVE",1,20));}
    @Test void oneUnavailableQualificationKeepsVerifiedSupplyWithAnIncompleteReadWarning(){
        when(eligibility.evaluate("201","301",null)).thenThrow(new DataAccessResourceFailureException("Private database diagnosis"));
        var value=page();assertFalse(value.get("readComplete").getAsBoolean());assertEquals(1,value.get("total").getAsInt());assertEquals("402",value.getAsJsonArray("records").get(0).getAsJsonObject().get("id").getAsString());verify(consumer,never()).detail("401");assertFalse(write(value).contains("Private database"));
    }
    @Test void allUnverifiedCandidatesCannotMasqueradeAsAnEmptySuccessfulCatalog(){
        when(eligibility.evaluate(anyString(),anyString(),isNull())).thenThrow(new DataAccessResourceFailureException("Unavailable"));
        var failure=assertThrows(Stage1Exception.class,this::page);assertEquals(503,failure.status());assertEquals("CATALOG_UNVERIFIED",failure.code());verifyNoInteractions(consumer);
    }
    @Test void aDetailReadBecomingUnavailableDoesNotHideAnotherVerifiedProduct(){
        when(consumer.detail("401")).thenThrow(new Stage1Exception(503,"DEPENDENCY_UNAVAILABLE"));
        var value=page();assertFalse(value.get("readComplete").getAsBoolean());assertEquals(1,value.get("total").getAsInt());assertEquals("402",value.getAsJsonArray("records").get(0).getAsJsonObject().get("id").getAsString());
    }
    @Test void aKnownUnqualifiedEmptyCatalogRemainsACompleteRead(){
        when(eligibility.evaluate(anyString(),anyString(),isNull())).thenReturn(map("MOCK_READY",false));
        var value=page();assertTrue(value.get("readComplete").getAsBoolean());assertEquals(0,value.get("total").getAsInt());assertTrue(value.getAsJsonArray("records").isEmpty());
    }
    @Test void authorizationFailureCannotBeTurnedIntoPartialSuccess(){
        when(transactions.member()).thenThrow(new Stage1Exception(403,"AUTH_REQUIRED"));assertEquals(403,assertThrows(Stage1Exception.class,this::page).status());verifyNoInteractions(eligibility);
    }
    @Test void unexpectedProgrammingFailuresAreNotHiddenAsIncompleteReads(){
        when(eligibility.evaluate("201","301",null)).thenThrow(new IllegalStateException("Unexpected implementation defect"));assertThrows(IllegalStateException.class,this::page);
    }
    @Test void anUnverifiedPageIsNotAnEmptySuccessEvenWhenAnotherPageHasEligibleGoods(){
        when(consumer.detail("401")).thenThrow(new DataAccessResourceFailureException("This page is unverified"));
        var failure=assertThrows(Stage1Exception.class,()->catalog.list("",null,"COMPREHENSIVE",1,1));assertEquals(503,failure.status());assertEquals("CATALOG_UNVERIFIED",failure.code());
    }
}
