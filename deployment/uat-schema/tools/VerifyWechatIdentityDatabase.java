import cn.lili.common.utils.SnowFlake;
import cn.lili.common.security.session.*;
import cn.lili.modules.community.p0.stage2.Stage2Settings;
import cn.lili.modules.community.p0.support.*;
import cn.lili.modules.community.p0.uat.*;
import cn.lili.modules.connect.mapper.WechatIdentityMapper;
import cn.lili.modules.connect.wechat.*;
import cn.lili.modules.member.mapper.MemberMapper;
import cn.lili.mybatis.mybatisplus.MyMetaObjectHandler;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.config.GlobalConfig;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.type.JdbcType;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/**
 * Disposable-owned-database verification only. Calls the real identity, mapper,
 * transaction and enrollment services with explicitly synthetic gateway results.
 * This is not a real WeChat, browser or device acceptance result.
 */
class VerifyWechatIdentityDatabase {
    static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Expected test root and run ID");
        Path root=Path.of(args[0]).toRealPath(),run=root.resolve(".uat-runtime").resolve(args[1]).toRealPath();
        var owner=object(Files.readString(run.resolve("owner.json")));
        check(owner.has("automatedTest")&&owner.get("automatedTest").getAsBoolean(),"Disposable automated test owner flag required");
        var resources=object(Files.readString(run.resolve("runtime/resources.json")));
        var secret=object(Files.readString(run.resolve("private/db.json")));
        var settings=new Stage2Settings();settings.setEnabled(true);settings.setUatResourceMode(true);settings.setServerUatMode(true);
        settings.setServerUatRoot(root.toString());settings.setServerMysqlPort(resources.get("mysqlPort").getAsInt());settings.setServerRedisPort(resources.get("redisPort").getAsInt());
        settings.setRunId(args[1]);settings.setMysqlUuid(resources.get("mysqlUuid").getAsString());settings.setFileRoot(run.resolve("files").toString());settings.setDatabase("dgl_p0_uat");
        UatResourceScope.registered(settings);
        var source=new DriverManagerDataSource("jdbc:mysql://127.0.0.1:"+settings.uatMysqlPort()+"/dgl_p0_uat?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC","uat_app",secret.get("app").getAsString());
        var db=new Stage1Db(new JdbcTemplate(source));
        check(settings.getMysqlUuid().equals(id(db.required("SELECT @@server_uuid uuid"),"uuid")),"Actual database UUID mismatch");
        check(db.count("SELECT COUNT(*) FROM li_p0_schema_history WHERE status='DONE'")>=47,"Current business schema required");
        var config=new MybatisConfiguration();config.setMapUnderscoreToCamelCase(true);config.setJdbcTypeForNull(JdbcType.NULL);config.setLogImpl(NoLoggingImpl.class);
        var global=new GlobalConfig();global.setMetaObjectHandler(new MyMetaObjectHandler());
        var factory=new MybatisSqlSessionFactoryBean();factory.setDataSource(source);factory.setConfiguration(config);factory.setGlobalConfig(global);
        var sf=factory.getObject();sf.getConfiguration().addMapper(MemberMapper.class);sf.getConfiguration().addMapper(WechatIdentityMapper.class);
        var sql=new SqlSessionTemplate(sf);var resident=new ResidentWechatProperties();resident.setSchemaReady(true);
        var merchant=new MerchantWechatProperties();merchant.setSchemaReady(true);
        var identities=new WechatIdentityService(sql.getMapper(WechatIdentityMapper.class),sql.getMapper(MemberMapper.class),resident,merchant,new DataSourceTransactionManager(source));
        ReflectionTestUtils.setField(identities,"testEnrollment",new ServerWechatResidentEnrollment(settings,db));
        SnowFlake.initialize(28,29);
        String app="wx0000000000000001",open="DB_TEST_"+UUID.randomUUID().toString().replace("-","");
        var first=identities.resolve(new WechatCodeGateway.Identity(app,open,null));identities.verify(first);
        check(first.domain()==SessionDomain.RESIDENT,"Resident identity required");
        check(db.count("SELECT COUNT(*) FROM li_p0_s2_member_scope WHERE member_id=? AND run_id=? AND permission='TRANSACT'",first.subject(),args[1])==1,"New resident test scope missing");
        check(db.count("SELECT COUNT(*) FROM li_member m JOIN li_wechat_identity i ON i.member_id=m.id WHERE m.id=? AND i.app_id=? AND i.binding_state='ACTIVE'",first.subject(),app)==1,"Backend resident association missing");
        var again=identities.resolve(new WechatCodeGateway.Identity(app,open,null));
        check(first.equals(again),"Repeated login changed stable identity");
        db.update("UPDATE li_p0_s2_member_scope SET permission='REVOKED' WHERE member_id=?",first.subject());
        identities.resolve(new WechatCodeGateway.Identity(app,open,null));
        check(db.count("SELECT COUNT(*) FROM li_p0_s2_member_scope WHERE member_id=? AND permission='REVOKED'",first.subject())==1,"Repeated login silently restored revoked scope");
        var seller=identities.resolve(new WechatCodeGateway.Identity("wx0000000000000002","DB_TEST_"+UUID.randomUUID().toString().replace("-",""),null),SessionDomain.APPLICANT);
        check(seller.domain()==SessionDomain.APPLICANT&&db.count("SELECT COUNT(*) FROM li_p0_s2_member_scope WHERE member_id=?",seller.subject())==0,"Merchant received resident transaction scope");
        db.update("UPDATE li_member SET disabled=0 WHERE id=?",first.subject());
        boolean rejected=false;try{identities.verify(first);}catch(ResidentLoginException expected){rejected=true;}
        check(rejected,"Disabled member was accepted");
        long members=db.count("SELECT COUNT(*) FROM li_member"),bindings=db.count("SELECT COUNT(*) FROM li_wechat_identity");
        var failedDb=new Stage1Db(new JdbcTemplate(source)){
            @Override public int update(String statement,Object...values){if(statement.startsWith("INSERT INTO li_p0_s2_member_scope"))throw new IllegalStateException("test rollback");return super.update(statement,values);}
        };
        ReflectionTestUtils.setField(identities,"testEnrollment",new ServerWechatResidentEnrollment(settings,failedDb));
        rejected=false;try{identities.resolve(new WechatCodeGateway.Identity(app,"DB_FAIL_"+UUID.randomUUID().toString().replace("-",""),null));}catch(ResidentLoginException expected){rejected=true;}
        check(rejected&&db.count("SELECT COUNT(*) FROM li_member")==members&&db.count("SELECT COUNT(*) FROM li_wechat_identity")==bindings,"Enrollment failure left partial identity data");
        Files.writeString(run.resolve("evidence/wechat-identity-database.json"),write(map("runId",args[1],"mysqlUuid",settings.getMysqlUuid(),"checks",6,"status","PASS_REAL_DB_WITH_SYNTHETIC_GATEWAY_IDENTITIES","realWechat",false,"browser",false,"device",false)),StandardOpenOption.CREATE_NEW);
        System.out.println("WECHAT_IDENTITY_DATABASE_VERIFIED checks=6 syntheticGateway=true");
    }
}
