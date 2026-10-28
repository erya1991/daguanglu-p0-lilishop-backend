package cn.lili.modules.community.p0.channel;

import cn.lili.modules.community.p0.support.*;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.*;
import com.wechat.pay.java.core.RSAPublicKeyConfig;
import org.junit.jupiter.api.*;
import javax.crypto.Cipher;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Real SDK HTTP signing, encryption and verification against a loopback server with synthetic RSA keys. */
class WechatEcommerceChannelTest {
    static KeyPair merchant,platform;HttpServer server;WechatEcommerceChannel port;
    final AtomicReference<Throwable> serverFailure=new AtomicReference<>();final List<String> paths=new ArrayList<>();
    JsonObject captured;boolean badSignature,redirect,missingTimestamp,duplicateSignature,replacedKey,wrongSerial;int responseStatus=200,calls;long timestampOffset;boolean bankBranchRequired=true;
    @BeforeAll static void keys() throws Exception {var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);merchant=generator.generateKeyPair();platform=generator.generateKeyPair();}
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.createContext("/",this::handle);server.start();
        var config=new RSAPublicKeyConfig.Builder().merchantId("1900000001").merchantSerialNumber("SYNTHETIC_MERCHANT_SERIAL").privateKey(merchant.getPrivate()).publicKey(platform.getPublic()).publicKeyId("PUB_KEY_ID_SYNTHETIC").apiV3Key("01234567890123456789012345678901").build();
        port=WechatEcommerceChannel.contract(URI.create("http://127.0.0.1:"+server.getAddress().getPort()),config);
    }
    @AfterEach void stop(){server.stop(0);if(serverFailure.get()!=null)fail("Loopback signature contract assertion failed",serverFailure.get());}
    private void handle(HttpExchange exchange){
        try{
            calls++;paths.add(exchange.getRequestURI().getPath());String body=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.ISO_8859_1),signed=body;
            if(exchange.getRequestURI().getPath().equals("/v3/merchant/media/upload")){
                int start=body.indexOf("\r\n\r\n")+4,end=body.indexOf("\r\n--",start);assertTrue(start>=4&&end>start);signed=body.substring(start,end);captured=object(signed);assertTrue(body.contains("name=\"file\""));
            }else if(!body.isEmpty())captured=object(new String(body.getBytes(StandardCharsets.ISO_8859_1),StandardCharsets.UTF_8));
            String authorization=exchange.getRequestHeaders().getFirst("Authorization");assertTrue(authorization.startsWith("WECHATPAY2-SHA256-RSA2048 "));var attributes=new HashMap<String,String>();var matcher=Pattern.compile("([a-z_]+)=\"([^\"]*)\"").matcher(authorization);while(matcher.find())attributes.put(matcher.group(1),matcher.group(2));
            assertEquals("1900000001",attributes.get("mchid"));assertEquals("SYNTHETIC_MERCHANT_SERIAL",attributes.get("serial_no"));assertTrue(Math.abs(Instant.now().getEpochSecond()-Long.parseLong(attributes.get("timestamp")))<60);
            String message=exchange.getRequestMethod()+"\n"+exchange.getRequestURI()+"\n"+attributes.get("timestamp")+"\n"+attributes.get("nonce_str")+"\n"+signed+"\n";
            var verifier=Signature.getInstance("SHA256withRSA");verifier.initVerify(merchant.getPublic());verifier.update(message.getBytes(StandardCharsets.ISO_8859_1));assertTrue(verifier.verify(Base64.getDecoder().decode(attributes.get("signature"))));
            if(redirect){exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/unapproved");exchange.sendResponseHeaders(302,-1);return;}
            boolean bankDirectory=exchange.getRequestURI().getPath().startsWith("/v3/capital/capitallhh/banks/");
            String response=write(bankDirectory?map("total_count",1,"count",1,"offset",0,"data",List.of(map("account_bank_code",1002,"account_bank","S1_TEST_BANK","need_bank_branch",bankBranchRequired))):exchange.getRequestURI().getPath().endsWith("/receivers/add")?map("type","MERCHANT_ID","account","1900000002","relation_type","PLATFORM"):map("applyment_id","9007199254740999","out_request_no","S1_TEST_CASE","applyment_state","FINISH","media_id","SYNTHETIC_MEDIA","code","SYSTEM_ERROR"));
            String timestamp=Long.toString(Instant.now().getEpochSecond()+timestampOffset),nonce=UUID.randomUUID().toString();var signer=Signature.getInstance("SHA256withRSA");signer.initSign(replacedKey?merchant.getPrivate():platform.getPrivate());signer.update((timestamp+"\n"+nonce+"\n"+response+"\n").getBytes(StandardCharsets.UTF_8));String signature=Base64.getEncoder().encodeToString(signer.sign());
            if(!missingTimestamp)exchange.getResponseHeaders().set("Wechatpay-Timestamp",timestamp);exchange.getResponseHeaders().set("Wechatpay-Nonce",nonce);exchange.getResponseHeaders().set("Wechatpay-Serial",wrongSerial?"PUB_KEY_ID_REPLACED":"PUB_KEY_ID_SYNTHETIC");exchange.getResponseHeaders().set("Wechatpay-Signature",badSignature?Base64.getEncoder().encodeToString(new byte[256]):signature);if(duplicateSignature)exchange.getResponseHeaders().add("Wechatpay-Signature",signature);exchange.getResponseHeaders().set("Content-Type","application/json");byte[] bytes=response.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(bankDirectory?200:responseStatus,bytes.length);exchange.getResponseBody().write(bytes);
        }catch(Throwable error){serverFailure.set(error);try{exchange.sendResponseHeaders(500,-1);}catch(Exception ignored){}}finally{exchange.close();}
    }
    @Test void submissionUsesEcommercePathAndEncryptsSensitiveFields() throws Exception {
        var payload=materials();
        var response=port.submitApplication("S1_TEST_CASE",payload);assertEquals("9007199254740999",response.get("applyment_id").getAsString());assertEquals(List.of("/v3/capital/capitallhh/banks/corporate-banking","/v3/ecommerce/applyments/"),paths);assertEquals("S1_TEST_BANK",captured.getAsJsonObject("account_info").get("account_bank").getAsString());
        assertFalse(write(captured).contains("123456789012"));var decrypt=Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");decrypt.init(Cipher.DECRYPT_MODE,platform.getPrivate());assertEquals("123456789012",new String(decrypt.doFinal(Base64.getDecoder().decode(captured.getAsJsonObject("account_info").get("account_number").getAsString())),StandardCharsets.UTF_8));assertEquals("123456789012",payload.getAsJsonObject("account_info").get("account_number").getAsString());
    }
    @Test void badResponseSignatureIsSanitizedAndNeverRetried(){badSignature=true;var error=assertThrows(ChannelFailure.class,()->port.queryByOutRequestNo("S1_TEST_CASE"));assertEquals("CHANNEL_SIGNATURE_INVALID",error.code());assertNull(error.getCause());assertEquals(1,calls);assertFalse(error.toString().contains("9007199254740999"));}
    @Test void missingSignatureHeaderIsNotRetried(){missingTimestamp=true;assertEquals("CHANNEL_SIGNATURE_INVALID",assertThrows(ChannelFailure.class,()->port.queryByOutRequestNo("S1_TEST_CASE")).code());assertEquals(1,calls);}
    @Test void staleSignedResponseIsNotRetried(){timestampOffset=-301;assertEquals("CHANNEL_SIGNATURE_INVALID",assertThrows(ChannelFailure.class,()->port.queryByOutRequestNo("S1_TEST_CASE")).code());assertEquals(1,calls);}
    @Test void duplicateSignatureHeaderIsRejected(){duplicateSignature=true;assertEquals("CHANNEL_SIGNATURE_INVALID",assertThrows(ChannelFailure.class,()->port.queryByOutRequestNo("S1_TEST_CASE")).code());assertEquals(1,calls);}
    @Test void mediaSignsActualMetadataDigestAndUsesMultipart(){byte[] bytes={1,2,3,4,5};assertEquals("SYNTHETIC_MEDIA",port.uploadMaterial("synthetic.png","image/png",bytes));assertEquals(digest(bytes),captured.get("sha256").getAsString());assertEquals("synthetic.png",captured.get("filename").getAsString());}
    @Test void queryRetriesOnlyBoundedTransientFailures(){responseStatus=503;var error=assertThrows(ChannelFailure.class,()->port.queryByApplymentId("12345"));assertEquals("CHANNEL_TEMPORARY_UNAVAILABLE",error.code());assertEquals(3,calls);}
    @Test void submitIsNeverAutomaticallyRepeatedAfterUnknownResponse(){responseStatus=503;assertThrows(ChannelFailure.class,()->port.submitApplication("S1_TEST_CASE",materials()));assertEquals(2,calls);assertEquals(1,paths.stream().filter(p->p.equals("/v3/ecommerce/applyments/")).count());}
    @Test void branchExemptionComesOnlyFromVerifiedDirectory(){var payload=materials();payload.getAsJsonObject("account_info").remove("bank_name");payload.getAsJsonObject("account_info").addProperty("branchRequired",false);assertEquals("CHANNEL_BANK_BRANCH_REQUIRED",assertThrows(ChannelFailure.class,()->port.submitApplication("S1_TEST_CASE",payload)).code());assertEquals(1,calls);bankBranchRequired=false;assertNotNull(port.submitApplication("S1_TEST_CASE",payload));assertEquals(3,calls);}
    @Test void personalBankUsesItsOwnDirectoryAndUnknownBankIsRejected(){var payload=materials();payload.getAsJsonObject("account_info").addProperty("bank_account_type","75");payload.getAsJsonObject("account_info").addProperty("account_bank","9999");assertEquals("CHANNEL_BANK_NOT_SUPPORTED",assertThrows(ChannelFailure.class,()->port.submitApplication("S1_TEST_CASE",payload)).code());assertEquals(List.of("/v3/capital/capitallhh/banks/personal-banking"),paths);}
    private JsonObject materials(){return object(map("organization_type","2","business_license_info",map("merchant_name","S1_TEST"),"account_info",map("bank_account_type","74","account_bank","1002","bank_name","S1_TEST_BRANCH","account_name","SYNTHETIC_OWNER","account_number","123456789012"),"contact_info",map("contact_name","SYNTHETIC_CONTACT","mobile_phone","13800000000"),"sales_scene_info",map("store_name","S1_TEST")));}
    @Test void authenticationFailureDoesNotRetry(){responseStatus=403;assertEquals("CHANNEL_AUTH_REJECTED",assertThrows(ChannelFailure.class,()->port.queryByOutRequestNo("S1_TEST_CASE")).code());assertEquals(1,calls);}
    @Test void redirectCannotReachAnotherEndpoint(){redirect=true;assertThrows(ChannelFailure.class,()->port.queryByOutRequestNo("S1_TEST_CASE"));assertEquals(1,calls);}
    @Test void arbitraryPathsAndFundOperationsAreRejected(){for(String path:List.of("/v3/pay/transactions/jsapi","/v3/ecommerce/profitsharing/orders","/v3/refund/domestic/refunds","/v3/applyment4sub/applyment/"))assertFalse(WechatEcommerceChannel.allowed("POST",path));assertThrows(ChannelFailure.class,()->port.queryByOutRequestNo("../other"));assertEquals(0,calls);}

    @Test void bothQueryIdentifiersAndReceiverRegistrationUseVerifiedOfficialContracts()throws Exception{
        assertEquals("9007199254740999",port.queryByOutRequestNo("S1_TEST_CASE").get("applyment_id").getAsString());
        assertEquals("FINISH",port.queryByApplymentId("9007199254740999").get("applyment_state").getAsString());
        var result=port.ensureReceiverRelation(object(map("appid","wx1111111111111111","type","MERCHANT_ID","account","1900000002","name","S1_TEST_PLATFORM","relation_type","PLATFORM")));
        assertEquals("1900000002",result.get("account").getAsString());assertEquals("MERCHANT_ID",result.get("type").getAsString());assertEquals(List.of("/v3/ecommerce/applyments/out-request-no/S1_TEST_CASE","/v3/ecommerce/applyments/9007199254740999","/v3/ecommerce/profitsharing/receivers/add"),paths);
        assertFalse(write(captured).contains("S1_TEST_PLATFORM"));var decrypt=Cipher.getInstance("RSA/ECB/OAEPWithSHA-1AndMGF1Padding");decrypt.init(Cipher.DECRYPT_MODE,platform.getPrivate());assertEquals("S1_TEST_PLATFORM",new String(decrypt.doFinal(Base64.getDecoder().decode(captured.get("name").getAsString())),StandardCharsets.UTF_8));
    }
    @Test void replacedKeyAndUnexpectedSerialCannotVerifyEvenFreshSignedResponses(){
        replacedKey=true;assertEquals("CHANNEL_SIGNATURE_INVALID",assertThrows(ChannelFailure.class,()->port.queryByOutRequestNo("S1_TEST_CASE")).code());assertEquals(1,calls);
        replacedKey=false;wrongSerial=true;assertEquals("CHANNEL_SIGNATURE_INVALID",assertThrows(ChannelFailure.class,()->port.queryByOutRequestNo("S1_TEST_CASE")).code());assertEquals(2,calls);
    }
    @Test void realCredentialsRejectEveryMissingFieldAndCertificateModeCannotBypassPublicKeyValidation(){
        var settings=new Stage1Settings();var credentials=object(map("merchantId","1900000001","merchantSerialNumber","SYNTHETIC_MERCHANT_SERIAL","privateKeyPkcs8",Base64.getEncoder().encodeToString(merchant.getPrivate().getEncoded()),"publicKeyId","PUB_KEY_ID_SYNTHETIC","publicKeyX509",Base64.getEncoder().encodeToString(platform.getPublic().getEncoded()),"apiV3Key","01234567890123456789012345678901"));
        for(String field:new ArrayList<>(credentials.keySet())){var missing=credentials.deepCopy();missing.remove(field);assertEquals("CHANNEL_CREDENTIAL_INVALID",assertThrows(ChannelFailure.class,()->WechatEcommerceChannel.real(settings,missing)).code(),field);}
        var real=WechatEcommerceChannel.real(settings,credentials);assertEquals("CHANNEL_OUTBOUND_NOT_AUTHORIZED",assertThrows(ChannelFailure.class,()->real.queryByOutRequestNo("S1_TEST_CASE")).code());
        credentials.addProperty("platformCertificate","S1_TEST_EXPIRED_CERTIFICATE");assertEquals("CHANNEL_CREDENTIAL_INVALID",assertThrows(ChannelFailure.class,()->WechatEcommerceChannel.real(settings,credentials)).code());assertEquals(0,calls);
    }
    @Test void mockMediaIdentifiersAreRejectedBeforeAnyRealTransport(){
        for(String field:List.of("business_license_copy","id_doc_copy_back","store_qr_code","qualifications","business_addition_pics")){var payload=materials();payload.getAsJsonObject("business_license_info").addProperty(field,"MOCK_fixture_media");assertEquals("CHANNEL_MEDIA_MODE_MISMATCH",assertThrows(ChannelFailure.class,()->port.submitApplication("S1_TEST_CASE",payload)).code(),field);}assertEquals(0,calls);
    }
    @Test void finishDoesNotClaimUnverifiedRealCapabilities(){var result=port.verifyRelation(object(map("applyment_state","FINISH","sub_mchid","1234567890")),"wxsynthetic");assertEquals("REAL",result.get("sourceMode"));assertEquals(false,result.get("paymentEnabled"));assertNull(result.get("maxRateBps"));assertEquals(0,calls);}
}
