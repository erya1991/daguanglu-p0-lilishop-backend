package cn.lili.modules.member.profile;

import cn.lili.common.security.session.*;
import cn.lili.common.utils.SnowFlake;
import cn.lili.modules.connect.wechat.WechatIdentityService;
import cn.lili.modules.file.entity.File;
import cn.lili.modules.file.plugin.FilePluginFactory;
import cn.lili.modules.file.service.FileService;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.*;
import java.net.URI;
import java.util.*;
import static cn.lili.modules.member.profile.ResidentProfileException.Code.*;

@Service
public class ResidentAvatarService {
    private static final int LIMIT=2*1024*1024;
    private final FileService files;
    private final FilePluginFactory plugins;
    private final ResidentProfileProperties properties;
    private final WechatIdentityService identities;
    public ResidentAvatarService(FileService files,FilePluginFactory plugins,ResidentProfileProperties properties,WechatIdentityService identities) {
        this.files=files; this.plugins=plugins; this.properties=properties; this.identities=identities;
    }
    public Avatar upload(SessionIdentity identity,MultipartFile upload) {
        properties.requireSchema();
        if(identity==null || identity.domain()!=SessionDomain.RESIDENT) throw new ResidentProfileException(SESSION_REQUIRED);
        try {
            identities.verify(identity);
            Image image=decode(upload);
            if(!properties.isUploadEnabled()) throw new ResidentProfileException(STORAGE_PENDING);
            String key=prefix(identity.subject())+UUID.randomUUID()+"."+image.format;
            String url=plugins.filePlugin().inputStreamUpload(new ByteArrayInputStream(image.bytes),key,"image/"+image.format);
            if(!safeUrl(url)) throw new ResidentProfileException(AVATAR_REJECTED);
            File file=new File(); file.setId(SnowFlake.getIdStr()); file.setFileKey(key); file.setName("avatar."+image.format);
            file.setFileSize((long)image.bytes.length); file.setFileType("image/"+image.format); file.setFileCategory("IMAGE");
            file.setOwnerId(identity.subject()); file.setUserEnums("MEMBER"); file.setUrl(url); file.setDeleteFlag(false);
            // Explicit non-secret audit values; do not persist original filename or request-provided owner.
            file.setCreateBy("stage0-avatar"); file.setCreateTime(new Date()); file.setUpdateTime(new Date());
            if(!files.save(file)) throw new ResidentProfileException(OPERATION_UNAVAILABLE);
            return new Avatar(file.getId(),url);
        } catch(ResidentProfileException e) {throw e;}
        catch(Exception e) {throw new ResidentProfileException(OPERATION_UNAVAILABLE);}
    }
    public String ownedUrl(String owner,String fileId) {
        if(fileId==null || !fileId.matches("[0-9]{1,19}")) throw new ResidentProfileException(AVATAR_REJECTED);
        File file=files.getById(fileId);
        if(file==null || !fileId.equals(file.getId()) || !owner.equals(file.getOwnerId()) || !"MEMBER".equals(file.getUserEnums())
                || !Boolean.FALSE.equals(file.getDeleteFlag()) || !"IMAGE".equals(file.getFileCategory())
                || !Set.of("image/jpeg","image/png").contains(file.getFileType()) || file.getFileSize()==null
                || file.getFileSize()<=0 || file.getFileSize()>LIMIT || file.getFileKey()==null
                || !file.getFileKey().startsWith(prefix(owner)) || !safeUrl(file.getUrl())) throw new ResidentProfileException(AVATAR_REJECTED);
        return file.getUrl();
    }
    private static String prefix(String owner) {return "MEMBER/"+owner+"/stage0-avatar/";}
    private static boolean safeUrl(String value) {
        try {var uri=URI.create(value); return value.length()<=255 && "https".equals(uri.getScheme()) && uri.getHost()!=null
                && uri.getUserInfo()==null && uri.getQuery()==null && uri.getFragment()==null;}
        catch(Exception e) {return false;}
    }
    static Image decode(MultipartFile file) {
        try {
            if(file==null || file.isEmpty() || file.getSize()>LIMIT) throw new ResidentProfileException(AVATAR_REJECTED);
            byte[] bytes; try(var input=file.getInputStream()) {bytes=input.readNBytes(LIMIT+1);}
            if(bytes.length==0 || bytes.length>LIMIT) throw new ResidentProfileException(AVATAR_REJECTED);
            try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers=ImageIO.getImageReaders(input); if(!readers.hasNext()) throw new ResidentProfileException(AVATAR_REJECTED);
                var reader=readers.next();
                try {
                    String format=reader.getFormatName().toLowerCase(Locale.ROOT);
                    if(!Set.of("jpeg","png").contains(format) || !("image/"+format).equalsIgnoreCase(file.getContentType())) throw new ResidentProfileException(AVATAR_REJECTED);
                    reader.setInput(input); int w=reader.getWidth(0), h=reader.getHeight(0);
                    if(w<=0 || h<=0 || w>4096 || h>4096 || (long)w*h>16_000_000) throw new ResidentProfileException(AVATAR_REJECTED);
                    var decoded=reader.read(0); var output=new ByteArrayOutputStream();
                    if(!ImageIO.write(decoded,format,output) || output.size()>LIMIT) throw new ResidentProfileException(AVATAR_REJECTED);
                    return new Image(format,output.toByteArray()); // Re-encode: discard metadata and appended payload.
                } finally {reader.dispose();}
            }
        } catch(ResidentProfileException e) {throw e;}
        catch(Exception e) {throw new ResidentProfileException(AVATAR_REJECTED);}
    }
    record Image(String format,byte[] bytes) { @Override public String toString(){return "AvatarImage[REDACTED]";} }
    public record Avatar(String fileId,String url) { }
}
