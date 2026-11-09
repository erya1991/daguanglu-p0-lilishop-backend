package cn.lili.modules.community.p0.stage45;

import cn.lili.modules.community.p0.stage2.Stage2Settings;
import cn.lili.modules.community.p0.stage3.Stage3Gate;
import cn.lili.modules.community.p0.support.*;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import java.nio.file.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Component
@ConditionalOnProperty(name="p0.stage45.enabled",havingValue="true")
public class Stage45Gate {
    private volatile String processArtifactHash;
    private final Stage45Settings settings;
    private final Stage2Settings resources;
    private final Stage3Gate predecessor;
    public Stage45Gate(Stage45Settings settings,Stage2Settings resources,Stage3Gate predecessor) {
        this.settings=settings;this.resources=resources;this.predecessor=predecessor;
    }
    public void writes() { check("BUSINESS_WRITE",settings.isWritesEnabled()); }
    public void channel() { check("CHANNEL_WRITE",settings.isWritesEnabled()&&settings.isChannelWritesEnabled()); }
    private void check(String action,boolean enabled) {
        Stage1Exception.require(enabled,503,"STAGE45_WRITES_DISABLED");
        predecessor.requireWrites();
        try {
            Path run=Path.of(resources.getFileRoot()).toRealPath().getParent();
            Path file=Path.of(settings.getAuthorizationFile()).toRealPath();
            Stage1Exception.require(file.startsWith(run.resolve("private").toRealPath()),503,"STAGE45_AUTHORIZATION_REQUIRED");
            var proof=object(Files.readString(file));
            Path artifact=Path.of(System.getProperty("java.class.path")).toRealPath();
            Stage1Exception.require(artifact.startsWith(run.resolve("build").toRealPath()),503,"STAGE45_ARTIFACT_UNBOUND");
            String artifactHash=processArtifactHash;if(artifactHash==null){synchronized(this){if(processArtifactHash==null)processArtifactHash=digest(Files.readAllBytes(artifact));artifactHash=processArtifactHash;}}
            final String boundHash=artifactHash;
            Stage1Exception.require(proof.get("stage").getAsInt()==45 && "APPROVED".equals(proof.get("status").getAsString())
                && resources.getRunId().equals(proof.get("runId").getAsString())
                && resources.getMysqlUuid().equals(proof.get("mysqlUuid").getAsString())
                && resources.getMode().equals(proof.get("mode").getAsString())
                && proof.getAsJsonArray("artifactHashes").asList().stream().anyMatch(v->boundHash.equals(v.getAsString()))
                && proof.getAsJsonArray("actions").asList().stream().anyMatch(v->action.equals(v.getAsString())),503,"STAGE45_AUTHORIZATION_REQUIRED");
        } catch(Stage1Exception e) { throw e; }
        catch(Exception e) { throw new Stage1Exception(503,"STAGE45_AUTHORIZATION_REQUIRED"); }
    }
}
