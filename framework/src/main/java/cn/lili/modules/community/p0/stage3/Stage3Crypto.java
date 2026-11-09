package cn.lili.modules.community.p0.stage3;

import cn.lili.modules.community.p0.support.Stage1Exception;
import javax.crypto.*;
import javax.crypto.spec.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Fixed 128-bit credential; never use this class' values in logs or public URLs. */
public final class Stage3Crypto {
    private static final char[] ALPHABET="0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private final byte[] encryptionKey,indexKey,confirmationKey;
    private final SecureRandom random=new SecureRandom();
    public Stage3Crypto(byte[] key) { if(key.length!=32)throw new IllegalArgumentException("256-bit private key required");this.encryptionKey=derive(key,"S3:AES-GCM");this.indexKey=derive(key,"S3:CREDENTIAL-INDEX");this.confirmationKey=derive(key,"S3:CONFIRMATION"); }
    public String credential() {
        byte[] bytes=new byte[16];random.nextBytes(bytes);StringBuilder out=new StringBuilder();int buffer=0,bits=0;
        for(byte b:bytes){buffer=(buffer<<8)|(b&255);bits+=8;while(bits>=5){bits-=5;out.append(ALPHABET[(buffer>>bits)&31]);}}
        if(bits>0)out.append(ALPHABET[(buffer<<(5-bits))&31]);
        return out.toString();
    }
    public static String normalize(String token) {
        if(token==null)throw invalid();
        String value=token.replace("-","").replace(" ","").toUpperCase(Locale.ROOT);
        if(!value.matches("[0-9A-HJKMNP-TV-Z]{26}"))throw invalid();
        return value;
    }
    public String index(String token) { return authenticate(indexKey,"credential:"+normalize(token)); }
    private static byte[] derive(byte[] root,String purpose){try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(root,"HmacSHA256"));return mac.doFinal(purpose.getBytes(StandardCharsets.US_ASCII));}catch(GeneralSecurityException e){throw new Stage1Exception(503,"CREDENTIAL_SECURITY_UNAVAILABLE");}}
    public String mac(String payload){return authenticate(confirmationKey,payload);}
    private static String authenticate(byte[] key,String payload) {
        try{Mac mac=Mac.getInstance("HmacSHA256");mac.init(new SecretKeySpec(key,"HmacSHA256"));return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));}
        catch(GeneralSecurityException e){throw new Stage1Exception(503,"CREDENTIAL_SECURITY_UNAVAILABLE");}
    }
    public boolean authentic(String payload,String signature) {
        return signature!=null&&MessageDigest.isEqual(mac(payload).getBytes(StandardCharsets.US_ASCII),signature.getBytes(StandardCharsets.US_ASCII));
    }
    public String encrypt(String token,String order) {
        try{byte[] iv=new byte[12];random.nextBytes(iv);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(encryptionKey,"AES"),new GCMParameterSpec(128,iv));cipher.updateAAD(order.getBytes(StandardCharsets.US_ASCII));return Base64.getEncoder().encodeToString(iv)+"."+Base64.getEncoder().encodeToString(cipher.doFinal(normalize(token).getBytes(StandardCharsets.US_ASCII)));}
        catch(GeneralSecurityException e){throw new Stage1Exception(503,"CREDENTIAL_SECURITY_UNAVAILABLE");}
    }
    public String decrypt(String encrypted,String order) {
        try{String[] parts=encrypted.split("\\.",-1);if(parts.length!=2)throw invalid();Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(encryptionKey,"AES"),new GCMParameterSpec(128,Base64.getDecoder().decode(parts[0])));cipher.updateAAD(order.getBytes(StandardCharsets.US_ASCII));return normalize(new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])),StandardCharsets.US_ASCII));}
        catch(GeneralSecurityException|IllegalArgumentException e){throw new Stage1Exception(503,"CREDENTIAL_SECURITY_UNAVAILABLE");}
    }
    private static Stage1Exception invalid(){return new Stage1Exception(404,"CREDENTIAL_UNAVAILABLE");}
    @Override public String toString(){return "Stage3Crypto[REDACTED]";}
}
