package cn.lili.modules.community.p0.channel;

import cn.lili.modules.community.p0.support.*;
import com.google.gson.*;
import com.wechat.pay.java.core.*;
import com.wechat.pay.java.core.cipher.*;
import com.wechat.pay.java.core.exception.*;
import com.wechat.pay.java.core.http.*;
import okhttp3.OkHttpClient;
import java.net.URI;
import java.security.*;
import java.security.spec.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Official ecommerce submit/query/receiver/media contracts. Ordinary applyment4sub and all fund endpoints are excluded.
 * Sources: pay.wechatpay.cn/doc/v3/partner/{4012713017,4012691376,4012477758,4012760432}.
 */
public final class WechatEcommerceChannel implements OnboardingChannelPort {
    private static final URI OFFICIAL=URI.create("https://api.mch.weixin.qq.com");
    private final HttpClient client;private final PrivacyEncryptor encryptor;private final URI base;private final Runnable outbound;
    private static final Set<String> SENSITIVE=Set.of("id_card_name","id_card_number","id_doc_name","id_doc_number","id_doc_address","account_name","account_number","contact_name","contact_id_card_number","mobile_phone","ubo_id_doc_name","ubo_id_doc_number","ubo_id_doc_address");
    private WechatEcommerceChannel(URI base,RSAPublicKeyConfig config,Runnable outbound){
        this.base=base;this.outbound=outbound;this.encryptor=config.createEncryptor();
        var http=new OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false).connectTimeout(3,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).writeTimeout(10,TimeUnit.SECONDS).callTimeout(15,TimeUnit.SECONDS).addInterceptor(chain->{
            var response=chain.proceed(chain.request());
            for(String name:List.of("Wechatpay-Timestamp","Wechatpay-Nonce","Wechatpay-Serial","Wechatpay-Signature"))if(response.headers(name).size()>1){response.close();throw new ChannelFailure("CHANNEL_SIGNATURE_INVALID",true,false);}
            return response;
        }).build();
        this.client=new DefaultHttpClientBuilder().config(config).validator(new ResponseValidator(config.createVerifier())).okHttpClient(http).disableRetryOnConnectionFailure().build();
    }
    public static WechatEcommerceChannel real(Stage1Settings settings,JsonObject credentials){
        return new WechatEcommerceChannel(OFFICIAL,config(credentials),()->{if(!settings.isRealOutboundEnabled())throw new ChannelFailure("CHANNEL_OUTBOUND_NOT_AUTHORIZED",false,false);});
    }
    // Package-private seam exists only for the local HTTP signature contract tests, never a runtime URL setting.
    static WechatEcommerceChannel contract(URI endpoint,RSAPublicKeyConfig config){
        if(!endpoint.getScheme().equals("http")||!endpoint.getHost().equals("127.0.0.1")||endpoint.getPort()<1024||endpoint.getRawUserInfo()!=null||endpoint.getRawQuery()!=null||endpoint.getRawFragment()!=null||!Set.of("","/").contains(endpoint.getPath()))throw new IllegalArgumentException("LOCAL_CONTRACT_ENDPOINT_REQUIRED");
        return new WechatEcommerceChannel(endpoint,config,()->{});
    }
    private static RSAPublicKeyConfig config(JsonObject value){
        try{var f=new Fields(value).only("merchantId","merchantSerialNumber","privateKeyPkcs8","publicKeyId","publicKeyX509","apiV3Key");var factory=KeyFactory.getInstance("RSA");
            PrivateKey privateKey=factory.generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(f.text("privateKeyPkcs8",100,20000,true))));PublicKey publicKey=factory.generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(f.text("publicKeyX509",100,10000,true))));
            return new RSAPublicKeyConfig.Builder().merchantId(f.text("merchantId",8,32,true)).merchantSerialNumber(f.text("merchantSerialNumber",1,128,true)).privateKey(privateKey).publicKey(publicKey).publicKeyId(f.text("publicKeyId",1,128,true)).apiV3Key(f.text("apiV3Key",32,32,true)).build();
        }catch(RuntimeException|GeneralSecurityException ignored){throw new ChannelFailure("CHANNEL_CREDENTIAL_INVALID",false,false);}
    }
    @Override public void validateMaterials(JsonObject mapped){
        Stage1Exception.require(Set.of("2","4").contains(mapped.get("organization_type").getAsString()),422,"CHANNEL_SUBJECT_UNSUPPORTED");
        for(String name:List.of("business_license_info","account_info","contact_info","sales_scene_info"))Stage1Exception.require(mapped.has(name)&&mapped.get(name).isJsonObject(),422,"CHANNEL_MATERIAL_REQUIRED");
        rejectMockMedia(mapped);
    }
    private static void rejectMockMedia(JsonElement element){
        if(element.isJsonArray()){element.getAsJsonArray().forEach(WechatEcommerceChannel::rejectMockMedia);return;}
        if(!element.isJsonObject())return;
        for(var entry:element.getAsJsonObject().entrySet()){
            var value=entry.getValue();String key=entry.getKey();
            if(value.isJsonPrimitive()&&value.getAsJsonPrimitive().isString()&&(key.endsWith("_copy")||key.endsWith("_copy_back")||Set.of("id_card_national","store_qr_code","qualifications","business_addition_pics").contains(key))&&value.getAsString().contains("MOCK_"))throw new ChannelFailure("CHANNEL_MEDIA_MODE_MISMATCH",false,false);
            rejectMockMedia(value);
        }
    }
    @Override public JsonObject submitApplication(String out,JsonObject mapped){validateMaterials(mapped);var payload=mapped.deepCopy();validateBank(payload.getAsJsonObject("account_info"));payload.addProperty("out_request_no",segment(out,124));encrypt(payload);return send("POST","/v3/ecommerce/applyments/",new JsonRequestBody.Builder().body(write(payload)).build(),false);}
    private void validateBank(JsonObject account){
        var f=new Fields(account);String type=f.choice("bank_account_type",Set.of("74","75"),true),code=f.text("account_bank",1,128,true);
        // Official read-only bank directory: 4012697626 / 4012697583. No client-supplied branch exemption.
        String route="/v3/capital/capitallhh/banks/"+(type.equals("74")?"corporate-banking":"personal-banking");
        for(int offset=0;offset<10000;offset+=200){var response=send("GET",route+"?offset="+offset+"&limit=200",null,true);var page=new Fields(response);long total=page.integer("total_count",0,10000,true);var rows=page.list("data",0,200,false);for(var item:rows){var bank=item.getAsJsonObject();if(bank.has("account_bank_code")&&bank.has("account_bank")&&(code.equals(bank.get("account_bank_code").getAsString())||code.equals(bank.get("account_bank").getAsString()))){ChannelBankRules.apply(account,bank);return;}}if(offset+200>=total)throw new ChannelFailure("CHANNEL_BANK_NOT_SUPPORTED",false,false);if(rows.isEmpty())throw new ChannelFailure("CHANNEL_BANK_DIRECTORY_INVALID",false,false);}
        throw new ChannelFailure("CHANNEL_BANK_DIRECTORY_INVALID",false,false);
    }
    @Override public JsonObject queryByOutRequestNo(String out){return send("GET","/v3/ecommerce/applyments/out-request-no/"+segment(out,124),null,true);}
    @Override public JsonObject queryByApplymentId(String id){return send("GET","/v3/ecommerce/applyments/"+segment(id,64),null,true);}
    @Override public String uploadMaterial(String name,String mime,byte[] bytes){
        boolean pdf=mime.equals("application/pdf");if(!Set.of("image/png","image/jpeg","application/pdf").contains(mime)||bytes.length==0||bytes.length>(pdf?7_864_320:5_242_880)||!name.matches("[A-Za-z0-9_-]{1,100}\\.(png|jpg|jpeg|pdf)"))throw new ChannelFailure("CHANNEL_MEDIA_INVALID",false,false);
        String metadata=write(map("filename",name,"sha256",digest(bytes)));
        var result=send("POST","/v3/merchant/media/upload",new FileRequestBody.Builder().meta(metadata).fileName(name).file(bytes).build(),false);
        return new Fields(result).text("media_id",1,512,true);
    }
    @Override public JsonObject ensureReceiverRelation(JsonObject receiver){
        var f=new Fields(receiver).only("appid","type","account","name","relation_type");f.text("appid",1,128,true);f.choice("type",Set.of("MERCHANT_ID"),true);f.text("account",1,64,true);f.text("name",2,128,true);f.choice("relation_type",Set.of("PLATFORM"),true);
        var payload=receiver.deepCopy();payload.addProperty("name",encryptor.encrypt(payload.get("name").getAsString()));
        return send("POST","/v3/ecommerce/profitsharing/receivers/add",new JsonRequestBody.Builder().body(write(payload)).build(),false);
    }
    @Override public Map<String,Object> verifyRelation(JsonObject application,String paymentAppId){
        // FINISH establishes an application result, not AppID linkage, product permission or a commission cap.
        // No verified account-specific authority for these checks is available in the approved local run.
        return map("sourceMode","REAL","paymentAppId",paymentAppId,"paymentEnabled",false,"appIdLinked",false,"profitsharingEnabled",false,"maxRateBps",null,"verificationState","UNVERIFIED","reason","ACCOUNT_CAPABILITY_EVIDENCE_PENDING");
    }
    private void encrypt(JsonElement element){
        if(element.isJsonArray()){element.getAsJsonArray().forEach(this::encrypt);return;}
        if(!element.isJsonObject())return;var object=element.getAsJsonObject();
        for(var entry:new ArrayList<>(object.entrySet())){if(SENSITIVE.contains(entry.getKey())&&!entry.getValue().isJsonNull())object.addProperty(entry.getKey(),encryptor.encrypt(entry.getValue().getAsString()));else encrypt(entry.getValue());}
    }
    private JsonObject send(String method,String path,RequestBody body,boolean query){
        if(!allowed(method,path))throw new ChannelFailure("CHANNEL_ENDPOINT_FORBIDDEN",false,false);outbound.run();int limit=query?3:1;
        for(int attempt=1;;attempt++)try{
            var request=new HttpRequest.Builder().httpMethod(HttpMethod.valueOf(method)).url(base.resolve(path).toString()).addHeader("Accept","application/json").addHeader("Wechatpay-Serial",encryptor.getWechatpaySerial());if(body!=null)request.body(body);
            var response=client.execute(request.build(),JsonObject.class).getServiceResponse();if(response==null)throw new ChannelFailure("CHANNEL_RESPONSE_INVALID",true,false);return response;
        }catch(ValidationException ignored){throw new ChannelFailure("CHANNEL_SIGNATURE_INVALID",true,false);}
        catch(MalformedMessageException ignored){throw new ChannelFailure("CHANNEL_RESPONSE_INVALID",true,false);}
        catch(ServiceException failure){int code=failure.getHttpStatusCode();boolean retry=code==429||code>=500;
            if(query&&retry&&attempt<limit){backoff(attempt);continue;}throw new ChannelFailure(code==401||code==403?"CHANNEL_AUTH_REJECTED":code==404?"CHANNEL_APPLICATION_NOT_FOUND":retry?"CHANNEL_TEMPORARY_UNAVAILABLE":"CHANNEL_REQUEST_REJECTED",retry,retry);
        }catch(ChannelFailure safe){throw safe;}
        catch(RuntimeException ignored){if(query&&attempt<limit){backoff(attempt);continue;}throw new ChannelFailure("CHANNEL_TRANSPORT_UNKNOWN",true,true);}
    }
    static boolean allowed(String method,String path){return method.equals("POST")&&Set.of("/v3/ecommerce/applyments/","/v3/ecommerce/profitsharing/receivers/add","/v3/merchant/media/upload").contains(path)||method.equals("GET")&&(path.matches("/v3/ecommerce/applyments/(?:out-request-no/[A-Za-z0-9_-]{1,124}|[0-9]{1,64})")||path.matches("/v3/capital/capitallhh/banks/(?:corporate-banking|personal-banking)\\?offset=[0-9]{1,4}&limit=200"));}
    private static String segment(String value,int max){if(value==null||!value.matches("[A-Za-z0-9_-]{1,"+max+"}"))throw new ChannelFailure("CHANNEL_IDENTIFIER_INVALID",false,false);return value;}
    /** HTTP header names are case insensitive. Keep SDK RSA verification without logging signed private responses. */
    private static final class ResponseValidator implements com.wechat.pay.java.core.auth.Validator {
        private final Verifier verifier;
        ResponseValidator(Verifier verifier){this.verifier=verifier;}
        public <T> String getSerialNumber(){return verifier.getSerialNumber();}
        public <T> boolean validate(HttpHeaders headers,String body){
            try{
                String timestamp=header(headers,"Wechatpay-Timestamp"),nonce=header(headers,"Wechatpay-Nonce"),serial=header(headers,"Wechatpay-Serial"),signature=header(headers,"Wechatpay-Signature");
                long seconds=Long.parseLong(timestamp),now=java.time.Instant.now().getEpochSecond();
                if(seconds<now-300||seconds>now+300||nonce.isEmpty()||nonce.length()>256||signature.length()>2048||!serial.equals(verifier.getSerialNumber()))return false;
                return verifier.verify(serial,timestamp+"\n"+nonce+"\n"+(body==null?"":body)+"\n",signature);
            }catch(RuntimeException ignored){return false;}
        }
        private static String header(HttpHeaders headers,String name){var values=headers.getHeaders().entrySet().stream().filter(e->e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).toList();if(values.size()!=1)throw new IllegalArgumentException("INVALID_SIGNATURE_HEADER");return values.getFirst();}
    }
    private static void backoff(int attempt){try{Thread.sleep(100L*(1L<<attempt));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new ChannelFailure("CHANNEL_INTERRUPTED",true,false);}}
}
