package cn.lili.modules.community.p0.support;

import cn.lili.common.security.session.SessionDomain;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Pure boundaries only; these do not constitute database, channel or device evidence. */
class Stage1RulesTest {
    @Test void actionCatalogIsFiniteUniqueAndDomainSpecific(){
        Set<String> seen=new HashSet<>();
        for(var route:Stage1Routes.all()){
            assertTrue(seen.add(route.method()+" "+route.template()),route.template());
            String path=route.template().replace("{id}","123").replace("{fileId}","456").replace("{requestId}","request_123");
            assertEquals(route,Stage1Routes.find(route.domain(),route.method(),path));
            for(var other:SessionDomain.values())if(other!=route.domain())assertNull(Stage1Routes.find(other,route.method(),path));
            assertNull(Stage1Routes.find(route.domain(),"DELETE",path));
        }
        assertNotNull(Stage1Routes.find(SessionDomain.APPLICANT,"POST","/store/passport/applicant/p0/s1/application"));
        assertNull(Stage1Routes.find(SessionDomain.STORE,"POST","/store/passport/applicant/p0/s1/application"));
    }
    @ParameterizedTest @ValueSource(strings={"/store/p0/s1/goods/../1","/store/p0/s1/goods/%31","/store/p0/s1/goods//1","/store/p0/s1/goods/1;admin","/store/p0/s1/goods/1/","/store/p0/s1/goods/0","/store/p0/s1/goods/1/list/extra"})
    void alternateRoutesCannotAcquireActions(String path){assertNull(Stage1Routes.find(SessionDomain.STORE,"GET",path));assertNull(Stage1Routes.find(SessionDomain.STORE,"POST",path));}
    @Test void strictFieldsPreserveMissingAndExplicitZero(){
        assertNull(new Fields(Map.of()).integer("costCent",0,99,false));
        assertEquals(0L,new Fields(map("costCent",0)).integer("costCent",0,99,true));
        assertThrows(Stage1Exception.class,()->new Fields(map("costCent","0")).integer("costCent",0,99,true));
        assertThrows(Stage1Exception.class,()->new Fields(map("costCent",new BigDecimal("0.1"))).integer("costCent",0,99,true));
        assertThrows(Stage1Exception.class,()->new Fields(map("storeId","123")).only("requestId","costCent"));
        assertThrows(Stage1Exception.class,()->new Fields(map("costCent",100)).integer("costCent",0,99,true));
        assertThrows(Stage1Exception.class,()->new Fields(map("id",123)).id("id",true));
        assertEquals("710000000000000001",new Fields(map("id","710000000000000001")).id("id",true));
    }
    @Test void canonicalCommandsIgnoreObjectKeyOrderButPreserveBusinessChanges(){
        assertEquals(hash(map("a",1,"b",2)),hash(map("b",2,"a",1)));
        assertNotEquals(hash(map("stock",0)),hash(Map.of()));assertNotEquals(hash(map("ids",List.of("1","2"))),hash(map("ids",List.of("2","1"))));
        assertEquals(99999999L,cents(money(99999999)));assertThrows(ArithmeticException.class,()->cents("0.001"));
    }
    @Test void windowsUseShanghaiInclusiveDatesAndExclusiveEnd(){
        var window=Stage1Rules.dates(LocalDate.of(2026,10,26),LocalDate.of(2026,10,26));
        assertEquals(Instant.parse("2026-10-25T16:00:00Z"),window.from());
        assertTrue(window.contains(window.from()));assertFalse(window.contains(window.to()));
        assertFalse(window.overlaps(Stage1Rules.dates(LocalDate.of(2026,10,27),LocalDate.of(2026,10,28))));
        assertThrows(Stage1Exception.class,()->Stage1Rules.dates(LocalDate.of(2026,10,27),LocalDate.of(2026,10,25)));
    }
    @Test void skuCodeAndFulfillmentBoundary(){
        assertEquals("AB_09-X",Stage1Rules.skuCode(" ab_09-x "));assertEquals(64,Stage1Rules.skuCode("a".repeat(64)).length());
        assertThrows(Stage1Exception.class,()->Stage1Rules.skuCode("a".repeat(65)));assertThrows(Stage1Exception.class,()->Stage1Rules.skuCode("a b"));
        assertEquals(Set.of("EXPRESS"),Stage1Rules.intersection(List.of(Set.of("EXPRESS","COMMUNITY_PICKUP"),Set.of("EXPRESS"))));assertTrue(Stage1Rules.intersection(List.of(Set.of("EXPRESS"),Set.of("IN_STORE_VERIFY"))).isEmpty());
    }
    @Test void mediaUsesBytesNotFilenameAndPrivatePdfCannotBePublicGoods() throws Exception {
        var bytes=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(2,2,BufferedImage.TYPE_INT_RGB),"png",bytes);assertEquals("image/png",Stage1Files.detect(bytes.toByteArray(),true));
        assertThrows(Stage1Exception.class,()->Stage1Files.detect("<svg onload='x'>".getBytes(),false));
        assertThrows(Stage1Exception.class,()->Stage1Files.detect(Arrays.copyOf(bytes.toByteArray(),24),true));
        byte[] pdf="%PDF-1.4\n1 0 obj << /Type /Catalog >> endobj\n2 0 obj << /Type /Page >> endobj\n%%EOF".getBytes();
        assertThrows(Stage1Exception.class,()->Stage1Files.detect(pdf,false));assertThrows(Stage1Exception.class,()->Stage1Files.detect(pdf,true));
    }
}
