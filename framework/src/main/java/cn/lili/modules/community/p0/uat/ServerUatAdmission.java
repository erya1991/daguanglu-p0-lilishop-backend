package cn.lili.modules.community.p0.uat;

import cn.lili.modules.community.p0.stage2.Stage2Settings;
import cn.lili.modules.community.p0.support.Stage1Exception;
import org.w3c.dom.Element;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Automated admission for a separately registered company TEST/MOCK deployment. */
public final class ServerUatAdmission {
    private ServerUatAdmission() { }
    public static final Set<String> REQUIRED_SUITES=Set.of(
        "cn.lili.modules.community.p0.stage2.Stage2PaymentFactsTest",
        "cn.lili.modules.community.p0.stage2.Stage2RecoveryScopeTest",
        "cn.lili.modules.community.p0.stage2.Stage2ServerRecoveryScopeTest",
        "cn.lili.modules.community.p0.stage3.Stage3EligibilityTest",
        "cn.lili.modules.community.p0.stage3.Stage3CryptoTest",
        "cn.lili.modules.community.p0.stage45.Stage45WechatFundsTest",
        "cn.lili.modules.community.p0.uat.UatResourceScopeServerTest",
        "cn.lili.modules.connect.wechat.ResidentWechatLoginTest",
        "cn.lili.modules.community.p0.uat.ServerUatAdmissionTest");

    public static void verify(Stage2Settings settings, Path artifact, String artifactHash) {
        try {
            if(!settings.isServerUatMode())throw new IllegalArgumentException();
            Path run=UatResourceScope.registered(settings);
            if(!artifact.toRealPath().startsWith(run.resolve("build").toRealPath()) ||
               !artifact.getFileName().toString().endsWith(".jar"))throw new IllegalArgumentException();
            Path file=run.resolve("private/server-admission.json");
            if(Files.isSymbolicLink(file)||!file.toRealPath().getParent().equals(run.resolve("private").toRealPath()))throw new IllegalArgumentException();
            var proof=object(Files.readString(file));
            if(proof.get("format").getAsInt()!=1 || !"WECHAT_TEST_MOCK".equals(proof.get("purpose").getAsString()) ||
               !settings.getRunId().equals(proof.get("runId").getAsString()) ||
               !settings.getMysqlUuid().equals(proof.get("mysqlUuid").getAsString()) ||
               !"S3_FIXED_128_AESGCM_HMAC_120_V1".equals(proof.get("credentialPolicy").getAsString()) ||
               !proof.get("sourceFingerprint").getAsString().matches("[a-f0-9]{64}") ||
               proof.getAsJsonArray("artifactHashes").asList().stream().noneMatch(v->artifactHash.equals(v.getAsString())))
                throw new IllegalArgumentException();
            var seen=new HashSet<String>();
            for(var raw:proof.getAsJsonArray("suites")) {
                var entry=raw.getAsJsonObject();String name=entry.get("name").getAsString();
                if(!REQUIRED_SUITES.contains(name)||!seen.add(name))throw new IllegalArgumentException();
                Path report=run.resolve(entry.get("path").getAsString()).normalize().toRealPath();
                if(!report.startsWith(run.resolve("evidence").toRealPath())||Files.size(report)>2_000_000)throw new IllegalArgumentException();
                byte[] bytes=Files.readAllBytes(report);
                if(!digest(bytes).equals(entry.get("sha256").getAsString()))throw new IllegalArgumentException();
                requirePassingSuite(bytes,name);
            }
            if(!seen.equals(REQUIRED_SUITES))throw new IllegalArgumentException();
        } catch(Exception invalid) {throw new Stage1Exception(503,"SERVER_UAT_AUTOMATED_ADMISSION_REQUIRED");}
    }
    private static void requirePassingSuite(byte[] bytes,String name)throws Exception {
        var factory=DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities",false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
        factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        var builder=factory.newDocumentBuilder();
        builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){
            @Override public void error(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}
            @Override public void fatalError(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}
        });
        Element suite=builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
        if(!"testsuite".equals(suite.getTagName())||!name.equals(suite.getAttribute("name"))||
           Integer.parseInt(suite.getAttribute("tests"))<=0)throw new IllegalArgumentException();
        for(String field:List.of("failures","errors","skipped"))
            if(Integer.parseInt(suite.getAttribute(field))!=0||suite.getElementsByTagName(field.equals("failures")?"failure":field.equals("errors")?"error":"skipped").getLength()!=0)
                throw new IllegalArgumentException();
        if(suite.getElementsByTagName("testcase").getLength()!=Integer.parseInt(suite.getAttribute("tests")))throw new IllegalArgumentException();
    }
}
