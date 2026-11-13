package cn.lili.modules.community.p0.support;

import cn.lili.common.security.session.*;
import cn.lili.common.security.AuthUser;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.session.http.SessionApplications;
import cn.lili.modules.community.p0.member.ResidentManagerService;
import cn.lili.modules.member.mapper.MerchantGrantMapper;
import cn.lili.modules.permission.manager.NativeManagerAccess;
import cn.lili.modules.community.p0.stage3.Stage3Authorization;
import cn.lili.modules.community.p0.stage45.Stage45Authorization;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.Clock;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResidentManagerSecurityTest {
    @Test void onlyManagerHasFiniteResidentRoutesAndSeparatePermissions(){
        String base="/manager/p0/s1/residents";
        assertEquals("resident.read",Stage1Routes.find(SessionDomain.MANAGER,"POST",base+"/search").action());
        assertEquals("resident.read",Stage1Routes.find(SessionDomain.MANAGER,"GET",base+"/12").action());
        assertEquals("resident.manage",Stage1Routes.find(SessionDomain.MANAGER,"GET",base+"/12/impact").action());
        assertEquals("resident.manage",Stage1Routes.find(SessionDomain.MANAGER,"POST",base+"/12/disable").action());
        assertNull(Stage1Routes.find(SessionDomain.MANAGER,"DELETE",base+"/12"));
        assertNull(Stage1Routes.find(SessionDomain.STORE,"POST",base+"/12/enable"));
        assertFalse(Stage1Access.managerRouteAllowed(Set.of("resident.read"),"resident.manage","POST",base+"/12/disable"));
    }
    @Test void memberCutoffIsAppliedToEachPersonalDomainEvenAfterAccountReenabled(){
        Stage1Db db=mock(Stage1Db.class,inv->{
            if(inv.getMethod().getName().equals("one"))return Map.of("session_cutoff",1000L);
            if(inv.getMethod().getName().equals("rows"))return List.of();
            return RETURNS_DEFAULTS.answer(inv);
        });
        Stage1Access access=new Stage1Access(db,new Stage1Settings(),mock(MerchantGrantMapper.class));
        ReflectionTestUtils.setField(access,"nativeManager",mock(NativeManagerAccess.class));
        for(SessionDomain domain:List.of(SessionDomain.RESIDENT,SessionDomain.APPLICANT,SessionDomain.STORE)){
            SessionIdentity identity=new SessionIdentity("42",domain,"app","7",1L);
            assertThrows(SessionException.class,()->access.checkIssuedAt(identity,1000));
            assertDoesNotThrow(()->access.checkIssuedAt(identity,1001));
        }
        for(UserEnums role:List.of(UserEnums.MEMBER,UserEnums.STORE)){
            AuthUser user=AuthUser.builder().id("42").role(role).build();
            assertThrows(SessionException.class,()->access.checkLegacy(user,null));
            assertThrows(SessionException.class,()->access.checkLegacy(user,1000L));
            assertDoesNotThrow(()->access.checkLegacy(user,1001L));
        }
    }
    @Test void residentSearchUsesServerApplicationAndMasksPhone(){
        List<String> queries=new ArrayList<>();
        Stage1Db db=mock(Stage1Db.class,inv->{
            if(inv.getMethod().getName().equals("count")){String sql=inv.getArgument(0);queries.add(sql);return 1L;}
            if(inv.getMethod().getName().equals("rows")){String sql=inv.getArgument(0);queries.add(sql);return List.of(Map.of("id",123L,"nick_name","测试","mobile","13812345678","disabled",true));}
            return RETURNS_DEFAULTS.answer(inv);
        });
        SessionApplications apps=new SessionApplications();apps.setResidentApplication("resident-app");apps.setMerchantApplication("merchant-app");
        var service=new ResidentManagerService(db,mock(Stage1Context.class),mock(Stage1Mutations.class),apps,Clock.systemUTC());
        Map<?,?> result=(Map<?,?>)service.search(Map.of("pageNumber",1,"pageSize",20,"mobile","13812345678"));
        Map<?,?> row=(Map<?,?>)((List<?>)result.get("records")).getFirst();
        assertEquals("138****5678",row.get("mobileMasked"));assertFalse(row.containsKey("mobile"));
        assertTrue(queries.getFirst().contains("w.app_id=?"));assertTrue(queries.getFirst().contains("m.mobile=?"));
    }
    @Test void missingApplicationFailsRatherThanListingAllMembers(){
        Stage1Db db=mock(Stage1Db.class);SessionApplications apps=new SessionApplications();
        var service=new ResidentManagerService(db,mock(Stage1Context.class),mock(Stage1Mutations.class),apps,Clock.systemUTC());
        assertThrows(SessionException.class,()->service.search(Map.of("pageNumber",1,"pageSize",20)));
        verifyNoInteractions(db);
    }
    @Test void residentReadAloneDoesNotGrantOrdersOrAftersales(){
        Stage1Db db=mock(Stage1Db.class,inv->{if(inv.getMethod().getName().equals("required"))return Map.of("id",42L,"disabled",true);return RETURNS_DEFAULTS.answer(inv);});
        var context=mock(Stage1Context.class);var actor=mock(Stage1Context.Actor.class);when(actor.id()).thenReturn("9");when(context.require(SessionDomain.MANAGER)).thenReturn(actor);
        SessionApplications apps=new SessionApplications();apps.setResidentApplication("resident-app");apps.setMerchantApplication("merchant-app");
        var service=new ResidentManagerService(db,context,mock(Stage1Mutations.class),apps,Clock.systemUTC());
        Stage3Authorization orders=mock(Stage3Authorization.class);when(orders.managerPermissions("9")).thenReturn(Set.of());
        Stage45Authorization sales=mock(Stage45Authorization.class);when(sales.managerPermissions("9")).thenReturn(Set.of());
        ReflectionTestUtils.setField(service,"orderAccess",orders);ReflectionTestUtils.setField(service,"saleAccess",sales);
        assertEquals(403,assertThrows(Stage1Exception.class,()->service.orders("42",1,20)).status());
        assertEquals(403,assertThrows(Stage1Exception.class,()->service.aftersales("42",1,20)).status());
    }
}
