package cn.lili.common.security.session;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static cn.lili.common.security.session.Stage1PersistenceTest.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named="s1.deployed",matches="owned-run")
@Timeout(value=5,unit=java.util.concurrent.TimeUnit.MINUTES)
class Stage1DeployedFilesTest {
    static final List<Object> results=new ArrayList<>();
    @BeforeAll static void start()throws Exception{initialize();deployedHttp=true;}
    @AfterAll static void end()throws Exception{try{Files.writeString(run.resolve("evidence/deployed-files-"+testId+".json"),write(results));}finally{deployedHttp=false;finish();}}
    @Test void actualPdfWebpSizeAndOwnershipRulesThroughDeployedSecurityChain()throws Exception{
        var owner=ownerFixture();var foreign=ownerFixture();var applicant=login("files_"+req());
        byte[] webp=Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA"),pdf=Stage1FileCodecsTest.pdf("");
        var image=new java.awt.image.BufferedImage(2,2,java.awt.image.BufferedImage.TYPE_INT_RGB);var pngOut=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"png",pngOut);byte[] png=pngOut.toByteArray();var jpegOut=new ByteArrayOutputStream();javax.imageio.ImageIO.write(image,"jpeg",jpegOut);byte[] jpeg=jpegOut.toByteArray();
        String key=req();var first=upload(owner.login().session(),Stage1Routes.STORE,"GOODS",webp,key,200);assertEquals("image/webp",first.get("mimeType").getAsString());assertEquals(first,upload(owner.login().session(),Stage1Routes.STORE,"GOODS",webp,key,200));upload(owner.login().session(),Stage1Routes.STORE,"GOODS",png,key,409);
        upload(owner.login().session(),Stage1Routes.STORE,"GOODS",jpeg,req(),200);upload(owner.login().session(),Stage1Routes.STORE,"GOODS",Arrays.copyOf(png,5*1024*1024),req(),200);upload(owner.login().session(),Stage1Routes.STORE,"GOODS",Arrays.copyOf(png,5*1024*1024+1),req(),422);
        var privateFile=upload(applicant.session(),Stage1Routes.APPLICANT,"QUALIFICATION",pdf,req(),200);assertEquals("application/pdf",privateFile.get("mimeType").getAsString());
        upload(applicant.session(),Stage1Routes.APPLICANT,"QUALIFICATION",Arrays.copyOf(png,10*1024*1024),req(),200);upload(applicant.session(),Stage1Routes.APPLICANT,"QUALIFICATION",Arrays.copyOf(png,10*1024*1024+1),req(),422);
        for(byte[] bad:List.of("<svg onload='PII_SECRET_TEST'/>".getBytes(StandardCharsets.US_ASCII),"MZ fake executable".getBytes(StandardCharsets.US_ASCII),Stage1FileCodecsTest.pdf("/OpenAction << /S /JavaScript /JS (PII_SECRET_TEST) >>")))upload(applicant.session(),Stage1Routes.APPLICANT,"QUALIFICATION",bad,req(),422);
        upload(owner.login().session(),Stage1Routes.STORE,"GOODS",pdf,req(),422);upload(applicant.session(),Stage1Routes.APPLICANT,"QUALIFICATION",webp,req(),422);
        String id=privateFile.get("id").getAsString();var permitted=download(applicant.session(),Stage1Routes.APPLICANT,id,200);assertArrayEquals(pdf,permitted.body());assertEquals("no-store",permitted.headers().firstValue("cache-control").orElseThrow());assertEquals("nosniff",permitted.headers().firstValue("x-content-type-options").orElseThrow());
        download(foreign.login().session(),Stage1Routes.STORE,id,404);download(operator,Stage1Routes.MANAGER,id,404);download(channel,Stage1Routes.MANAGER,id,404);download(reviewer,Stage1Routes.MANAGER,id,404);download(null,Stage1Routes.APPLICANT,id,403);
        var application=call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(Stage1Routes.APPLICANT+"/application"),applicant.session(),map("requestId",req(),"payload",payload(id)),200);String appId=application.get("id").getAsString();call(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(Stage1Routes.APPLICANT+"/application/"+appId+"/submit"),applicant.session(),map("requestId",req(),"expectedVersion",0),200);download(reviewer,Stage1Routes.MANAGER,id,200);download(operator,Stage1Routes.MANAGER,id,404);
        assertEquals(1,db.count("SELECT COUNT(*) FROM li_p0_operation WHERE request_id=?",key));
    }
    static JsonObject upload(SessionTokens session,String base,String purpose,byte[] bytes,String key,int status)throws Exception{
        String boundary="S1BOUNDARY"+req();var out=new ByteArrayOutputStream();out.writeBytes(("--"+boundary+"\r\nContent-Disposition: form-data; name=\"metadata\"\r\n\r\n"+write(map("requestId",key,"purpose",purpose))+"\r\n--"+boundary+"\r\nContent-Disposition: form-data; name=\"file\"; filename=\"synthetic.jpg\"\r\nContent-Type: image/jpeg\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.writeBytes(bytes);out.writeBytes(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.US_ASCII));
        var response=liveClient.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:19080"+base+"/files")).timeout(java.time.Duration.ofSeconds(30)).header("accessToken",session.accessToken()).header("Content-Type","multipart/form-data; boundary="+boundary).POST(HttpRequest.BodyPublishers.ofByteArray(out.toByteArray())).build(),HttpResponse.BodyHandlers.ofString());var body=object(response.body());results.add(map("case","upload","purpose",purpose,"bytes",bytes.length,"expected",status,"actual",response.statusCode(),"code",body.get("code")));assertEquals(status,response.statusCode(),purpose+" bytes="+bytes.length+" code="+body.get("code"));assertFalse(response.body().contains("PII_SECRET_TEST"));return status==200?body.getAsJsonObject("result"):body;
    }
    static HttpResponse<byte[]> download(SessionTokens session,String base,String id,int expected)throws Exception{var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:19080"+base+"/files/"+id+"/preview")).timeout(java.time.Duration.ofSeconds(10)).GET();if(session!=null)request.header("accessToken",session.accessToken());var response=liveClient.send(request.build(),HttpResponse.BodyHandlers.ofByteArray());assertEquals(expected,response.statusCode());results.add(map("case","private-preview","domain",base,"expected",expected,"actual",response.statusCode()));return response;}
}
