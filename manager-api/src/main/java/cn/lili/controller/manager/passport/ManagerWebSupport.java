package cn.lili.controller.manager.passport;

import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.security.context.UserContext;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.common.security.session.SessionDomain;
import cn.lili.common.security.session.http.SessionHttpFilter;
import cn.lili.common.vo.PageVO;
import cn.lili.modules.permission.entity.dos.Menu;
import cn.lili.modules.permission.entity.vo.MenuVO;
import cn.lili.modules.permission.manager.*;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.google.gson.Gson;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.*;

/** Narrow MVC adapter for the approved W01 contract; authority always remains server-side. */
@Component
@RequiredArgsConstructor
public class ManagerWebSupport {
    public final ManagerAccessPolicy policy;
    public final ManagerMaintenanceService maintenance;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public cn.lili.modules.community.p0.support.Stage1ManagerNavigation stage1;

    @org.springframework.beans.factory.annotation.Autowired(required=false)
    public NativeManagerAccess nativeManager;
    public boolean scoped() { return active() && nativeManager==null; }

    public static boolean active() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null && auth.isAuthenticated()
                && auth.getDetails() instanceof SessionHttpFilter.Principal principal
                && principal.session().identity().domain() == SessionDomain.MANAGER;
    }
    public static boolean requestedSession() {
        var request = request();
        String protocol = request == null ? null : request.getHeader("X-Manager-Session-Protocol");
        if (protocol != null && !ManagerSessionResponse.PROTOCOL.equals(protocol)) deny();
        return protocol != null;
    }
    public String actor() {
        var user = UserContext.getCurrentUser();
        if (!active() || user == null || user.getRole() != UserEnums.MANAGER) deny();
        return user.getId();
    }
    public static HttpServletRequest request() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }
    public static void validateLogin() {
        var request = request();
        if (request == null || !request.getParameterMap().keySet().equals(Set.of("username", "password"))) deny();
        for (String key : List.of("username", "password"))
            if (request.getParameterValues(key).length != 1) deny();
        for (String key : List.of("userId", "role", "isSuper", "appId", "memberId", "storeId"))
            if (request.getHeader(key) != null) deny();
    }
    public static List<String> ids(Object payload, String key) {
        var json = new Gson().toJsonTree(payload);
        if (!json.isJsonObject() || json.getAsJsonObject().size() != 1
                || !json.getAsJsonObject().has(key) || !json.getAsJsonObject().get(key).isJsonArray()) deny();
        var values = json.getAsJsonObject().getAsJsonArray(key);
        if (values.size() > 512) deny();
        List<String> result = new ArrayList<>();
        for (var item : values) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) deny();
            String value = item.getAsString();
            if (!value.matches("[A-Za-z0-9_-]{1,128}") || result.contains(value)) deny();
            result.add(value);
        }
        var request = request();
        if (request != null && !request.getParameterMap().isEmpty()) deny();
        return result;
    }
    public static boolean status(Boolean bound) {
        var request = request();
        if (request == null || bound == null || request.getParameterMap().size() != 1
                || !request.getParameterMap().containsKey("status")) deny();
        String[] values = request.getParameterValues("status");
        if (values.length != 1 || !(values[0].equals("true") || values[0].equals("false"))
                || bound != Boolean.parseBoolean(values[0])) deny();
        try {
            if (request.getInputStream().readNBytes(1).length != 0) deny();
        } catch (java.io.IOException e) { deny(); }
        return bound;
    }
    public static <T> Page<T> page(List<T> rows, PageVO params) {
        if (rows == null || params == null || params.getPageNumber() == null || params.getPageSize() == null
                || params.getPageNumber() < 1 || params.getPageNumber() > 10000
                || params.getPageSize() < 1 || params.getPageSize() > 100) deny();
        int from = Math.min(rows.size(), (params.getPageNumber() - 1) * params.getPageSize());
        int to = Math.min(rows.size(), from + params.getPageSize());
        var page = new Page<T>(params.getPageNumber(), params.getPageSize(), rows.size());
        page.setRecords(rows.subList(from, to));
        return page;
    }
    public static List<MenuVO> tree(List<Menu> menus) {
        Map<String, MenuVO> nodes = new LinkedHashMap<>();
        for (Menu menu : menus) {
            if (menu == null || menu.getId() == null || nodes.containsKey(menu.getId())) deny();
            MenuVO node = new MenuVO(menu);
            node.setCreateBy(null); node.setUpdateBy(null);
            if (node.getSortOrder() == null) node.setSortOrder(0.0);
            nodes.put(menu.getId(), node);
        }
        List<MenuVO> roots = new ArrayList<>();
        for (MenuVO node : nodes.values()) {
            Set<String> chain = new HashSet<>();
            MenuVO cursor = node;
            while (cursor != null) {
                if (!chain.add(cursor.getId())) deny();
                cursor = nodes.get(cursor.getParentId());
            }
            MenuVO parent = nodes.get(node.getParentId());
            if (parent == null) roots.add(node); else parent.getChildren().add(node);
        }
        roots.sort(Comparator.comparing(Menu::getSortOrder));
        return roots;
    }
    private static void deny() { throw new ServiceException(ResultCode.USER_AUTHORITY_ERROR); }
}
