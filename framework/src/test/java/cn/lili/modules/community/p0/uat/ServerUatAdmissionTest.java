package cn.lili.modules.community.p0.uat;

import cn.lili.modules.community.p0.stage2.Stage2Settings;
import cn.lili.modules.community.p0.support.Stage1Exception;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;

class ServerUatAdmissionTest {
    @TempDir Path root;
    Stage2Settings settings;Path run,artifact;Map<String,Object> proof;
    List<Map<String,Object>> suites;
    @BeforeEach void fixture()throws Exception {
        String id="UAT_20261113000000000";run=root.resolve(".uat-runtime").resolve(id);
        for(String dir:List.of("files","runtime","private","evidence","build"))Files.createDirectories(run.resolve(dir));
        Files.writeString(run.resolve("owner.json"),write(map("integration",true,"runId",id,"mode","MOCK","environment","TEST","workspace",root.toString(),"purpose","WECHAT_TEST")));
        Files.writeString(run.resolve("runtime/resources.json"),write(map("runId",id,"mysqlUuid","test-uuid","mysqlPort",19706,"redisPort",19779)));
        settings=new Stage2Settings();settings.setEnabled(true);settings.setUatResourceMode(true);settings.setServerUatMode(true);
        settings.setServerUatRoot(root.toString());settings.setRunId(id);settings.setDatabase("dgl_p0_uat");settings.setMysqlUuid("test-uuid");settings.setFileRoot(run.resolve("files").toString());
        artifact=run.resolve("build/manager.jar");Files.writeString(artifact,"unit artifact");
        suites=new ArrayList<>();
        for(String name:ServerUatAdmission.REQUIRED_SUITES) {
            Path report=run.resolve("evidence/"+name+".xml");
            Files.writeString(report,"<testsuite name=\""+name+"\" tests=\"1\" failures=\"0\" errors=\"0\" skipped=\"0\"><testcase name=\"check\"/></testsuite>");
            suites.add(map("name",name,"path",run.relativize(report).toString(),"sha256",digest(Files.readAllBytes(report))));
        }
        proof=map("format",1,"purpose","WECHAT_TEST_MOCK","runId",id,"mysqlUuid","test-uuid","credentialPolicy","S3_FIXED_128_AESGCM_HMAC_120_V1","sourceFingerprint","a".repeat(64),"artifactHashes",List.of(digest(Files.readAllBytes(artifact))),"suites",suites);
        save();
    }
    void save()throws Exception {Files.writeString(run.resolve("private/server-admission.json"),write(proof));}
    void verify()throws Exception {ServerUatAdmission.verify(settings,artifact,digest(Files.readAllBytes(artifact)));}
    @Test void exactRegisteredBuildAndPassingReportsAreRequired()throws Exception {
        assertDoesNotThrow(this::verify);
        Files.writeString(artifact,"different");assertThrows(Stage1Exception.class,this::verify);
        settings.setMode("REAL");assertThrows(Stage1Exception.class,this::verify);
    }
    @Test void reportTamperingMissingOrDuplicateSuiteCannotAdmit()throws Exception {
        var first=suites.getFirst();Path file=run.resolve((String)first.get("path"));
        Files.writeString(file,Files.readString(file).replace("tests=\"1\"","tests=\"2\""));
        assertThrows(Stage1Exception.class,this::verify);
        first.put("sha256",digest(Files.readAllBytes(file)));save();
        assertThrows(Stage1Exception.class,this::verify);
        suites.set(1,first);save();assertThrows(Stage1Exception.class,this::verify);
    }
    @Test void skippedFailedAndExternalEntityReportsAreRejected()throws Exception {
        var first=suites.getFirst();Path file=run.resolve((String)first.get("path"));String original=Files.readString(file);
        for(String xml:List.of(original.replace("failures=\"0\"","failures=\"1\""),original.replace("skipped=\"0\"","skipped=\"1\""),
            "<!DOCTYPE testsuite [<!ENTITY private SYSTEM \"file:///must-not-read\">]>"+original)) {
            Files.writeString(file,xml);first.put("sha256",digest(Files.readAllBytes(file)));save();
            assertThrows(Stage1Exception.class,this::verify);
        }
    }
    @Test void foreignRunAndArtifactCannotReuseAdmission()throws Exception {
        proof.put("runId","UAT_20261114000000000");save();assertThrows(Stage1Exception.class,this::verify);
        settings.setServerUatMode(false);assertThrows(Stage1Exception.class,this::verify);
    }
}
