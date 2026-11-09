package cn.lili.modules.community.p0.uat;
import cn.lili.modules.community.p0.stage2.Stage2Settings;
import cn.lili.modules.verification.entity.dos.VerificationSource;
import cn.lili.common.exception.ServiceException;
import org.springframework.stereotype.Service;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import lombok.RequiredArgsConstructor;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
@Service @RequiredArgsConstructor @ConditionalOnProperty(name="p0.uat.enabled",havingValue="true")
public class UatVerificationResources {
 private final Stage2Settings settings;
 public Path local(String resource){try{Path run=UatResourceScope.registered(settings),file;URI uri=URI.create(resource);if("file".equals(uri.getScheme()))file=Path.of(uri);else{if(uri.isAbsolute()&&(!"127.0.0.1".equals(uri.getHost())||!Set.of(19790,19792,19793,19794).contains(uri.getPort())))throw new IllegalArgumentException();String path=uri.getPath();if(!path.startsWith("/files/"))throw new IllegalArgumentException();file=run.resolve("files/public").resolve(path.substring(7));}file=file.toRealPath();if(!file.startsWith(run.resolve("private/captcha").toRealPath())&&!file.startsWith(run.resolve("files/public").toRealPath())||!Files.isRegularFile(file)||Files.size(file)>5*1024*1024)throw new IllegalArgumentException();return file;}catch(Exception e){throw new ServiceException("UAT_CAPTCHA_LOCAL_RESOURCE_REQUIRED");}}
 public VerificationSource writable(VerificationSource input,String id){if(input.getName()==null||input.getName().isBlank()||input.getName().length()>100||input.getType()==null||!Set.of("RESOURCE","SLIDER").contains(input.getType()))throw new ServiceException("CAPTCHA_RESOURCE_INVALID");var safe=new VerificationSource();safe.setId(id);safe.setName(input.getName().trim());safe.setType(input.getType());safe.setResource(local(input.getResource()).toUri().toString());return safe;}
 public void preview(VerificationSource row){try{byte[] bytes=Files.readAllBytes(local(row.getResource()));var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));if(image==null)throw new IllegalArgumentException();String mime=bytes.length>3&&bytes[0]==(byte)0x89?"image/png":"image/jpeg";row.setPreview("data:"+mime+";base64,"+Base64.getEncoder().encodeToString(bytes));}catch(Exception e){throw new ServiceException("UAT_CAPTCHA_RESOURCE_INVALID");}}
 public static void requireRemaining(List<VerificationSource> rows,List<String> removed,VerificationSource replacement){for(String type:List.of("RESOURCE","SLIDER")){boolean remains=rows.stream().anyMatch(r->type.equals(r.getType())&&!removed.contains(r.getId()))||(replacement!=null&&type.equals(replacement.getType()));if(!remains)throw new ServiceException("LAST_CAPTCHA_RESOURCE_REQUIRED");}}
}
