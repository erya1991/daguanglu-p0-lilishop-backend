package cn.lili.modules.community.p0.stage3;
import cn.lili.modules.community.p0.stage2.*;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
class Stage3EligibilityTest {
    @Test void paidEnumWithoutCompletePaidFactsNeverQualifies(){
        var db=mock(Stage1Db.class);var channel=mock(PersistentPaymentMock.class);var settings=new Stage2Settings();settings.setRunId("S3_TEST");
        var state=map("trade_id","1","member_id","2","mode","MOCK","status","PAID","payment_status","SUCCEEDED","amount_cent",1001);
        var request=object(map("tradeId","1","payerMemberId","2","mode","MOCK","currency","CNY","combineNo","C1","amountCent",1001,"subOrders",List.of(map("orderId","3","storeId","4","subNo","S2S3","subMchid","M4","amountCent",1001))));
        var legacy=object(map("mode","MOCK","state","SUCCEEDED","request",request,"signature","UNIT_ONLY"));
        when(db.required(startsWith("SELECT * FROM li_p0_trade_state"),any())).thenReturn(state);
        when(db.count(startsWith("SELECT COUNT(*) FROM li_p0_s2_member_scope"),any(),any())).thenReturn(1L);
        when(db.required(startsWith("SELECT * FROM li_p0_payment"),any())).thenReturn(map("status","SUCCEEDED","result_json",write(legacy),"request_json",write(request),"request_hash",hash(request),"combine_no","C1"));
        when(channel.trusted(any(JsonObject.class))).thenReturn(true);
        var result=new Stage3Eligibility(db,channel,settings).inspect("1");
        assertFalse(result.eligible());assertEquals("PAID_FACTS_INCOMPLETE_OR_MISMATCH",result.reason());
        assertTrue(mockingDetails(db).getInvocations().stream().noneMatch(i->i.getMethod().getName().equals("update")));
    }
    @Test void untrustedSignatureIsRejectedBeforeSnapshotOrInventoryConsumption(){
        var db=mock(Stage1Db.class);var channel=mock(PersistentPaymentMock.class);var settings=new Stage2Settings();settings.setRunId("S3_TEST");
        when(db.required(startsWith("SELECT * FROM li_p0_trade_state"),any())).thenReturn(map("mode","MOCK","member_id","2"));
        when(db.count(startsWith("SELECT COUNT(*) FROM li_p0_s2_member_scope"),any(),any())).thenReturn(1L);
        when(db.required(startsWith("SELECT * FROM li_p0_payment"),any())).thenReturn(map("status","SUCCEEDED","request_json","{}","result_json","{}"));
        assertEquals("SC23_SOURCE_UNTRUSTED",new Stage3Eligibility(db,channel,settings).inspect("1").reason());
        verify(db,never()).rows(contains("inventory"),any(Object[].class));
    }
    @Test void wrongModeAndUnregisteredScopeCannotBecomeTrustedTurnover(){
        var db=mock(Stage1Db.class);var settings=new Stage2Settings();settings.setRunId("S3_TEST");var service=new Stage3Eligibility(db,mock(PersistentPaymentMock.class),settings);
        when(db.required(anyString(),any())).thenReturn(map("mode","REAL"));assertEquals("SC23_MODE_MISMATCH",service.inspect("1").reason());
        when(db.required(anyString(),any())).thenReturn(map("mode","MOCK","member_id","2"));assertEquals("SC23_SCOPE_MISMATCH",service.inspect("1").reason());
    }
    @Test void nativeAddressAndProductTextMustStillMatchImmutableSnapshot(){
        var original=object(map("contact",map("name","住户","mobile","13800000000"),"storeName","本店","goodsCent",1001,"freightCent",200,"address",map("region","甲省乙市丙区","provinceCode","11","cityCode","1101","districtCode","110101","detail","一号楼")));
        var order=map("consignee_name","住户","consignee_mobile","13800000000","store_name","本店","goods_price","10.01","freight_price","2.00","consignee_address_path","甲省乙市丙区","consignee_address_id_path","11,1101,110101","consignee_detail","一号楼");
        assertDoesNotThrow(()->Stage3Eligibility.requireOrderSnapshot(order,original));
        order.put("consignee_detail","另一地址");assertThrows(Stage1Exception.class,()->Stage3Eligibility.requireOrderSnapshot(order,original));
        var item=map("goods_name","商品","specs","红色","flow_price","20.02");var line=object(map("name","商品","specifications","红色","quantity",2,"unitPriceCent",1001));
        assertDoesNotThrow(()->Stage3Eligibility.requireItemSnapshot(item,line));item.put("specs","蓝色");assertThrows(Stage1Exception.class,()->Stage3Eligibility.requireItemSnapshot(item,line));
    }
}
