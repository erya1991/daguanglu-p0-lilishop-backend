package cn.lili.modules.community.p0.stage3;

import cn.lili.modules.community.p0.stage2.Stage2Settings;
import cn.lili.modules.community.p0.support.*;
import org.springframework.core.env.Environment;
import java.nio.file.*;
import java.util.Set;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

public final class Stage3ResourceScope {
    private Stage3ResourceScope(){}
    public static void verify(Stage1Db db,Stage2Settings s,Environment env)throws Exception{
        if(!s.isEnabled()||env.getProperty("p0.stage1.enabled",Boolean.class,false)||!"dgl_p0_s3".equals(s.getDatabase())||!env.getProperty("p0.stage3.enabled",Boolean.class,false)||!s.isStage3ResourceMode()||!"TEST".equals(s.getEnvironment())||!"MOCK".equals(s.getMode()))throw new IllegalStateException();
        var identity=db.required("SELECT @@server_uuid AS uuid,DATABASE() AS database_name,CURRENT_USER() AS account");
        if(s.getMysqlUuid()==null||!s.getMysqlUuid().equals(id(identity,"uuid"))||!"dgl_p0_s3".equals(id(identity,"database_name"))||!id(identity,"account").startsWith("s3_app@"))throw new IllegalStateException();
        if(Set.of("bffc3648-d139-11f1-afc9-9617b0124629","043ca7b1-d2d6-11f1-889b-7af81f86d1ff","8d54bca3-ccfc-11f1-8a9b-1e861828a1bc").contains(s.getMysqlUuid()))throw new IllegalStateException();
        Path run=Path.of(s.getFileRoot()).toRealPath().getParent();var owner=object(Files.readString(run.resolve("owner.json")));
        if(!run.getParent().getFileName().toString().equals(".stage3-runtime")||!run.getFileName().toString().matches("S3_[0-9]{8}_[0-9]{6}")
            ||!Path.of(s.getFileRoot()).getFileName().toString().equals("files")||!Path.of(owner.get("workspace").getAsString()).toRealPath().resolve(".stage3-runtime").equals(run.getParent())||owner.get("stage").getAsInt()!=3||!s.getRunId().equals(owner.get("runId").getAsString()))throw new IllegalStateException();
        if(!"127.0.0.1".equals(env.getProperty("spring.data.redis.host"))||!"19579".equals(env.getProperty("spring.data.redis.port")))throw new IllegalStateException();
        for(String key:Set.of("lili.message.consumer.enabled","lili.promotion.delay-listener.enabled","lili.mqtt.enabled"))if(!"false".equals(env.getProperty(key)))throw new IllegalStateException();
        for(String frequency:Set.of("minute","hour","day"))if(!"-".equals(env.getProperty("lili.schedule.every-"+frequency+"-cron")))throw new IllegalStateException();
        if(db.count("SELECT COUNT(*) FROM li_p0_schema_history WHERE migration IN ('S3_001_fulfillment.sql','S3_002_verification.sql','S3_003_messages.sql','S3_004_access_captcha.sql') AND status='DONE'")!=4)throw new IllegalStateException();
    }
}
