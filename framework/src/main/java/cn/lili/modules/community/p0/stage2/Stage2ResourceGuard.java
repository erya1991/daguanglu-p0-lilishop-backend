package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.support.*;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.Set;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Component @ConditionalOnProperty(name="p0.stage2.enabled", havingValue="true")
public class Stage2ResourceGuard implements SmartInitializingSingleton {
    private final Stage1Db db; private final Stage2Settings settings; private final Environment env;
    public Stage2ResourceGuard(Stage1Db db, Stage2Settings settings, Environment env) { this.db=db; this.settings=settings; this.env=env; }
    @Override public void afterSingletonsInstantiated() {
        try {
            if(settings.isUatResourceMode()||env.getProperty("p0.uat.enabled",Boolean.class,false)){cn.lili.modules.community.p0.uat.UatResourceScope.verify(db,settings,env);return;}
            if(settings.isStage45ResourceMode()){cn.lili.modules.community.p0.stage45.Stage45ResourceScope.verify(db,settings,env);return;}
            if(env.getProperty("p0.stage45.enabled",Boolean.class,false))throw new IllegalStateException();
            if(settings.isStage3ResourceMode()){cn.lili.modules.community.p0.stage3.Stage3ResourceScope.verify(db,settings,env);return;}
            if(env.getProperty("p0.stage3.enabled",Boolean.class,false))throw new IllegalStateException();
            if (env.getProperty("p0.stage1.enabled", Boolean.class, false) || !"TEST".equals(settings.getEnvironment()) || !"MOCK".equals(settings.getMode())) throw new IllegalStateException();
            var identity = db.required("SELECT @@server_uuid AS uuid,DATABASE() AS database_name,CURRENT_USER() AS account");
            if (settings.getMysqlUuid() == null || !settings.getMysqlUuid().equals(id(identity,"uuid")) || !"dgl_p0_s2".equals(id(identity,"database_name")) || !id(identity,"account").startsWith("s2_app@")) throw new IllegalStateException();
            if (Set.of("bffc3648-d139-11f1-afc9-9617b0124629", "8d54bca3-ccfc-11f1-8a9b-1e861828a1bc").contains(id(identity,"uuid"))) throw new IllegalStateException();
            Path files=Path.of(settings.getFileRoot()).toRealPath(), run=files.getParent();
            var owner=object(Files.readString(run.resolve("owner.json")));
            if (!run.getFileName().toString().matches("S2_[0-9]{8}_[0-9]{6}") || !settings.getRunId().equals(owner.get("runId").getAsString()) || owner.get("stage").getAsInt()!=2 || !run.getParent().getFileName().toString().equals(".stage2-runtime")) throw new IllegalStateException();
            if (!"127.0.0.1".equals(env.getProperty("spring.data.redis.host")) || !"19479".equals(env.getProperty("spring.data.redis.port"))) throw new IllegalStateException();
            for (String key : Set.of("lili.message.consumer.enabled", "lili.promotion.delay-listener.enabled", "lili.mqtt.enabled")) if (!"false".equals(env.getProperty(key))) throw new IllegalStateException();
            for (String frequency : Set.of("minute","hour","day")) if (!"-".equals(env.getProperty("lili.schedule.every-"+frequency+"-cron"))) throw new IllegalStateException();
            if (db.count("SELECT COUNT(*) FROM li_p0_schema_history WHERE migration IN ('S2_001_core.sql','S2_003_payment_schedule.sql','S2_004_task_generation.sql','S2_005_close_reason.sql','S2_006_close_details.sql') AND status='DONE'")!=5) throw new IllegalStateException();
        } catch (Exception rejected) { throw new IllegalStateException("STAGE2_RESOURCE_NOT_REGISTERED"); }
    }
}
