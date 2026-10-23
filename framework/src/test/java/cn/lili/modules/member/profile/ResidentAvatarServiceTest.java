package cn.lili.modules.member.profile;

import cn.lili.common.security.session.*;
import cn.lili.common.utils.SnowFlake;
import cn.lili.modules.connect.wechat.WechatIdentityService;
import cn.lili.modules.file.entity.File;
import cn.lili.modules.file.plugin.*;
import cn.lili.modules.file.service.FileService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ResidentAvatarServiceTest {
    static Object oldSnowflake;
    @BeforeAll static void ids(){oldSnowflake=org.springframework.test.util.ReflectionTestUtils.getField(SnowFlake.class,"snowflake");SnowFlake.initialize(31,30);}
    @AfterAll static void restore(){org.springframework.test.util.ReflectionTestUtils.setField(SnowFlake.class,"snowflake",oldSnowflake);}
    final FileService files=mock(FileService.class);final FilePluginFactory factory=mock(FilePluginFactory.class);final FilePlugin plugin=mock(FilePlugin.class);
    final ResidentProfileProperties props=new ResidentProfileProperties();final WechatIdentityService identities=mock(WechatIdentityService.class);
    final ResidentAvatarService service=new ResidentAvatarService(files,factory,props,identities);
    final SessionIdentity a=new SessionIdentity("123",SessionDomain.RESIDENT,OfflineResidentStore.APP,"789",1L);
    final Map<String,File> saved=new HashMap<>();
    ResidentAvatarServiceTest(){props.setSchemaReady(true);props.setUploadEnabled(true);when(factory.filePlugin()).thenReturn(plugin);
        when(plugin.inputStreamUpload(any(),anyString(),anyString())).thenReturn("https://synthetic.invalid/avatar.png");
        when(files.save(any())).thenAnswer(i->{File file=i.getArgument(0);saved.put(file.getId(),file);return true;});
        when(files.getById(anyString())).thenAnswer(i->saved.get(i.getArgument(0)));}
    static byte[] image(String format)throws Exception {var image=new BufferedImage(4,4,BufferedImage.TYPE_INT_RGB);var out=new ByteArrayOutputStream();ImageIO.write(image,format,out);return out.toByteArray();}
    @ParameterizedTest @ValueSource(strings={"png","jpeg"})
    void actualTypeReencodedAndOwnershipRecorded(String format)throws Exception{
        var bytes=image(format);var upload=new MockMultipartFile("file","private-original."+format,"image/"+format,bytes);
        var result=service.upload(a,upload);var file=saved.get(result.fileId());assertEquals("123",file.getOwnerId());assertEquals("MEMBER",file.getUserEnums());assertEquals("avatar."+format,file.getName());
        assertTrue(file.getFileKey().startsWith("MEMBER/123/stage0-avatar/"));assertEquals(result.url(),service.ownedUrl("123",result.fileId()));
        assertThrows(ResidentProfileException.class,()->service.ownedUrl("456",result.fileId()));
    }
    @Test void fakeTypeOversizeAndBadBytesRejectBeforeStorage()throws Exception{
        for(var file:List.of(new MockMultipartFile("file","x.png","image/png","<svg/>".getBytes()),new MockMultipartFile("file","x.png","image/jpeg",image("png")),new MockMultipartFile("file","x.png","image/png",new byte[2*1024*1024+1])))assertThrows(ResidentProfileException.class,()->service.upload(a,file));
        verifyNoInteractions(factory,files);
    }
    @ParameterizedTest @ValueSource(strings={"owner","domain","deleted","category","type","key","secret-url"})
    void editRevalidatesFileNotJustClientFileId(String fault)throws Exception{
        var result=service.upload(a,new MockMultipartFile("file","x.png","image/png",image("png")));var f=saved.get(result.fileId());
        switch(fault){case "owner"->f.setOwnerId("456");case "domain"->f.setUserEnums("STORE");case "deleted"->f.setDeleteFlag(true);case "category"->f.setFileCategory("FILE");case "type"->f.setFileType("image/svg+xml");case "key"->f.setFileKey("legacy/unverified.png");default->f.setUrl("https://synthetic.invalid/image?secret=synthetic");}
        assertThrows(ResidentProfileException.class,()->service.ownedUrl("123",result.fileId()));
    }
    @Test void unavailableStorageDoesNotCallPlugin()throws Exception{props.setUploadEnabled(false);assertThrows(ResidentProfileException.class,()->service.upload(a,new MockMultipartFile("file","x.png","image/png",image("png"))));verifyNoInteractions(factory,files);}
    @Test void databaseSaveFailureNeverReturnsFileId()throws Exception{doReturn(false).when(files).save(any());assertThrows(ResidentProfileException.class,()->service.upload(a,new MockMultipartFile("file","x.png","image/png",image("png"))));}
}
