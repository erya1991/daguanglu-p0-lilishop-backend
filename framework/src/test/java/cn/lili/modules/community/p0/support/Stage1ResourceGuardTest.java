package cn.lili.modules.community.p0.support;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

class Stage1ResourceGuardTest {
    @TempDir Path temporary;
    Stage1Db db;Stage1Settings settings;MockEnvironment env;Map<String,Object> identity;Path run;
    @BeforeEach void setup()throws Exception{
        db=mock(Stage1Db.class);settings=new Stage1Settings();settings.setRunId("S1_20261027_000000");settings.setMysqlUuid("S1_TEST_UUID");
        run=Files.createDirectories(temporary.resolve(settings.getRunId()));settings.setFileRoot(Files.createDirectory(run.resolve("files")).toString());Files.writeString(run.resolve("owner.json"),write(map("runId",settings.getRunId())));
        identity=map("uuid","S1_TEST_UUID","database_name","dgl_p0_s1","account","s1_app@localhost");when(db.required(anyString())).thenAnswer(call->identity);when(db.count(anyString())).thenAnswer(call->call.<String>getArgument(0).contains("information_schema")?8L:1L);
        env=new MockEnvironment().withProperty("spring.data.redis.host","127.0.0.1").withProperty("spring.data.redis.port","19379");for(String key:List.of("lili.message.consumer.enabled","lili.promotion.delay-listener.enabled","lili.mqtt.enabled"))env.setProperty(key,"false");for(String unit:List.of("minute","hour","day"))env.setProperty("lili.schedule.every-"+unit+"-cron","-");
    }
    void guard(){new Stage1ResourceGuard(db,settings,env).afterSingletonsInstantiated();}
    void refuses(){assertEquals("STAGE1_RESOURCE_NOT_REGISTERED",assertThrows(IllegalStateException.class,this::guard).getMessage());}
    @Test void registeredRunPassesOnlyReadOnlyIdentityAndStructureProbes(){guard();for(var invocation:mockingDetails(db).getInvocations()){assertTrue(Set.of("required","count").contains(invocation.getMethod().getName()));assertTrue(invocation.<String>getArgument(0).startsWith("SELECT "));}}
    @Test void wrongUuidOldDatabaseAccountOrSchemaNeverReachesBusinessWrites(){
        for(var entry:Map.of("uuid","UNKNOWN","database_name","daguanglu_f0103_dev","account","root@localhost").entrySet()){Object old=identity.put(entry.getKey(),entry.getValue());refuses();identity.put(entry.getKey(),old);}
        settings.setMysqlUuid("8d54bca3-ccfc-11f1-8a9b-1e861828a1bc");identity.put("uuid",settings.getMysqlUuid());refuses();settings.setMysqlUuid("S1_TEST_UUID");identity.put("uuid","S1_TEST_UUID");when(db.count(anyString())).thenReturn(0L);refuses();
        assertTrue(mockingDetails(db).getInvocations().stream().allMatch(i->i.getMethod().getName().equals("required")||i.getMethod().getName().equals("count")));
    }
    @Test void oldRedisUnknownFilesBackgroundTasksAndRealOutboundAreRejected()throws Exception{
        env.setProperty("spring.data.redis.port","16379");refuses();env.setProperty("spring.data.redis.port","19379");env.setProperty("spring.data.redis.host","remote.invalid");refuses();env.setProperty("spring.data.redis.host","127.0.0.1");
        for(String key:List.of("lili.message.consumer.enabled","lili.promotion.delay-listener.enabled","lili.mqtt.enabled")){env.setProperty(key,"true");refuses();env.setProperty(key,"false");}
        for(String unit:List.of("minute","hour","day")){String key="lili.schedule.every-"+unit+"-cron";env.setProperty(key,"0 * * * * ?");refuses();env.setProperty(key,"-");}
        settings.setRealOutboundEnabled(true);refuses();settings.setRealOutboundEnabled(false);Files.writeString(run.resolve("owner.json"),write(map("runId","OTHER_RUN")));refuses();Files.writeString(run.resolve("owner.json"),write(map("runId",settings.getRunId())));String original=settings.getFileRoot();settings.setFileRoot(temporary.resolve("unknown-files").toString());refuses();settings.setFileRoot(original);guard();
    }
}
