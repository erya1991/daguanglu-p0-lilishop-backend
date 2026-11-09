
package cn.lili.modules.community.p0.uat;
import cn.lili.modules.community.p0.stage2.Stage2Settings;
import cn.lili.modules.community.p0.support.*;
import org.springframework.core.env.Environment;
import java.nio.file.*;
import java.util.Set;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Separate registered UAT ownership, never impersonates an older stage resource. */
public final class UatResourceScope {
    private UatResourceScope() { }
    public static Path registered(Stage2Settings s) {
        try {
            if(!s.isEnabled()||!s.isUatResourceMode()||s.isStage3ResourceMode()||s.isStage45ResourceMode()
                ||!"TEST".equals(s.getEnvironment())||!"MOCK".equals(s.getMode())||!"dgl_p0_uat".equals(s.getDatabase()))throw new IllegalStateException();
            Path root=Path.of("D:/git/daguanglu-p0-uat").toRealPath(),files=Path.of(s.getFileRoot()).toRealPath(),run=files.getParent();
            if(!files.getFileName().toString().equals("files")||!run.getParent().equals(root.resolve(".uat-runtime").toRealPath())||!run.getFileName().toString().matches("UAT_[0-9]{17}")||!run.getFileName().toString().equals(s.getRunId()))throw new IllegalStateException();
            var owner=object(Files.readString(run.resolve("owner.json")));var r=object(Files.readString(run.resolve("runtime/resources.json")));
            if(!owner.get("integration").getAsBoolean()||!s.getRunId().equals(owner.get("runId").getAsString())||!"MOCK".equals(owner.get("mode").getAsString())
                ||!"TEST".equals(owner.get("environment").getAsString())||!Path.of(owner.get("workspace").getAsString()).toRealPath().equals(root)
                ||!s.getRunId().equals(r.get("runId").getAsString())||!s.getMysqlUuid().equals(r.get("mysqlUuid").getAsString())
                ||r.get("mysqlPort").getAsInt()!=19706||r.get("redisPort").getAsInt()!=19779)throw new IllegalStateException();
            return run;
        }catch(Exception e){throw new IllegalStateException("UAT_RESOURCE_NOT_REGISTERED");}
    }
    public static void verify(Stage1Db db,Stage2Settings s,Environment env) {
        registered(s);
        if(!env.getProperty("p0.uat.enabled",Boolean.class,false)||!env.getProperty("p0.stage3.enabled",Boolean.class,false)||!env.getProperty("p0.stage45.enabled",Boolean.class,false)
            ||env.getProperty("p0.stage1.enabled",Boolean.class,false)||!"127.0.0.1".equals(env.getProperty("server.address")))throw new IllegalStateException("UAT_MODE_REQUIRED");
        var row=db.required("SELECT @@server_uuid uuid,DATABASE() db,CURRENT_USER() account");
        if(!s.getMysqlUuid().equals(id(row,"uuid"))||!"dgl_p0_uat".equals(id(row,"db"))||!id(row,"account").startsWith("uat_app@")
            ||!env.getProperty("spring.datasource.url","").startsWith("jdbc:mysql://127.0.0.1:19706/dgl_p0_uat?")
            ||!"127.0.0.1".equals(env.getProperty("spring.data.redis.host"))||!"19779".equals(env.getProperty("spring.data.redis.port")))throw new IllegalStateException("UAT_RESOURCE_MISMATCH");
        for(String key:Set.of("lili.message.consumer.enabled","lili.promotion.delay-listener.enabled","lili.mqtt.enabled","lili.stage0-resident-wechat.enabled","lili.stage0-merchant-wechat.enabled","lili.verification-code.loginBypassEnabled"))
            if(!"false".equals(env.getProperty(key)))throw new IllegalStateException("UAT_OUTBOUND_OR_BYPASS_ENABLED");
        for(String f:Set.of("minute","hour","day"))if(!"-".equals(env.getProperty("lili.schedule.every-"+f+"-cron")))throw new IllegalStateException("LEGACY_SCHEDULE_ENABLED");
        if(db.count("SELECT COUNT(*) FROM li_p0_schema_history WHERE migration IN ('S45_001_native.sql','S45_002_business.sql','S45_003_inherited_compat.sql','S45_004_permissions.sql','UAT_002_manager_security.sql') AND status='DONE'")!=5)throw new IllegalStateException("UAT_SCHEMA_REQUIRED");
    }
}
