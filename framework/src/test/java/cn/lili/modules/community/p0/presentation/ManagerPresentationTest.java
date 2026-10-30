package cn.lili.modules.community.p0.presentation;
import cn.lili.modules.community.p0.support.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
class ManagerPresentationTest {
    @Test void preservesNineteenDigitIdentifiers(){assertEquals("2115097764975017984",ManagerPresentationViews.identifier("2115097764975017984"));}
    @ParameterizedTest @ValueSource(strings={"0","-1","1.0","1e9"," 1","1/2","99999999999999999999"}) void rejectsInvalidIdentifiers(String value){assertThrows(Stage1Exception.class,()->ManagerPresentationViews.identifier(value));}
    @Test void profileHasFiniteFieldsAndKeepsExplicitZero(){var p=ManagerPresentationViews.profile(map("id","2115097764975017984","version",0,"storeName","测试店铺","extra",map("businessHours","09:00—18:00","password","never-leak","bindingId","private"),"secret_ref","private","bankAccount","private"));assertEquals(0,p.get("version").getAsInt());assertFalse(p.has("secret_ref"));assertFalse(p.has("bankAccount"));assertEquals(Set.of("businessHours"),p.getAsJsonObject("extra").keySet());}
    @Test void searchEscapesLikeSyntax(){assertEquals("%a!%b!_c!!%",ManagerPresentationViews.like("a%b_c!"));assertNull(ManagerPresentationViews.search("  "));assertEquals("店铺",ManagerPresentationViews.search(" 店铺 "));}
    @Test void searchDoesNotTruncate(){assertThrows(Stage1Exception.class,()->ManagerPresentationViews.search("字".repeat(101)));}
    @Test void paginationIsActualCount(){var result=ManagerPresentationViews.page(45,3,20,List.of());assertEquals(45L,result.get("total"));assertEquals(3,result.get("pageNumber"));}
    @Test void operatorDoesNotExposeUsernameOrIdentity(){assertEquals("管理员 · 3456",ManagerPresentationViews.operator(map("actor_id","123456")));assertEquals("审核测试人",ManagerPresentationViews.operator(map("operator_name","审核测试人","actor_id","123456","username","private")));}
}
