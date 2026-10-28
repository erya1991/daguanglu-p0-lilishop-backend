package cn.lili.modules.community.p0.support;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.Set;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Fail startup before a business endpoint can target an unregistered local resource. */
@Component
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
public class Stage1ResourceGuard implements SmartInitializingSingleton {
    private final Stage1Db db;private final Stage1Settings settings;private final Environment environment;
    public Stage1ResourceGuard(Stage1Db db,Stage1Settings settings,Environment environment){this.db=db;this.settings=settings;this.environment=environment;}
    @Override public void afterSingletonsInstantiated(){
        try{
            if(!"TEST".equals(settings.getEnvironment())||!settings.permitsMode(settings.getMode())||settings.isRealOutboundEnabled())throw new IllegalStateException();
            var row=db.required("SELECT @@server_uuid AS uuid,DATABASE() AS database_name,CURRENT_USER() AS account");
            if(settings.getMysqlUuid()==null||!settings.getMysqlUuid().equals(id(row,"uuid"))||"8d54bca3-ccfc-11f1-8a9b-1e861828a1bc".equals(id(row,"uuid"))
                    ||!"dgl_p0_s1".equals(settings.getDatabase())||!settings.getDatabase().equals(id(row,"database_name"))||!id(row,"account").startsWith("s1_app@"))throw new IllegalStateException();
            if(db.count("SELECT COUNT(*) FROM li_p0_schema_history WHERE migration='S1_021_exact_native_identifiers.sql' AND status='DONE'")!=1)throw new IllegalStateException();
            if(db.count("SELECT COUNT(*) FROM li_p0_schema_history WHERE migration='S1_022_channel_environments.sql' AND status='DONE'")!=1)throw new IllegalStateException();
            if(db.count("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND data_type='bigint' AND ((table_name='li_user_role' AND column_name IN ('user_id','role_id')) OR (table_name='li_role_menu' AND column_name IN ('menu_id','role_id')) OR (table_name='li_clerk_role' AND column_name IN ('clerk_id','role_id')) OR (table_name='li_goods_sku' AND column_name IN ('goods_id','store_id')))")!=8)throw new IllegalStateException();
            Path files=Path.of(settings.getFileRoot()).toRealPath(),run=files.getParent();
            var owner=object(Files.readString(run.resolve("owner.json")));
            if(!run.getFileName().toString().equals(settings.getRunId())||!settings.getRunId().equals(owner.get("runId").getAsString())||!files.getFileName().toString().equals("files"))throw new IllegalStateException();
            String host=environment.getProperty("spring.data.redis.host",environment.getProperty("spring.redis.host"));
            String port=environment.getProperty("spring.data.redis.port",environment.getProperty("spring.redis.port"));
            if(!"127.0.0.1".equals(host)||!"19379".equals(port))throw new IllegalStateException();
            if(!"false".equals(environment.getProperty("lili.message.consumer.enabled"))||!"false".equals(environment.getProperty("lili.promotion.delay-listener.enabled"))||!"false".equals(environment.getProperty("lili.mqtt.enabled")))throw new IllegalStateException();
            for(String frequency:Set.of("minute","hour","day"))if(!"-".equals(environment.getProperty("lili.schedule.every-"+frequency+"-cron")))throw new IllegalStateException();
        }catch(Exception rejected){throw new IllegalStateException("STAGE1_RESOURCE_NOT_REGISTERED");}
    }
}
