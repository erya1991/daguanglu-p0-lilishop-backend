package cn.lili.modules.community.p0.stage45;

import cn.lili.modules.community.p0.stage2.Stage2Settings;
import cn.lili.modules.community.p0.support.*;
import org.springframework.core.env.Environment;
import java.nio.file.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

public final class Stage45ResourceScope {
    private Stage45ResourceScope() {}
    public static void verify(Stage1Db db,Stage2Settings s,Environment env)throws Exception {
        if(!s.isEnabled() || !s.isStage45ResourceMode() || s.isStage3ResourceMode()
            || !env.getProperty("p0.stage45.enabled",Boolean.class,false)
            || !env.getProperty("p0.stage3.enabled",Boolean.class,false)
            || env.getProperty("p0.stage1.enabled",Boolean.class,false)
            || !"TEST".equals(s.getEnvironment()) || !"MOCK".equals(s.getMode()) || !"dgl_p0_s45".equals(s.getDatabase()))
            throw new IllegalStateException("STAGE45_RESOURCE_NOT_REGISTERED");
        var row=db.required("SELECT @@server_uuid uuid,DATABASE() db,CURRENT_USER() account");
        if(s.getMysqlUuid()==null || !s.getMysqlUuid().equals(id(row,"uuid")) || !"dgl_p0_s45".equals(id(row,"db"))
            || !id(row,"account").startsWith("s45_app@")
            || Set.of("bffc3648-d139-11f1-afc9-9617b0124629","043ca7b1-d2d6-11f1-889b-7af81f86d1ff","bc5b6947-d500-11f1-ba08-7205f988de2c").contains(s.getMysqlUuid()))
            throw new IllegalStateException("STAGE45_RESOURCE_NOT_REGISTERED");
        Path files=Path.of(s.getFileRoot()).toRealPath(),run=files.getParent();
        var owner=object(Files.readString(run.resolve("owner.json")));
        if(!run.getParent().equals(Path.of("D:/git/daguanglu-p0-stage45/.stage45-runtime").toRealPath())
            || !run.getFileName().toString().matches("S45_[0-9]{8}_[0-9]{6}") || !files.getFileName().toString().equals("files")
            || owner.get("stage").getAsInt()!=45 || !s.getRunId().equals(owner.get("runId").getAsString()))
            throw new IllegalStateException("STAGE45_RESOURCE_NOT_REGISTERED");
        if(!"127.0.0.1".equals(env.getProperty("spring.data.redis.host")) || !"19679".equals(env.getProperty("spring.data.redis.port")))
            throw new IllegalStateException("STAGE45_CACHE_NOT_REGISTERED");
        for(String key:Set.of("lili.message.consumer.enabled","lili.promotion.delay-listener.enabled","lili.mqtt.enabled"))
            if(!"false".equals(env.getProperty(key)))throw new IllegalStateException("LEGACY_CONSUMER_ENABLED");
        for(String f:Set.of("minute","hour","day"))
            if(!"-".equals(env.getProperty("lili.schedule.every-"+f+"-cron")))throw new IllegalStateException("LEGACY_SCHEDULE_ENABLED");
        if(db.count("SELECT COUNT(*) FROM li_p0_schema_history WHERE migration IN ('S45_001_native.sql','S45_002_business.sql','S45_003_inherited_compat.sql','S45_004_permissions.sql') AND status='DONE'")!=4)
            throw new IllegalStateException("STAGE45_SCHEMA_NOT_REGISTERED");
    }
}
