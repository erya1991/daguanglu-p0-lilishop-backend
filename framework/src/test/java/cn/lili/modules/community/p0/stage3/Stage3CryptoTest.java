package cn.lili.modules.community.p0.stage3;
import cn.lili.modules.community.p0.support.Stage1Exception;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class Stage3CryptoTest {
    private final Stage3Crypto crypto=new Stage3Crypto(new byte[32]);
    @Test void credentialRetains128RandomBitsAndManualFormat(){
        Set<String> seen=new HashSet<>();for(int i=0;i<256;i++){String token=crypto.credential();assertEquals(26,token.length());assertTrue(seen.add(token));assertEquals(token,Stage3Crypto.normalize(token.substring(0,13)+"-"+token.substring(13).toLowerCase(Locale.ROOT)));}
        assertThrows(Stage1Exception.class,()->Stage3Crypto.normalize("12345678"));
        assertThrows(Stage1Exception.class,()->Stage3Crypto.normalize("I".repeat(26)));
    }
    @Test void authenticatedEncryptionRejectsWrongOrderWrongKeyAndTampering(){
        String token=crypto.credential(),encrypted=crypto.encrypt(token,"9007199254740993");
        assertNotEquals(token,encrypted);assertEquals(token,crypto.decrypt(encrypted,"9007199254740993"));assertNotEquals(encrypted,crypto.encrypt(token,"9007199254740993"));
        assertThrows(Stage1Exception.class,()->crypto.decrypt(encrypted,"9007199254740994"));
        byte[] other=new byte[32];other[0]=1;assertThrows(Stage1Exception.class,()->new Stage3Crypto(other).decrypt(encrypted,"9007199254740993"));
        assertThrows(Stage1Exception.class,()->crypto.decrypt(encrypted.substring(0,encrypted.length()-3)+"AAA","9007199254740993"));
    }
    @Test void confirmationAndCredentialHashesHaveSeparatePurposes(){
        String token=crypto.credential();assertNotEquals(crypto.index(token),crypto.mac("confirmation:"+token));
        assertTrue(crypto.authentic("confirmation:actor-point-version",crypto.mac("confirmation:actor-point-version")));
        assertFalse(crypto.authentic("confirmation:other-point",crypto.mac("confirmation:actor-point-version")));
        assertFalse(crypto.toString().contains(token));
    }
    @Test void offlineQrAndGroupedManualInputExpressTheSameCredential()throws Exception{
        String token=crypto.credential();var matrix=new com.google.zxing.qrcode.QRCodeWriter().encode("DGLS3:"+token,com.google.zxing.BarcodeFormat.QR_CODE,280,280);
        var picture=com.google.zxing.client.j2se.MatrixToImageWriter.toBufferedImage(matrix);var bitmap=new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(new com.google.zxing.client.j2se.BufferedImageLuminanceSource(picture)));
        assertEquals("DGLS3:"+token,new com.google.zxing.MultiFormatReader().decode(bitmap).getText());assertEquals(crypto.index(token),crypto.index(token.substring(0,5)+" "+token.substring(5,10)+"-"+token.substring(10).toLowerCase(Locale.ROOT)));
    }
}
