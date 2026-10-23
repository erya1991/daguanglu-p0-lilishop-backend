package cn.lili.modules.member.profile;

import cn.lili.common.security.session.http.SessionApplications;
import cn.lili.modules.connect.wechat.ResidentWechatProperties;
import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static cn.lili.modules.member.profile.ResidentProfileException.Code.*;

class OfficialWechatPhoneGatewayTest {
    final SessionApplications apps=new SessionApplications();
    final ResidentWechatProperties wechat=new ResidentWechatProperties();
    final ResidentProfileProperties props=new ResidentProfileProperties();
    final Clock clock=Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"),ZoneOffset.UTC);
    final AtomicInteger tokenCalls=new AtomicInteger(),phoneCalls=new AtomicInteger();
    final String secret=UUID.randomUUID().toString().replace("-","");
    JsonObject response;
    final Set<String> used=new HashSet<>();
    final OfficialWechatPhoneGateway gateway;
    OfficialWechatPhoneGatewayTest() {
        apps.setResidentApplication(OfflineResidentStore.APP);apps.setMerchantApplication("wx0000000000000002");
        wechat.setEnabled(true);wechat.setSchemaReady(true);wechat.setAppSecret(secret);props.setSchemaReady(true);props.setPhoneEnabled(true);
        response=JsonParser.parseString("{\"errcode\":0,\"phone_info\":{\"countryCode\":\"86\",\"purePhoneNumber\":\"13900000001\",\"phoneNumber\":\"13900000001\",\"watermark\":{\"appid\":\""+OfflineResidentStore.APP+"\",\"timestamp\":"+clock.instant().getEpochSecond()+"}}}").getAsJsonObject();
        gateway=new OfficialWechatPhoneGateway(apps,wechat,props,(url,body)->{
            var json=JsonParser.parseString(body).getAsJsonObject();
            if(url.equals("https://api.weixin.qq.com/cgi-bin/stable_token")) {
                tokenCalls.incrementAndGet();assertEquals(apps.getResidentApplication(),json.get("appid").getAsString());assertFalse(json.get("force_refresh").getAsBoolean());
                return "{\"access_token\":\"synthetic-server-token\",\"expires_in\":7200}";
            }
            assertEquals("https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token=synthetic-server-token",url);
            phoneCalls.incrementAndGet();assertEquals(Set.of("code"),json.keySet());
            if(!used.add(json.get("code").getAsString()))return "{\"errcode\":40163}";
            return response.toString();
        },clock);
    }
    @Test void officialPhoneResultAndApplicationScopedTokenReuse() {assertEquals("13900000001",gateway.verify("phone-code-1"));gateway.verify("phone-code-2");assertEquals(1,tokenCalls.get());assertEquals(2,phoneCalls.get());}
    @Test void sameCodeCannotBeUsedTwice() {gateway.verify("one-use");assertEquals(PHONE_REJECTED,assertThrows(ResidentProfileException.class,()->gateway.verify("one-use")).getCode());}
    @ParameterizedTest @ValueSource(ints={40029,40163,40013,45011})
    void invalidExpiredWrongAppAndLimitReject(int error) {response.addProperty("errcode",error);assertThrows(ResidentProfileException.class,()->gateway.verify("phone-code"));}
    @ParameterizedTest @ValueSource(strings={"wrong-app","old-watermark","future-watermark","bad-number","missing-info"})
    void untrustedPhoneResponseRejects(String fault) {
        var info=response.getAsJsonObject("phone_info");var watermark=info.getAsJsonObject("watermark");
        switch(fault){case "wrong-app"->watermark.addProperty("appid","wx0000000000000002");case "old-watermark"->watermark.addProperty("timestamp",clock.instant().getEpochSecond()-301);case "future-watermark"->watermark.addProperty("timestamp",clock.instant().getEpochSecond()+31);case "bad-number"->info.addProperty("purePhoneNumber","not-a-phone");default->response.remove("phone_info");}
        assertThrows(ResidentProfileException.class,()->gateway.verify("code"));
    }
    @Test void disabledConfigurationAndCancelledInputNeverReachTransport() {props.setPhoneEnabled(false);assertThrows(ResidentProfileException.class,()->gateway.verify("code"));assertThrows(ResidentProfileException.class,()->gateway.verify(""));assertEquals(0,tokenCalls.get());}
    @Test void upstreamExceptionNeverRetainsSecrets() {
        var bad=new OfficialWechatPhoneGateway(apps,wechat,props,(url,body)->{throw new IllegalStateException(url+body);},clock);
        var error=assertThrows(ResidentProfileException.class,()->bad.verify("synthetic-private-code"));assertNull(error.getCause());assertFalse(error.toString().contains(secret));assertFalse(error.toString().contains("synthetic-private-code"));
    }
}
