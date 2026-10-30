package cn.lili.modules.community.p0.stage2;

import org.junit.jupiter.api.Test;
import java.util.List;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;

class Stage2CommercialTest {
    private Object order(String title,String image,long price,String address,String phone,String opening){return map("storeId","1","storeName","店铺展示名","items",List.of(map("skuId","2","quantity",2,"unitPriceCent",price,"specifications","标准规格","name",title,"image",image,"cartId","3","cartVersion",8)),"method","COMMUNITY_PICKUP","point",map("id","4","address",address,"phone",phone,"opening",opening,"name","点位展示名","version",9),"goodsCent",price*2,"freightCent",0,"amountCent",price*2);}
    @Test void approvedDisplayAndPointContactChangesPreserveHistoricObligations(){assertEquals(hash(Stage2QuoteService.obligations(order("旧名称","old",99,"同一地址","13800000000","周一"))),hash(Stage2QuoteService.obligations(order("合法复审后的名称","new",99,"同一地址","13900000000","全天"))));}
    @Test void priceQuantityAndActualServiceLocationRemainCommercialChanges(){var old=Stage2QuoteService.obligations(order("名称","old",99,"地址一","13800000000","全天"));assertNotEquals(hash(old),hash(Stage2QuoteService.obligations(order("名称","old",100,"地址一","13800000000","全天"))));assertNotEquals(hash(old),hash(Stage2QuoteService.obligations(order("名称","old",99,"地址二","13800000000","全天"))));}
}
