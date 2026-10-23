package cn.lili.controller.passport.connect;

import cn.lili.common.security.session.SessionException;
import cn.lili.common.security.session.http.SessionRoutes;
import cn.lili.common.utils.ResponseUtil;
import cn.lili.modules.connect.wechat.ResidentLoginException;
import cn.lili.modules.connect.wechat.ResidentWechatLoginService;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static cn.lili.modules.connect.wechat.ResidentLoginException.Code.*;

/** Strict body contract avoids DTO unknown-field ignoring and validation-error credential echoes. */
@RestController
public final class ResidentWechatLoginController {
    private final ResidentWechatLoginService login;
    public ResidentWechatLoginController(ResidentWechatLoginService login) { this.login = login; }

    @PostMapping(value = SessionRoutes.RESIDENT_LOGIN, consumes = "application/json")
    public void login(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store"); response.setHeader("Pragma", "no-cache");
        try {
            if (!request.getParameterMap().isEmpty()) throw new ResidentLoginException(INVALID_REQUEST);
            for (String selector : List.of("appId", "openId", "memberId", "userId", "storeId", "clerkId", "role", "isSuper")) {
                if (request.getHeader(selector) != null) throw new ResidentLoginException(INVALID_REQUEST);
            }
            byte[] bytes = request.getInputStream().readNBytes(4097);
            if (bytes.length > 4096) throw new ResidentLoginException(INVALID_REQUEST);
            var body = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
            for (String field : List.of("phoneCode", "phoneNumberCode", "mobileCode", "mobile", "phone", "encryptedData", "iv")) {
                if (body.has(field)) throw new ResidentLoginException(PHONE_NOT_SUPPORTED);
            }
            if (body.size() != 1 || !body.has("loginCode") || !body.get("loginCode").isJsonPrimitive()
                    || !body.getAsJsonPrimitive("loginCode").isString()) throw new ResidentLoginException(INVALID_REQUEST);
            var result = login.login(body.get("loginCode").getAsString());
            ResponseUtil.output(response, 200, ResponseUtil.resultMap(true, 200, "SUCCESS", result));
        } catch (ResidentLoginException failure) {
            int status = switch (failure.getCode()) {
                case INVALID_REQUEST, PHONE_NOT_SUPPORTED -> 400;
                case WECHAT_REJECTED, IDENTITY_CONFLICT -> 403;
                default -> 503;
            };
            reject(response, status, failure.getCode().name());
        } catch (SessionException failure) {
            reject(response, 503, "SESSION_UNAVAILABLE");
        } catch (Exception ignored) { reject(response, 400, "INVALID_REQUEST"); }
    }
    private static void reject(HttpServletResponse response, int status, String code) {
        ResponseUtil.output(response, status, ResponseUtil.resultMap(false, status, code));
    }
}
