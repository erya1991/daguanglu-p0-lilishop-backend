package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.support.Stage1Db;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class Stage2RecoveryScopeTest {
    @TempDir Path run;
    Stage2Settings settings; Stage1Db db; Map<String,Object> body;
    @BeforeEach void setup()throws Exception {
        Files.createDirectory(run.resolve("files"));settings=new Stage2Settings();settings.setRunId("S2_20261028_214544");settings.setFileRoot(run.resolve("files").toString());settings.setWorkersEnabled(true);
        db=mock(Stage1Db.class,inv->{if(inv.getMethod().getName().equals("count")){String sql=(String)inv.getRawArguments()[0];return sql.contains("FROM li_p0_trade_state t WHERE")?0L:1L;}return null;});
        body=map("runId",settings.getRunId(),"evidence","explicit fixture IDs","admittedAt",Instant.now().minusSeconds(10),"members",List.of("101"),"stores",List.of("201"),"skus",List.of("301"));save();
    }
    void save()throws Exception {Path file=run.resolve("scope.json");byte[] value=write(body).getBytes(java.nio.charset.StandardCharsets.UTF_8);Files.write(file,value);settings.setRecoveryScopeFile(file.toString());settings.setRecoveryScopeSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value)));}
    @Test void onlyHashBoundRegisteredIdentitiesCanBeLoaded() {
        var scope=new Stage2RecoveryScope(settings,db);assertEquals(List.of("101"),scope.current().members());assertEquals(List.of("301"),scope.current().skus());
    }
    @Test void tamperingIsRejectedEvenAfterTheEnrollmentWasLoaded()throws Exception {
        var scope=new Stage2RecoveryScope(settings,db);scope.current();Files.writeString(run.resolve("scope.json"),"{}");assertThrows(IllegalStateException.class,scope::current);
    }
    @Test void missingOrEmptyEnrollmentNeverMeansAllRows()throws Exception {
        settings.setRecoveryScopeFile(null);assertThrows(IllegalStateException.class,()->new Stage2RecoveryScope(settings,db).current());
        body.put("members",List.of());save();assertThrows(IllegalStateException.class,()->new Stage2RecoveryScope(settings,db).current());verifyNoInteractions(db);
    }
    @Test void duplicateIdsAndSqlFragmentsFailBeforeAnyDatabaseLookup()throws Exception {
        for(var ids:List.of(List.of("101","101"),List.of("101) OR 1=1"))){body.put("members",ids);save();assertThrows(IllegalStateException.class,()->new Stage2RecoveryScope(settings,db).current());}verifyNoInteractions(db);
    }
    @Test void wrongRunOrPathCannotEnrollOtherResources()throws Exception {
        body.put("runId","S2_other");save();assertThrows(IllegalStateException.class,()->new Stage2RecoveryScope(settings,db).current());
        settings.setRecoveryScopeFile(run.getParent().resolve("other.json").toString());assertThrows(IllegalStateException.class,()->new Stage2RecoveryScope(settings,db).current());verifyNoInteractions(db);
    }
    @Test void unregisteredMemberAndOldProtectedTradeRejectStartup() {
        var missing=mock(Stage1Db.class,inv->inv.getMethod().getName().equals("count")?0L:null);assertThrows(IllegalStateException.class,()->new Stage2RecoveryScope(settings,missing).current());
        var protectedTrade=mock(Stage1Db.class,inv->inv.getMethod().getName().equals("count")?1L:null);assertThrows(IllegalStateException.class,()->new Stage2RecoveryScope(settings,protectedTrade).current());
    }
    @Test void ownedUatScopeIncludesNewPageCreatedGoodsWithoutChangingHumanGrants()throws Exception {
        settings.setUatResourceMode(true);body.put("dynamicOwnedUat",true);body.put("merchants",List.of("401"));body.put("stores",List.of());body.put("skus",List.of());save();
        try(var ownership=mockStatic(cn.lili.modules.community.p0.uat.UatResourceScope.class)) {
            ownership.when(()->cn.lili.modules.community.p0.uat.UatResourceScope.registered(settings)).thenReturn(run);
            when(db.rows(contains("SELECT DISTINCT c.store_id"),eq(settings.getRunId()+"_merchant"))).thenReturn(List.of(map("id","201")));
            when(db.rows(contains("SELECT c.sku_id"),eq(settings.getRunId()))).thenReturn(List.of(map("id","301")),List.of(map("id","301"),map("id","302")));
            when(db.count(contains("c.source_run=?"),eq(settings.getRunId()))).thenReturn(1L,2L);
            var scope=new Stage2RecoveryScope(settings,db);assertEquals(List.of("301"),scope.current().skus());assertEquals(List.of("301","302"),scope.current().skus());
            verify(db,never()).update(anyString(),any());
        }
    }
    @Test void dynamicScopeCannotRunOutsideRegisteredUat()throws Exception {
        body.put("dynamicOwnedUat",true);body.put("merchants",List.of("401"));save();
        assertThrows(IllegalStateException.class,()->new Stage2RecoveryScope(settings,db).current());verifyNoInteractions(db);
    }

}
