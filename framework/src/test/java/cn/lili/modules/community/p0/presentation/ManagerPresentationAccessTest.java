package cn.lili.modules.community.p0.presentation;
import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.support.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
class ManagerPresentationAccessTest {
    @Test void routesAreReadOnlyFiniteAndManagerOnly(){var routes=Stage1Routes.all().stream().filter(r->r.template().contains("/ui/")).toList();assertEquals(20,routes.size());assertEquals(3,routes.stream().filter(r->r.template().contains("/ui/stage2-")).count());for(var r:routes){assertEquals("GET",r.method());assertEquals(SessionDomain.MANAGER,r.domain());var p=r.template().replace("{id}","123");assertNull(Stage1Routes.find(SessionDomain.STORE,"GET",p));assertNull(Stage1Routes.find(SessionDomain.MANAGER,"POST",p));assertNull(Stage1Routes.find(SessionDomain.MANAGER,"GET",p+"/unlisted"));}}
    @Test void projectionsUseReadOnlyTransactionsAndStage1Assembly(){for(var type:List.of(ManagerPresentationService.class,ManagerLookupService.class)){assertTrue(type.getAnnotation(Transactional.class).readOnly());assertNotNull(type.getAnnotation(P0BusinessEnabled.class));assertArrayEquals(new Class<?>[]{ManagerPresentationCondition.class},type.getAnnotation(org.springframework.context.annotation.Conditional.class).value());}}
    @Test void unauthorisedLookupNeverTouchesData(){var db=mock(Stage1Db.class);var context=mock(Stage1Context.class);var access=mock(Stage1Access.class);var actor=mock(Stage1Context.Actor.class);when(context.require(SessionDomain.MANAGER)).thenReturn(actor);when(actor.id()).thenReturn("123");when(access.managerPermissions("123")).thenReturn(Set.of("store.read"));var lookup=new ManagerLookupService(db,context,access,new Stage1Settings());assertThrows(Stage1Exception.class,()->lookup.options("receivers",null,null,"456","789",null,1,20));verifyNoInteractions(db);}
    @Test void categoryLookupRequiresDeclaredPurpose(){var db=mock(Stage1Db.class);var context=mock(Stage1Context.class);var access=mock(Stage1Access.class);var lookup=new ManagerLookupService(db,context,access,new Stage1Settings());assertThrows(Stage1Exception.class,()->lookup.options("categories",null,null,null,null,"anything",1,20));verifyNoInteractions(db);}
    @Test void treePathKeepsStringIds(){var lookup=new ManagerLookupService(mock(Stage1Db.class),mock(Stage1Context.class),mock(Stage1Access.class),new Stage1Settings());var rows=List.of(map("id","2115097764975017984","name","日用","parentId","0"),map("id","2115097764975017985","name","清洁","parentId","2115097764975017984"));assertEquals("日用 / 清洁",lookup.categoryPath("2115097764975017985",rows));assertTrue(lookup.categoryPath("999",rows).contains("未提供"));}
    @Test void commercialHistoryRequiresItsOwnPermissionBeforeReadingData(){
        for(var type:List.of("AGREEMENT","RULE")){
            var db=mock(Stage1Db.class);var context=mock(Stage1Context.class);var access=mock(Stage1Access.class);var actor=mock(Stage1Context.Actor.class);
            when(context.require(SessionDomain.MANAGER)).thenReturn(actor);when(actor.id()).thenReturn("123");when(access.managerPermissions("123")).thenReturn(Set.of("store.read"));
            var lookup=new ManagerLookupService(db,context,access,new Stage1Settings());var service=new ManagerPresentationService(db,context,lookup,null,null,null);
            var error=assertThrows(Stage1Exception.class,()->service.objectHistory("456",type,"789",1,20));assertEquals(403,error.status());verifyNoInteractions(db);
        }
    }
    @Test void commercialHistoryRejectsUnboundedScopes(){
        var db=mock(Stage1Db.class);var service=new ManagerPresentationService(db,mock(Stage1Context.class),mock(ManagerLookupService.class),null,null,null);
        for(var type:List.of("ANY","rule","%")){var e=assertThrows(Stage1Exception.class,()->service.objectHistory("456",type,"789",1,20));assertEquals(422,e.status());}
        assertThrows(Stage1Exception.class,()->service.objectHistory("456",null,"789",1,20));verifyNoInteractions(db);
    }
    @Test void commercialHistoryIsBoundToStoreObjectAndFiniteActionPrefix(){
        var calls=new ArrayList<String>();var db=mock(Stage1Db.class,invocation->{String name=invocation.getMethod().getName();if(name.equals("required")){calls.add((String)invocation.getArgument(0));return map("id","789");}if(name.equals("count"))return 1L;if(name.equals("rows")){calls.add((String)invocation.getArgument(0));return List.of(map("id","987","action","rule.disable","reason","合成停用原因","identity_domain","MANAGER","actor_id","123","operator_name","合成财务"));}return RETURNS_DEFAULTS.answer(invocation);});
        var lookup=mock(ManagerLookupService.class);var service=new ManagerPresentationService(db,mock(Stage1Context.class),lookup,null,null,null);var value=object(service.objectHistory("456","RULE","789",1,20));
        verify(lookup).require("store.read");verify(lookup).require("rule.read");assertEquals("RULE",value.get("objectType").getAsString());assertEquals("789",value.get("objectId").getAsString());assertEquals("合成停用原因",value.getAsJsonArray("records").get(0).getAsJsonObject().get("reason").getAsString());
        assertTrue(calls.get(0).contains("li_p0_commission_rule WHERE id=? AND store_id=?"));assertTrue(calls.get(1).contains("a.store_id=? AND a.object_id=? AND a.action LIKE ?"));
    }
    @Test void categoryFilterCountsBeforePagingAndNeverLoadsTheWholeCatalog(){
        var calls=new ArrayList<String>();var db=mock(Stage1Db.class,invocation->{if(invocation.getMethod().getName().equals("count")){calls.add((String)invocation.getArgument(0));return 42L;}if(invocation.getMethod().getName().equals("rows")){calls.add((String)invocation.getArgument(0));return List.of();}return RETURNS_DEFAULTS.answer(invocation);});
        var lookup=mock(ManagerLookupService.class);when(lookup.categoryIds("123")).thenReturn(List.of("123","456"));var core=mock(cn.lili.modules.community.p0.catalog.GoodsManagementService.class);var service=new ManagerPresentationService(db,mock(Stage1Context.class),lookup,null,core,null);
        var value=object(service.goods(null,null,null,null,null,"IN_STOCK","123",false,3,20));assertEquals(42,value.get("total").getAsLong());assertEquals(3,value.get("pageNumber").getAsInt());assertTrue(value.getAsJsonArray("records").isEmpty());assertEquals(2,calls.size());assertTrue(calls.get(0).startsWith("SELECT COUNT(*)"));assertTrue(calls.get(0).contains("c.category_id IN (?,?)"));assertTrue(calls.get(0).contains("s.quantity>0"));assertTrue(calls.get(1).endsWith("LIMIT ? OFFSET ?"));verify(core).list(null,null,null,null,null,"IN_STOCK",1,1);verifyNoMoreInteractions(core);
    }
    @Test void filteredQueryRetainsOriginalServiceValidationBeforeAnyDatabaseRead(){
        var db=mock(Stage1Db.class);var lookup=mock(ManagerLookupService.class);var core=mock(cn.lili.modules.community.p0.catalog.GoodsManagementService.class);when(core.list(null,"bad",null,null,null,null,1,1)).thenThrow(new Stage1Exception(422,"GOODS_STATUS_INVALID"));var service=new ManagerPresentationService(db,mock(Stage1Context.class),lookup,null,core,null);var error=assertThrows(Stage1Exception.class,()->service.goods(null,"bad",null,null,null,null,"123",false,1,20));assertEquals("GOODS_STATUS_INVALID",error.code());verifyNoInteractions(db);
    }
    @Test void lookupReadPermissionsDoNotGrantStoreOrCommercialOperations(){
        var permissions=Set.of("goods.read","goods.review");
        for(String type:List.of("categories","stores","units"))assertTrue(Stage1Access.managerRouteAllowed(permissions,"store.read","GET","/manager/p0/s1/ui/lookups/"+type));
        assertFalse(Stage1Access.managerRouteAllowed(Set.of(),"store.read","GET","/manager/p0/s1/ui/lookups/categories"));
        assertFalse(Stage1Access.managerRouteAllowed(permissions,"store.read","GET","/manager/p0/s1/stores/123/eligibility"));
        assertFalse(Stage1Access.managerRouteAllowed(permissions,"channel.receiver","GET","/manager/p0/s1/ui/lookups/receivers"));
        assertFalse(Stage1Access.managerRouteAllowed(permissions,"store.read","POST","/manager/p0/s1/ui/lookups/categories"));
        var db=mock(Stage1Db.class);var context=mock(Stage1Context.class);var access=mock(Stage1Access.class);var actor=mock(Stage1Context.Actor.class);
        when(context.require(SessionDomain.MANAGER)).thenReturn(actor);when(actor.id()).thenReturn("123");when(access.managerPermissions("123")).thenReturn(permissions);
        var lookup=new ManagerLookupService(db,context,access,new Stage1Settings());
        assertNotNull(lookup.options("categories",null,null,null,null,"goods",1,20));
        assertThrows(Stage1Exception.class,()->lookup.options("categories",null,null,null,null,"admission",1,20));
    }
}
