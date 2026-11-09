package cn.lili.common.security.session;

import cn.lili.modules.community.p0.stage2.*;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.util.*;
import java.util.concurrent.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.common.security.session.Stage2PersistenceTest.*;
import static cn.lili.common.security.session.Stage2RemainingVerificationTest.request;
import static cn.lili.common.security.session.Stage2AcceptanceVerificationTest.*;
import static cn.lili.common.security.session.Stage2OwnerAcceptanceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@EnabledIfSystemProperty(named="s2.integration",matches="owned-run")
@Timeout(value=10,unit=TimeUnit.MINUTES)
class Stage2AddressVerificationTest {
    @BeforeAll static void open()throws Exception{simulation.offset=0;Stage2RemainingVerificationTest.open();}
    @BeforeEach void before(){simulation.offset=0;mvc=transactionMvc;}
    @AfterAll static void close()throws Exception{Stage2PersistenceTest.closeS2();}

    @Test void trimmedFieldBoundariesRejectBadInputWithoutChangingTheBookOrLeakingPrivateFields()throws Exception {
        var r=residentFixture();var valid=address(0," 王 ");valid.put("detail"," 五字地址甲 ");var book=request(transactionMvc,post(Stage2Routes.BASE+"/addresses"),r.session(),valid,200);var saved=book.getAsJsonArray("records").get(0).getAsJsonObject();assertEquals("王",saved.get("name").getAsString());assertEquals("五字地址甲",saved.get("detail").getAsString());assertEquals(saved.get("id"),book.get("defaultAddressId"));
        var maximum=address(1,"名".repeat(30));maximum.put("detail","址".repeat(200));book=request(transactionMvc,post(Stage2Routes.BASE+"/addresses"),r.session(),maximum,200);assertEquals(2,book.getAsJsonArray("records").size());String stable=hash(book);
        var variants=List.of(Map.entry("name",""),Map.entry("name","名".repeat(31)),Map.entry("detail","四字址甲"),Map.entry("detail","址".repeat(201)),Map.entry("mobile","1380000000"),Map.entry("mobile","23800000000"),Map.entry("mobile","1380000000x"),Map.entry("provinceCode","990001"),Map.entry("cityCode","990201"),Map.entry("districtCode","990199"));
        for(var variant:variants){var bad=address(2,"王");bad.put(variant.getKey(),variant.getValue());var response=transactionMvc.perform(post(Stage2Routes.BASE+"/addresses").header("accessToken",r.session().accessToken()).contentType("application/json").content(write(bad))).andReturn();assertTrue(response.getResponse().getStatus()>=400&&response.getResponse().getStatus()<500,variant.getKey());String error=response.getResponse().getContentAsString();assertFalse(error.contains("13800000000"));assertFalse(error.contains("S2_TEST并发地址"));assertEquals(stable,hash(getAddresses(r.session())));}
        note("TC-S2-R08-002-01","trimmed-name-detail-phone-region-boundaries",map("member",r.memberId()),map("invalidCases",variants.size(),"bookVersion",2,"rows",2,"bookUnchangedOnFailure",true));
    }

    @Test void everyPrivateAddressActionRejectsAnotherMemberAndWrongDomainWithoutChangingFacts()throws Exception {
        var p=purchase(1);var other=residentFixture();String id=p.address();var before=getAddresses(p.resident().session());var edit=address(0,"禁止越权");edit.put("expectedVersion",0);
        request(transactionMvc,get(Stage2Routes.BASE+"/addresses/"+id),other.session(),null,404);request(transactionMvc,put(Stage2Routes.BASE+"/addresses/"+id),other.session(),edit,404);request(transactionMvc,delete(Stage2Routes.BASE+"/addresses/"+id),other.session(),map("operationId",req(),"expectedVersion",0,"addressBookVersion",0),404);request(transactionMvc,post(Stage2Routes.BASE+"/addresses/"+id+"/default"),other.session(),map("operationId",req(),"expectedVersion",0,"addressBookVersion",0),404);
        request(transactionMvc,get(Stage2Routes.BASE+"/addresses/"+id),p.owner().login().session(),null,403);request(transactionMvc,put(Stage2Routes.BASE+"/addresses/"+id),p.owner().login().session(),edit,403);
        var attempt=map("items",List.of(map("skuId",p.sku(),"quantity",1)),"stores",List.of(map("storeId",p.owner().store(),"method","MERCHANT_DELIVERY","addressId",id,"addressVersion",0,"contact",map("name","S2居民","mobile","13800000000"))));request(transactionMvc,post(Stage2Routes.BASE+"/checkout/quotes"),other.session(),attempt,404);assertEquals(before,getAddresses(p.resident().session()));assertTrue(getAddresses(other.session()).getAsJsonArray("records").isEmpty());
        note("TC-S2-R08-001-01","private-read-edit-delete-default-selection-and-store-domain",map("address",id,"owner",p.resident().memberId(),"other",other.memberId()),map("ownerFactsUnchanged",true,"otherRows",0));
    }

    @Test void defaultMatrixAndDeleteVersusOldSetDefaultNeverLeaveADanglingPointer()throws Exception {
        var r=residentFixture();var book=request(transactionMvc,post(Stage2Routes.BASE+"/addresses"),r.session(),address(0,"甲"),200);String a=firstAddress(book);assertEquals(a,book.get("defaultAddressId").getAsString());book=request(transactionMvc,post(Stage2Routes.BASE+"/addresses"),r.session(),address(1,"乙"),200);String b=firstAddress(book);assertEquals(a,book.get("defaultAddressId").getAsString());var third=address(2,"丙");third.put("isDefault",true);book=request(transactionMvc,post(Stage2Routes.BASE+"/addresses"),r.session(),third,200);String d=firstAddress(book);assertEquals(d,book.get("defaultAddressId").getAsString());
        book=request(transactionMvc,delete(Stage2Routes.BASE+"/addresses/"+d),r.session(),map("operationId",req(),"expectedVersion",0,"addressBookVersion",3),200);assertTrue(book.get("defaultAddressId").isJsonNull());book=request(transactionMvc,post(Stage2Routes.BASE+"/addresses/"+b+"/default"),r.session(),map("operationId",req(),"expectedVersion",0,"addressBookVersion",4),200);assertEquals(b,book.get("defaultAddressId").getAsString());
        request(transactionMvc,post(Stage2Routes.BASE+"/addresses/"+d+"/default"),r.session(),map("operationId",req(),"expectedVersion",0,"addressBookVersion",3),409);assertEquals(b,getAddresses(r.session()).get("defaultAddressId").getAsString());request(transactionMvc,delete(Stage2Routes.BASE+"/addresses/"+a),r.session(),map("operationId",req(),"expectedVersion",0,"addressBookVersion",5),200);assertEquals(b,getAddresses(r.session()).get("defaultAddressId").getAsString());request(transactionMvc,post(Stage2Routes.BASE+"/addresses/default/clear"),r.session(),map("operationId",req(),"addressBookVersion",6),200);assertTrue(getAddresses(r.session()).get("defaultAddressId").isJsonNull());
        note("TC-S2-R08-007-02","first-ordinary-explicit-default-delete-old-default-no-promotion",map("member",r.memberId(),"addresses",List.of(a,b,d)),getAddresses(r.session()));
    }
}
