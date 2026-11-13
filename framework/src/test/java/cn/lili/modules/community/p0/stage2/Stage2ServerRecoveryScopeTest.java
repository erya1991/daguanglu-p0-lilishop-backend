package cn.lili.modules.community.p0.stage2;

import cn.lili.common.security.session.http.SessionApplications;
import cn.lili.modules.community.p0.support.Stage1Db;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;

class Stage2ServerRecoveryScopeTest {
    @TempDir Path root;
    @Test void discoversOnlyRegisteredRunAndMerchantApplication() throws Exception {
        String runId="UAT_20261113000000000";
        Path run=root.resolve(".uat-runtime").resolve(runId);
        Files.createDirectories(run.resolve("files")); Files.createDirectories(run.resolve("runtime"));
        String workspace=root.toRealPath().toString().replace("\\","\\\\");
        Files.writeString(run.resolve("owner.json"),"{\"integration\":true,\"runId\":\""+runId+"\",\"mode\":\"MOCK\",\"environment\":\"TEST\",\"workspace\":\""+workspace+"\",\"purpose\":\"WECHAT_TEST\"}");
        Files.writeString(run.resolve("runtime/resources.json"),"{\"runId\":\""+runId+"\",\"mysqlUuid\":\"test-uuid\",\"mysqlPort\":19706,\"redisPort\":19779}");
        var s=new Stage2Settings();s.setEnabled(true);s.setUatResourceMode(true);s.setServerUatMode(true);
        s.setServerUatRoot(root.toString());s.setDatabase("dgl_p0_uat");s.setRunId(runId);
        s.setMysqlUuid("test-uuid");s.setFileRoot(run.resolve("files").toString());s.setWorkersEnabled(true);
        var body=map("runId",runId,"evidence","isolated test","admittedAt",Instant.now().minusSeconds(10),
            "dynamicOwnedUat",true,"members",List.of(),"stores",List.of(),"skus",List.of());
        Path file=run.resolve("scope.json");byte[] bytes=write(body).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file,bytes);s.setRecoveryScopeFile(file.toString());
        s.setRecoveryScopeSha256(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        var db=new Stage1Db(null) {
            boolean populated; String app;
            @Override public List<Map<String,Object>> rows(String sql,Object...args) {
                if(sql.contains("FROM li_p0_s2_member_scope")) return populated?List.of(map("id","101")):List.of();
                if(sql.contains("SELECT DISTINCT c.store_id")) {app=(String)args[0];return populated?List.of(map("id","201")):List.of();}
                if(sql.contains("SELECT c.sku_id")) return populated?List.of(map("id","301")):List.of();
                throw new AssertionError(sql);
            }
            @Override public long count(String sql,Object...args) {
                return sql.contains("FROM li_p0_trade_state t WHERE")?0:populated?1:0;
            }
        };
        var scope=new Stage2RecoveryScope(s,db);
        assertThrows(IllegalStateException.class,scope::current);
        var applications=new SessionApplications();applications.setMerchantApplication("wx_merchant_test");applications.setResidentApplication("wx_resident_test");
        scope.setApplications(applications);
        assertTrue(scope.current().members().isEmpty());
        db.populated=true;
        var selected=scope.current();
        assertEquals(List.of("101"),selected.members());assertEquals(List.of("201"),selected.stores());
        assertEquals(List.of("301"),selected.skus());assertEquals("wx_merchant_test",db.app);
        s.setMode("REAL");assertThrows(IllegalStateException.class,scope::current);
    }
}
