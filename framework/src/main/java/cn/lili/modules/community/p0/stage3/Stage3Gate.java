package cn.lili.modules.community.p0.stage3;

import cn.lili.modules.community.p0.stage2.Stage2Settings;
import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.nio.file.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Admission binds evidence to this candidate artifact and owned resource. Never a permanent paid permit. */
@Component @ConditionalOnProperty(name="p0.stage3.enabled",havingValue="true")
public class Stage3Gate {
    private volatile String processArtifactHash;
    private final Stage3Settings settings;private final Stage2Settings resources;
    public Stage3Gate(Stage3Settings settings,Stage2Settings resources){this.settings=settings;this.resources=resources;}
    public void requireWrites(){
        Stage1Exception.require(settings.isWritesEnabled(),503,"STAGE3_WRITES_DISABLED");
        try {
            Path run=Path.of(resources.getFileRoot()).toRealPath().getParent();
            if(resources.isServerUatMode()) {
                Path artifact=Path.of(System.getProperty("java.class.path")).toRealPath();
                if(processArtifactHash==null) synchronized(this) {if(processArtifactHash==null)processArtifactHash=digest(Files.readAllBytes(artifact));}
                cn.lili.modules.community.p0.uat.ServerUatAdmission.verify(resources,artifact,processArtifactHash);
                return;
            }
            Path file=privateFile(run,settings.getAdmissionFile());var proof=object(Files.readString(file));
            Stage1Exception.require(proof.get("stage").getAsInt()==3&&"APPROVED".equals(proof.get("status").getAsString())
                &&resources.getRunId().equals(proof.get("runId").getAsString())&&resources.getMysqlUuid().equals(proof.get("mysqlUuid").getAsString()),503,"SC23_NOT_ADMITTED");
            Path artifact=Path.of(System.getProperty("java.class.path")).toRealPath();
            Stage1Exception.require(artifact.startsWith(run.resolve("build"))&&artifact.toString().endsWith(".jar"),503,"SC23_NOT_ADMITTED");
            String current=processArtifactHash;if(current==null){synchronized(this){if(processArtifactHash==null)processArtifactHash=digest(Files.readAllBytes(artifact));current=processArtifactHash;}}
            Stage1Exception.require(proof.getAsJsonArray("artifactHashes").asList().stream().anyMatch(v->processArtifactHash.equals(v.getAsString())),503,"SC23_NOT_ADMITTED");
            Set<String> checks=new HashSet<>();proof.getAsJsonArray("checks").forEach(v->checks.add(v.getAsString()));
            Stage1Exception.require(checks.containsAll(Set.of("COMPLETE_PAID","UNPAID","UNKNOWN_CLOSING","INCOMPLETE_PAID","PAID_LOCAL_PENDING","LATE_AFTER_RELEASE")),503,"SC23_NOT_ADMITTED");
            Stage1Exception.require(!proof.getAsJsonArray("evidence").isEmpty(),503,"SC23_NOT_ADMITTED");
            for(var raw:proof.getAsJsonArray("evidence")){
                var e=raw.getAsJsonObject();Path evidence=run.resolve(e.get("path").getAsString()).normalize().toRealPath();
                Stage1Exception.require(evidence.startsWith(run.resolve("evidence").toRealPath())&&digest(Files.readAllBytes(evidence)).equals(e.get("sha256").getAsString()),503,"SC23_NOT_ADMITTED");
            }
        }catch(Stage1Exception e){throw e;}catch(Exception e){throw new Stage1Exception(503,"SC23_NOT_ADMITTED");}
    }
    public void requireCredentials(){
        requireWrites();Stage1Exception.require(settings.isCredentialsEnabled(),503,"CREDENTIAL_SECURITY_PENDING");
        if(resources.isServerUatMode())return;
        try{Path run=Path.of(resources.getFileRoot()).toRealPath().getParent();var proof=object(Files.readString(privateFile(run,settings.getSecurityApprovalFile())));
            Stage1Exception.require("APPROVED".equals(proof.get("status").getAsString())&&resources.getRunId().equals(proof.get("runId").getAsString())
                &&"S3_FIXED_128_AESGCM_HMAC_120_V1".equals(proof.get("policy").getAsString())&&proof.getAsJsonArray("artifactHashes").asList().stream().anyMatch(v->processArtifactHash.equals(v.getAsString())),503,"CREDENTIAL_SECURITY_PENDING");
        }catch(Stage1Exception e){throw e;}catch(Exception e){throw new Stage1Exception(503,"CREDENTIAL_SECURITY_PENDING");}
    }
    public Stage3Crypto crypto(){
        try{Path run=Path.of(resources.getFileRoot()).toRealPath().getParent();return new Stage3Crypto(Base64.getDecoder().decode(Files.readString(privateFile(run,settings.getKeyFile())).trim()));}
        catch(Exception e){throw new Stage1Exception(503,"CREDENTIAL_SECURITY_UNAVAILABLE");}
    }
    private static Path privateFile(Path run,String name)throws Exception{
        Path file=Path.of(Objects.requireNonNull(name)).toRealPath();if(!file.startsWith(run.resolve("private").toRealPath()))throw new IllegalArgumentException();return file;
    }
    public String writeBlock(){try{requireWrites();return null;}catch(Stage1Exception e){return e.code();}}
}
