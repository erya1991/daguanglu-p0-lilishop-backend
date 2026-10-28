package cn.lili.modules.community.p0.channel;
import cn.lili.modules.community.p0.support.*;
import com.google.gson.JsonObject;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** AES-GCM envelopes with a separate run-owned key; business tables retain opaque references only. */
@Component
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
public class ChannelVault {
    private final Stage1Settings settings;private final SecureRandom random=new SecureRandom();
    public ChannelVault(Stage1Settings settings){this.settings=settings;}
    private Path root(){
        try{Path run=Path.of(settings.getFileRoot()).toRealPath().getParent();Stage1Exception.require(run.getFileName().toString().equals(settings.getRunId())&&Files.isRegularFile(run.resolve("owner.json")),503,"CHANNEL_VAULT_UNAVAILABLE");return run.resolve("private").toRealPath();}
        catch(Exception ignored){throw new ChannelFailure("CHANNEL_VAULT_UNAVAILABLE",false,false);}
    }
    private synchronized byte[] key(){
        try{Path root=root(),file=root.resolve("channel-material-key");if(!Files.exists(file)){
            Path existing=root.resolve("channel-materials");if(Files.isDirectory(existing))try(var entries=Files.list(existing)){if(entries.findAny().isPresent())throw new IllegalStateException();}
            byte[] key=new byte[32];random.nextBytes(key);Files.write(file,key,StandardOpenOption.CREATE_NEW);Arrays.fill(key,(byte)0);
        }byte[] value=Files.readAllBytes(file);if(value.length!=32)throw new IllegalStateException();return value;}
        catch(Exception ignored){throw new ChannelFailure("CHANNEL_VAULT_UNAVAILABLE",false,false);}
    }
    public String put(Object value){
        byte[] secret=key(),plain=write(value).getBytes(StandardCharsets.UTF_8);String ref=UUID.randomUUID().toString();
        try{byte[] nonce=new byte[12];random.nextBytes(nonce);var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(secret,"AES"),new GCMParameterSpec(128,nonce));cipher.updateAAD(("S1_CHANNEL:"+ref).getBytes(StandardCharsets.UTF_8));byte[] encrypted=cipher.doFinal(plain);
            Path directory=root().resolve("channel-materials");Files.createDirectories(directory);if(Files.isSymbolicLink(directory))throw new IllegalStateException();Files.write(directory.resolve(ref),ByteBuffer.allocate(12+encrypted.length).put(nonce).put(encrypted).array(),StandardOpenOption.CREATE_NEW);return ref;
        }catch(Exception ignored){throw new ChannelFailure("CHANNEL_VAULT_UNAVAILABLE",false,false);}finally{Arrays.fill(secret,(byte)0);Arrays.fill(plain,(byte)0);}
    }
    public JsonObject get(String reference){
        if(reference==null||!reference.matches("[a-f0-9-]{36}"))throw new ChannelFailure("CHANNEL_VAULT_UNAVAILABLE",false,false);
        byte[] secret=key(),plain=null;
        try{Path path=root().resolve("channel-materials").resolve(reference);if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>2_000_000)throw new IllegalStateException();byte[] encrypted=Files.readAllBytes(path);if(encrypted.length<28)throw new IllegalStateException();var cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(secret,"AES"),new GCMParameterSpec(128,Arrays.copyOf(encrypted,12)));cipher.updateAAD(("S1_CHANNEL:"+reference).getBytes(StandardCharsets.UTF_8));plain=cipher.doFinal(encrypted,12,encrypted.length-12);return object(new String(plain,StandardCharsets.UTF_8));}
        catch(Exception ignored){throw new ChannelFailure("CHANNEL_VAULT_UNAVAILABLE",false,false);}finally{Arrays.fill(secret,(byte)0);if(plain!=null)Arrays.fill(plain,(byte)0);}
    }
    /** Deployment-injected reference, never a path or key accepted from a business form. */
    JsonObject credential(String reference){
        if(reference==null||!reference.matches("[a-zA-Z0-9_-]{1,64}"))throw new ChannelFailure("CHANNEL_CREDENTIAL_PENDING",false,false);
        try{Path directory=root().resolve("channel-credentials"),file=directory.resolve(reference+".json");if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(directory)||Files.size(file)>100_000)throw new IllegalStateException();return object(Files.readString(file));}
        catch(Exception ignored){throw new ChannelFailure("CHANNEL_CREDENTIAL_PENDING",false,false);}
    }
}
