package cn.lili.controller.seller.passport;

import cn.lili.common.security.session.SessionException;
import cn.lili.common.security.session.http.SessionRoutes;
import cn.lili.common.utils.ResponseUtil;
import cn.lili.modules.connect.wechat.MerchantWechatLoginService;
import cn.lili.modules.connect.wechat.ResidentLoginException;
import cn.lili.modules.member.merchant.MerchantAccessException;
import com.google.gson.JsonParser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static cn.lili.modules.connect.wechat.ResidentLoginException.Code.INVALID_REQUEST;

/** Only a short-lived Code is accepted; identity and authority are always server-selected. */
@RestController
public final class MerchantWechatLoginController {
    private final MerchantWechatLoginService login;
    public MerchantWechatLoginController(MerchantWechatLoginService login) { this.login = login; }

    @PostMapping(value = SessionRoutes.MERCHANT_LOGIN, consumes = "application/json")
    public void login(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Pragma", "no-cache");
        try {
            if (!request.getParameterMap().isEmpty()) throw new ResidentLoginException(INVALID_REQUEST);
            for (String selector : List.of("appId", "openId", "memberId", "userId", "storeId", "clerkId", "role", "isSuper")) {
                if (request.getHeader(selector) != null) throw new ResidentLoginException(INVALID_REQUEST);
            }
            byte[] bytes = request.getInputStream().readNBytes(4097);
            if (bytes.length > 4096) throw new ResidentLoginException(INVALID_REQUEST);
            var body = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
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
        } catch (MerchantAccessException failure) {
            int status = switch (failure.getCode()) {
                case DATA_UNAVAILABLE, PROOF_SOURCE_PENDING -> 503;
                case RELATION_CONFLICT -> 409;
                case DENIED -> 403;
            };
            reject(response, status, failure.getCode().name());
        } catch (Exception ignored) {
            reject(response, 400, "INVALID_REQUEST");
        }
    }

    private static void reject(HttpServletResponse response, int status, String code) {
        ResponseUtil.output(response, status, ResponseUtil.resultMap(false, status, code));
    }
}
