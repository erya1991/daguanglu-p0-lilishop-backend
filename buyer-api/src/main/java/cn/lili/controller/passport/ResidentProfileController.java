package cn.lili.controller.passport;

import cn.lili.common.security.session.*;
import cn.lili.common.security.session.http.*;
import cn.lili.common.utils.ResponseUtil;
import cn.lili.modules.connect.wechat.MemberLinkService;
import cn.lili.modules.member.profile.*;
import com.google.gson.*;
import jakarta.servlet.http.*;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;
import static cn.lili.modules.member.profile.ResidentProfileException.Code.*;

@RestController
public class ResidentProfileController {
    private final ResidentProfileService profiles;
    private final ResidentAvatarService avatars;
    private final MemberLinkService links;
    public ResidentProfileController(ResidentProfileService profiles,ResidentAvatarService avatars,MemberLinkService links) {
        this.profiles=profiles;this.avatars=avatars;this.links=links;
    }
    // Existing MemberBuyerController owns these two method/URI mappings.
    public void read(HttpServletRequest request,HttpServletResponse response) {
        execute(response,() -> { noSelectors(request); return profiles.read(current().identity()); });
    }
    public void edit(HttpServletRequest request,HttpServletResponse response) {
        execute(response,() -> {
            var session=current(); var body=body(request,Set.of("nickname","avatarFileId","version"));
            return profiles.edit(session.identity(),version(body),optional(body,"nickname"),optional(body,"avatarFileId"));
        });
    }
    @PostMapping(value=SessionRoutes.RESIDENT_PHONE,consumes="application/json")
    public void phone(HttpServletRequest request,HttpServletResponse response) {
        execute(response,() -> {
            var session=current(); var body=body(request,Set.of("phoneCode","requestId","version"));
            return profiles.phone(session.identity(),version(body),string(body,"requestId"),string(body,"phoneCode"));
        });
    }
    @PostMapping(value=SessionRoutes.RESIDENT_LINK,consumes="application/json")
    public void link(HttpServletRequest request,HttpServletResponse response) {
        execute(response,() -> {
            var session=current(); var body=body(request,Set.of("requestId","proofReference","consentVersion","confirmed"));
            boolean confirmed=body.has("confirmed") && body.get("confirmed").isJsonPrimitive()
                    && body.getAsJsonPrimitive("confirmed").isBoolean() && body.get("confirmed").getAsBoolean();
            return links.link(session,string(body,"requestId"),string(body,"proofReference"),string(body,"consentVersion"),confirmed);
        });
    }
    @PostMapping(value=SessionRoutes.RESIDENT_AVATAR,consumes="multipart/form-data")
    public void avatar(HttpServletRequest request,HttpServletResponse response) {
        execute(response,() -> {
            var session=current(); noSelectors(request);
            var multipart=org.springframework.web.util.WebUtils.getNativeRequest(request,MultipartHttpServletRequest.class);
            if(multipart==null || multipart.getMultiFileMap().size()!=1
                    || multipart.getFiles("file").size()!=1) throw new ResidentProfileException(INVALID_REQUEST);
            return avatars.upload(session.identity(),multipart.getFile("file"));
        });
    }
    private static SessionService.VerifiedSession current() {
        var authentication=SecurityContextHolder.getContext().getAuthentication();
        if(authentication==null || !authentication.isAuthenticated()
                || !(authentication.getDetails() instanceof SessionHttpFilter.Principal p)
                || p.session().identity().domain()!=SessionDomain.RESIDENT) throw new ResidentProfileException(SESSION_REQUIRED);
        return ((SessionHttpFilter.Principal)authentication.getDetails()).session();
    }
    static void noSelectors(HttpServletRequest request) {
        if(request.getQueryString()!=null || !request.getParameterMap().isEmpty()) throw new ResidentProfileException(INVALID_REQUEST);
        for(String key:List.of("memberId","userId","username","appId","openId","storeId","clerkId","role","isSuper")) {
            if(request.getHeader(key)!=null) throw new ResidentProfileException(INVALID_REQUEST);
        }
    }
    private static JsonObject body(HttpServletRequest request,Set<String> fields) {
        try {
            noSelectors(request);
            if(request.getContentType()==null || !request.getContentType().toLowerCase(Locale.ROOT).startsWith("application/json")) throw new ResidentProfileException(INVALID_REQUEST);
            byte[] bytes=request.getInputStream().readNBytes(4097); if(bytes.length>4096) throw new ResidentProfileException(INVALID_REQUEST);
            var object=JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
            if(!fields.containsAll(object.keySet())) throw new ResidentProfileException(INVALID_REQUEST);
            return object;
        } catch(Exception e) {throw new ResidentProfileException(INVALID_REQUEST);}
    }
    private static String optional(JsonObject body,String field) {return body.has(field)?string(body,field):null;}
    private static String string(JsonObject body,String field) {
        if(!body.has(field) || !body.get(field).isJsonPrimitive() || !body.getAsJsonPrimitive(field).isString()) throw new ResidentProfileException(INVALID_REQUEST);
        return body.get(field).getAsString();
    }
    private static long version(JsonObject body) {
        if(!body.has("version") || !body.get("version").isJsonPrimitive() || !body.getAsJsonPrimitive("version").isNumber()
                || !body.get("version").getAsString().matches("[0-9]{1,18}")) throw new ResidentProfileException(INVALID_REQUEST);
        return body.get("version").getAsLong();
    }
    private static void execute(HttpServletResponse response,Supplier<Object> work) {
        response.setHeader("Cache-Control","no-store");response.setHeader("Pragma","no-cache");
        try { ResponseUtil.output(response,200,ResponseUtil.resultMap(true,200,"SUCCESS",work.get())); }
        catch(ResidentProfileException e) {
            int status=switch(e.getCode()) {
                case INVALID_REQUEST,NICKNAME_REJECTED,AVATAR_REJECTED,CONSENT_REQUIRED,PHONE_REJECTED -> 400;
                case SESSION_REQUIRED,IDENTITY_UNAVAILABLE,PROOF_REJECTED -> 403;
                case PROFILE_CONFLICT,REQUEST_CONFLICT,LINK_REQUIRED,HISTORY_CONFLICT -> 409;
                default -> 503;
            };
            ResponseUtil.output(response,status,ResponseUtil.resultMap(false,status,e.getCode().name()));
        } catch(Exception e) { ResponseUtil.output(response,503,ResponseUtil.resultMap(false,503,"OPERATION_UNAVAILABLE")); }
    }
}
