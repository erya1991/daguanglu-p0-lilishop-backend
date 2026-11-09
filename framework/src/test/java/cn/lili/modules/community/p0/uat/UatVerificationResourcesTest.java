package cn.lili.modules.community.p0.uat;
import cn.lili.modules.community.p0.stage2.Stage2Settings;
import cn.lili.modules.verification.entity.dos.VerificationSource;
import cn.lili.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class UatVerificationResourcesTest {
 @TempDir Path root;
 private VerificationSource row(String id,String type){var r=new VerificationSource();r.setId(id);r.setType(type);r.setName("test");return r;}
 @Test void lastImageOrSliderCannotBeRemovedOrConverted(){var rows=List.of(row("1","RESOURCE"),row("2","SLIDER"));assertThrows(ServiceException.class,()->UatVerificationResources.requireRemaining(rows,List.of("1"),null));assertThrows(ServiceException.class,()->UatVerificationResources.requireRemaining(rows,List.of("2"),row("2","RESOURCE")));assertDoesNotThrow(()->UatVerificationResources.requireRemaining(rows,List.of("1"),row("1","RESOURCE")));}
 @Test void extraOwnedResourceCanBeRemoved(){assertDoesNotThrow(()->UatVerificationResources.requireRemaining(List.of(row("1","RESOURCE"),row("2","SLIDER"),row("3","RESOURCE")),List.of("3"),null));}
 @Test void previewAndWritesRemainInsideRegisteredRun()throws Exception{Files.createDirectories(root.resolve("private/captcha"));Files.createDirectories(root.resolve("files/public"));Path image=root.resolve("private/captcha/base.png");javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(4,4,java.awt.image.BufferedImage.TYPE_INT_RGB),"png",image.toFile());try(var scope=mockStatic(UatResourceScope.class)){var settings=new Stage2Settings();scope.when(()->UatResourceScope.registered(settings)).thenReturn(root.toRealPath());var service=new UatVerificationResources(settings);var input=row("injected","RESOURCE");input.setResource(image.toUri().toString());var safe=service.writable(input,null);assertNull(safe.getId());service.preview(safe);assertTrue(safe.getPreview().startsWith("data:image/png;base64,"));assertThrows(ServiceException.class,()->service.local("https://example.invalid/x.png"));Path outside=root.resolve("private/secret.png");Files.writeString(outside,"private");assertThrows(ServiceException.class,()->service.local(outside.toUri().toString()));}}
}
