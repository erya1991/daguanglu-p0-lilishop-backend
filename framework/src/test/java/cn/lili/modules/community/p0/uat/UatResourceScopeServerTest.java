package cn.lili.modules.community.p0.uat;

import cn.lili.modules.community.p0.stage2.Stage2Settings;
import cn.lili.modules.community.p0.support.Stage1Db;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static cn.lili.modules.community.p0.support.Stage1Json.map;
import static org.junit.jupiter.api.Assertions.*;

class UatResourceScopeServerTest {
    @TempDir Path root;
    private static final String RUN = "UAT_20261113000000000";

    private Stage2Settings settings() throws Exception {
        Path run = root.resolve(".uat-runtime").resolve(RUN);
        Files.createDirectories(run.resolve("files"));
        Files.createDirectories(run.resolve("runtime"));
        String workspace = root.toRealPath().toString().replace("\\", "\\\\");
        Files.writeString(run.resolve("owner.json"), "{\"integration\":true,\"runId\":\""+RUN+"\",\"mode\":\"MOCK\",\"environment\":\"TEST\",\"workspace\":\""+workspace+"\",\"purpose\":\"WECHAT_TEST\"}");
        Files.writeString(run.resolve("runtime/resources.json"), "{\"runId\":\""+RUN+"\",\"mysqlUuid\":\"test-uuid\",\"mysqlPort\":19706,\"redisPort\":19779}");
        var s = new Stage2Settings();
        s.setEnabled(true); s.setUatResourceMode(true); s.setServerUatMode(true);
        s.setServerUatRoot(root.toString()); s.setDatabase("dgl_p0_uat");
        s.setRunId(RUN); s.setMysqlUuid("test-uuid"); s.setFileRoot(run.resolve("files").toString());
        return s;
    }

    private MockEnvironment environment() {
        var e = new MockEnvironment().withProperty("p0.uat.enabled","true")
            .withProperty("p0.stage3.enabled","true").withProperty("p0.stage45.enabled","true")
            .withProperty("server.address","127.0.0.1")
            .withProperty("spring.datasource.url","jdbc:mysql://127.0.0.1:19706/dgl_p0_uat?test=true")
            .withProperty("spring.data.redis.host","127.0.0.1").withProperty("spring.data.redis.port","19779")
            .withProperty("lili.stage0-resident-wechat.enabled","true")
            .withProperty("lili.stage0-merchant-wechat.enabled","true")
            .withProperty("p0.uat.local-login-enabled","false");
        for (String k : new String[]{"lili.message.consumer.enabled","lili.promotion.delay-listener.enabled","lili.mqtt.enabled","lili.verification-code.loginBypassEnabled"}) e.setProperty(k,"false");
        for (String k : new String[]{"minute","hour","day"}) e.setProperty("lili.schedule.every-"+k+"-cron","-");
        return e;
    }

    @Test void registeredServerRequiresOwnedMockResources() throws Exception {
        var s = settings();
        assertEquals(root.resolve(".uat-runtime").resolve(RUN).toRealPath(), UatResourceScope.registered(s));
        s.setMysqlUuid("other"); assertThrows(IllegalStateException.class, () -> UatResourceScope.registered(s));
        s.setMysqlUuid("test-uuid"); s.setMode("REAL"); assertThrows(IllegalStateException.class, () -> UatResourceScope.registered(s));
        s.setMode("MOCK"); s.setServerUatRoot("relative"); assertThrows(IllegalStateException.class, () -> UatResourceScope.registered(s));
    }

    @Test void realWechatServerRejectsLocalLoginAndWrongDatabase() throws Exception {
        var s=settings(); var e=environment();
        var db=new Stage1Db(null) {
            boolean wrong;
            @Override public Map<String,Object> required(String sql,Object...args) {
                return map("uuid",wrong?"wrong":"test-uuid","db","dgl_p0_uat","account","uat_app@localhost");
            }
            @Override public long count(String sql,Object...args) { return 5; }
        };
        assertDoesNotThrow(() -> UatResourceScope.verify(db,s,e));
        e.setProperty("p0.uat.local-login-enabled","true");
        assertThrows(IllegalStateException.class, () -> UatResourceScope.verify(db,s,e));
        e.setProperty("p0.uat.local-login-enabled","false");
        e.setProperty("lili.stage0-resident-wechat.enabled","false");
        assertThrows(IllegalStateException.class, () -> UatResourceScope.verify(db,s,e));
        e.setProperty("lili.stage0-resident-wechat.enabled","true");
        db.wrong=true;
        assertThrows(IllegalStateException.class, () -> UatResourceScope.verify(db,s,e));
    }

    @Test void enrollmentRequiresTransactionAndNeverUpserts() throws Exception {
        var s=settings();
        var db=new Stage1Db(null) {
            int calls;
            String statement;
            @Override public int update(String sql,Object...args) { calls++; statement=sql; return 1; }
        };
        var enrollment=new ServerWechatResidentEnrollment(s,db);
        assertThrows(IllegalStateException.class, () -> enrollment.registerNew("123"));
        assertEquals(0,db.calls);
        try {
            TransactionSynchronizationManager.setActualTransactionActive(true);
            assertThrows(IllegalStateException.class, () -> enrollment.registerNew("bad"));
            enrollment.registerNew("123");
            assertEquals(1,db.calls);
            assertFalse(db.statement.toUpperCase().contains("DUPLICATE"));
            s.setMode("REAL");
            assertThrows(IllegalStateException.class, () -> enrollment.registerNew("124"));
            assertEquals(1,db.calls);
        } finally {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
    }

    @Test void alternatePortsMustMatchRegistryAndLocalModeRetainsHistoricalPorts() throws Exception {
        var s=settings();
        s.setServerMysqlPort(29706);s.setServerRedisPort(29779);
        assertThrows(IllegalStateException.class,()->UatResourceScope.registered(s));
        Path registry=root.resolve(".uat-runtime").resolve(RUN).resolve("runtime/resources.json");
        Files.writeString(registry,Files.readString(registry).replace("19706","29706").replace("19779","29779"));
        assertDoesNotThrow(()->UatResourceScope.registered(s));
        s.setServerRedisPort(29706);
        assertThrows(IllegalStateException.class,()->UatResourceScope.registered(s));
        s.setServerUatMode(false);
        assertEquals(19706,s.uatMysqlPort());assertEquals(19779,s.uatRedisPort());
    }
}
