package cn.lili.modules.member.profile;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.common.security.session.http.SessionApplications;
import cn.lili.modules.connect.wechat.ResidentWechatProperties;
import com.google.gson.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import static cn.lili.modules.member.profile.ResidentProfileException.Code.*;

/** Fixed official endpoints; no log, redirect, automatic retry or production mock. */
@Component
public final class OfficialWechatPhoneGateway implements WechatPhoneGateway {
    private final SessionApplications apps;
    private final ResidentWechatProperties wechat;
    private final ResidentProfileProperties settings;
    private final Exchange exchange;
    private final Clock clock;
    private String cachedApp, cachedToken;
    private long tokenDeadline;
    @Autowired
    public OfficialWechatPhoneGateway(SessionApplications apps,ResidentWechatProperties wechat,ResidentProfileProperties settings) {
        this(apps,wechat,settings,OfficialWechatPhoneGateway::post,Clock.systemUTC());
    }
    OfficialWechatPhoneGateway(SessionApplications apps,ResidentWechatProperties wechat,ResidentProfileProperties settings,Exchange exchange,Clock clock) {
        this.apps=apps; this.wechat=wechat; this.settings=settings; this.exchange=exchange; this.clock=clock;
    }
    @Override public String verify(String phoneCode) {
        ResidentProfileService.code(phoneCode); settings.requireSchema();
        if(!settings.isPhoneEnabled()) throw new ResidentProfileException(PHONE_PENDING);
        try {
            String app=apps.application(SessionDomain.RESIDENT), secret=wechat.requireSecret();
            if(!app.matches("wx[0-9a-fA-F]{16}")) throw new ResidentProfileException(PHONE_PENDING);
            String token=token(app,secret);
            JsonObject payload=new JsonObject(); payload.addProperty("code",phoneCode);
            JsonObject response=json(exchange.post("https://api.weixin.qq.com/wxa/business/getuserphonenumber?access_token="
                    +URLEncoder.encode(token,StandardCharsets.UTF_8),payload.toString()));
            success(response);
            JsonObject info=response.getAsJsonObject("phone_info"), watermark=info.getAsJsonObject("watermark");
            long timestamp=watermark.get("timestamp").getAsLong(), now=clock.instant().getEpochSecond();
            if(!app.equals(string(watermark,"appid")) || timestamp>now+30 || timestamp<now-300) throw new ResidentProfileException(PHONE_REJECTED);
            String country=string(info,"countryCode"), pure=string(info,"purePhoneNumber"), full=string(info,"phoneNumber");
            if(!country.matches("[1-9][0-9]{0,2}") || !pure.matches("[0-9]{5,14}") || country.length()+pure.length()>15) throw new ResidentProfileException(PHONE_REJECTED);
            if(!full.equals(pure) && !full.equals(country+pure) && !full.equals("+"+country+pure)) throw new ResidentProfileException(PHONE_REJECTED);
            if(country.equals("86")) {
                if(!pure.matches("1[0-9]{10}")) throw new ResidentProfileException(PHONE_REJECTED);
                return pure;
            }
            return "+"+country+pure;
        } catch(ResidentProfileException e) { throw new ResidentProfileException(e.getCode()); }
        catch(Exception e) { throw new ResidentProfileException(PHONE_UNAVAILABLE); }
    }
    private synchronized String token(String app,String secret) throws Exception {
        if(app.equals(cachedApp) && cachedToken!=null && clock.millis()<tokenDeadline) return cachedToken;
        JsonObject body=new JsonObject(); body.addProperty("grant_type","client_credential");
        body.addProperty("appid",app); body.addProperty("secret",secret); body.addProperty("force_refresh",false);
        JsonObject response=json(exchange.post("https://api.weixin.qq.com/cgi-bin/stable_token",body.toString()));
        if(response.has("errcode")) success(response);
        String token=string(response,"access_token"); long expires=response.get("expires_in").getAsLong();
        if(token.isBlank() || token.length()>4096 || expires<=60 || expires>7200) throw new ResidentProfileException(PHONE_UNAVAILABLE);
        cachedApp=app; cachedToken=token; tokenDeadline=clock.millis()+(expires-60)*1000;
        return token;
    }
    private static void success(JsonObject json) {
        if(!json.has("errcode") || !json.get("errcode").isJsonPrimitive()
                || !json.getAsJsonPrimitive("errcode").isNumber() || !"0".equals(json.get("errcode").getAsString())) throw new ResidentProfileException(PHONE_REJECTED);
    }
    private static String string(JsonObject json,String field) {
        if(json==null || !json.has(field) || !json.get(field).isJsonPrimitive() || !json.getAsJsonPrimitive(field).isString()) throw new ResidentProfileException(PHONE_REJECTED);
        return json.get(field).getAsString();
    }
    private static JsonObject json(String body) {
        if(body==null || body.length()>8192) throw new ResidentProfileException(PHONE_UNAVAILABLE);
        return JsonParser.parseString(body).getAsJsonObject();
    }
    private static String post(String url,String body) throws Exception {
        var connection=(HttpURLConnection)URI.create(url).toURL().openConnection();
        try {
            connection.setInstanceFollowRedirects(false); connection.setUseCaches(false);
            connection.setConnectTimeout(3000); connection.setReadTimeout(5000); connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type","application/json; charset=utf-8"); connection.setDoOutput(true);
            byte[] payload=body.getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(payload.length);
            try(var output=connection.getOutputStream()) { output.write(payload); }
            if(connection.getResponseCode()!=200) throw new ResidentProfileException(PHONE_UNAVAILABLE);
            try(var input=connection.getInputStream()) {
                byte[] bytes=input.readNBytes(8193); if(bytes.length>8192) throw new ResidentProfileException(PHONE_UNAVAILABLE);
                return new String(bytes,StandardCharsets.UTF_8);
            }
        } finally {connection.disconnect();}
    }
    @FunctionalInterface interface Exchange { String post(String url,String body) throws Exception; }
}
