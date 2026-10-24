package cn.lili.modules.permission.manager;

import cn.lili.common.security.session.SessionException;
import cn.lili.modules.permission.entity.dos.*;
import cn.lili.modules.permission.entity.vo.AdminUserVO;
import cn.lili.modules.permission.mapper.*;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.*;

import static cn.lili.common.security.session.SessionException.Code.AUTHORIZATION_REQUIRED;
import static cn.lili.common.security.session.SessionException.Code.DENIED;

/** W01's fixed method/URI contract plus current, explicit ID-scoped database grants. */
@Component
@RequiredArgsConstructor
public class ManagerAccessPolicy {
    private final ManagerAccessSettings settings;
    private final AdminUserMapper admins;
    private final UserRoleMapper userRoles;
    private final DepartmentRoleMapper departmentRoles;
    private final RoleMapper roles;
    private final RoleMenuMapper roleMenus;
    private final MenuMapper menus;

    private static final Set<String> PREP_READ = Set.of(
            "/manager/goods/goods/list", "/manager/goods/goods/sku/list",
            "/manager/goods/goods/goodsNumber", "/manager/goods/goods/get/{id}",
            "/manager/store/store", "/manager/store/store/all",
            "/manager/store/store/get/detail/{storeId}");
    private static final Set<String> SELF_READ = Set.of(
            "/manager/passport/user/info", "/manager/permission/menu/memberMenu");
    private static final Map<String, String> MAINTENANCE = Map.ofEntries(
            Map.entry("GET /manager/passport/user/getByCondition", "USER_READ"),
            Map.entry("GET /manager/permission/role", "ROLE_READ"),
            Map.entry("GET /manager/permission/userRole/{userId}", "USER_ROLE_READ"),
            Map.entry("GET /manager/permission/roleMenu/{roleId}", "ROLE_MENU_READ"),
            Map.entry("GET /manager/permission/menu/tree", "MENU_READ"),
            Map.entry("PUT /manager/passport/user/enable/{userId}", "USER_STATUS_WRITE"),
            Map.entry("PUT /manager/permission/userRole/{userId}", "USER_ROLE_WRITE"),
            Map.entry("POST /manager/permission/roleMenu/{roleId}", "ROLE_MENU_WRITE"));

    public enum Kind { A, B }
    public record Context(Kind kind, AdminUser admin, Set<String> roleIds, List<Menu> menus,
                          List<RoleMenu> grants) { }

    public boolean enabled() { return settings.isEnabled(); }

    public Context current(String adminId) { return current(adminId, false); }

    Context current(String adminId, boolean lock) {
        configured();
        AdminUser admin = account(adminId, lock, true);
        Set<String> ids = effectiveRoles(admin, lock);
        require(!ids.isEmpty());
        Kind kind;
        if (settings.getRoleAIds().containsAll(ids)) kind = Kind.A;
        else if (settings.getRoleBIds().containsAll(ids)) kind = Kind.B;
        else throw new SessionException(DENIED); // No mixed A/B or extra inherited role.
        List<RoleMenu> grants = new ArrayList<>();
        Map<String, Menu> visible = new LinkedHashMap<>();
        Set<String> allowed = new LinkedHashSet<>(settings.getReadMenuIds());
        if (kind == Kind.B) allowed.addAll(settings.getMaintenanceMenuIds());
        for (String roleId : ids) {
            validRole(roleId, lock);
            List<RoleMenu> rows = list(roleMenus.selectList(query("role_id", roleId, lock)));
            Set<String> seen = new HashSet<>();
            for (RoleMenu row : rows) {
                require(row != null && roleId.equals(row.getRoleId()) && Boolean.FALSE.equals(row.getDeleteFlag())
                        && row.getIsSuper() != null && allowed.contains(row.getMenuId()) && seen.add(row.getMenuId()));
                if (kind == Kind.A) require(Boolean.FALSE.equals(row.getIsSuper()));
                Menu menu = validMenu(row.getMenuId(), lock);
                validateMenuDefinition(menu, settings.getReadMenuIds().contains(menu.getId()));
                grants.add(row);
                visible.put(menu.getId(), menu);
            }
        }
        return new Context(kind, admin, Set.copyOf(ids), List.copyOf(visible.values()), List.copyOf(grants));
    }

    /** Scope detection is for denying legacy A/B bypasses, not for granting any permission. */
    public boolean isConfiguredRoleMember(String adminId) {
        if (!enabled()) return false;
        require(text(adminId));
        AdminUser admin = admins.selectOne(query("id", adminId, false));
        require(admin != null && adminId.equals(admin.getId()));
        if (Boolean.TRUE.equals(admin.getIsSuper())) return false; // Historical super handling stays outside A/B.
        require(Boolean.FALSE.equals(admin.getIsSuper()) && Boolean.FALSE.equals(admin.getDeleteFlag()));
        Set<String> ids = effectiveRoles(admin, false);
        return ids.stream().anyMatch(id -> settings.getRoleAIds().contains(id) || settings.getRoleBIds().contains(id));
    }

    /** Route shape only: caller must still verify Session, live grants, and managed target. */
    public static boolean allowedRoute(String method, String path) {
        if (method == null || !safePath(path)) return false;
        if (method.equals("GET") && (SELF_READ.contains(path)
                || PREP_READ.stream().anyMatch(template -> matches(template, path)))) return true;
        if (method.equals("POST") && (path.equals("/manager/passport/user/refresh")
                || path.equals("/manager/passport/user/logout"))) return true;
        return MAINTENANCE.keySet().stream().anyMatch(route -> matchesRoute(route, method, path));
    }

    public void requireRoute(String adminId, String method, String path) {
        requireRoute(current(adminId), method, path);
    }

    void requireRoute(Context context, String method, String path) {
        require(method != null && safePath(path));
        if (method.equals("POST") && path.equals("/manager/passport/user/logout")) return;
        if (method.equals("GET") && SELF_READ.contains(path)) return;
        if (method.equals("GET") && PREP_READ.stream().anyMatch(template -> matches(template, path))) {
            require(hasGrant(context, method, path, settings.getReadMenuIds()));
            return;
        }
        require(context.kind() == Kind.B && MAINTENANCE.keySet().stream()
                .anyMatch(route -> matchesRoute(route, method, path))
                && hasGrant(context, method, path, settings.getMaintenanceMenuIds()));
    }

    public Map<String, Object> describe(String adminId) {
        Context context = current(adminId);
        Set<String> caps = new LinkedHashSet<>(List.of("SELF_READ", "MENU_SELF_READ"));
        if (PREP_READ.stream().anyMatch(path -> hasGrant(context, "GET", path, settings.getReadMenuIds()))) caps.add("PREP_READ");
        if (context.kind() == Kind.B) MAINTENANCE.forEach((route, capability) -> {
            String[] split = route.split(" ", 2);
            if (hasGrant(context, split[0], split[1], settings.getMaintenanceMenuIds())) caps.add(capability);
        });
        Map<String, Object> description = new LinkedHashMap<>();
        description.put("role", context.kind().name());
        description.put("capabilities", List.copyOf(caps));
        description.put("manageableUserIds", context.kind() == Kind.B ? Set.copyOf(settings.getManageableUserIds()) : Set.of());
        description.put("roleAIds", context.kind() == Kind.B ? Set.copyOf(settings.getRoleAIds()) : Set.of());
        description.put("readMenuIds", Set.copyOf(settings.getReadMenuIds()));
        description.put("maintenanceMenuIds", context.kind() == Kind.B ? Set.copyOf(settings.getMaintenanceMenuIds()) : Set.of());
        return description;
    }

    public List<AdminUserVO> users(String operatorId) {
        requireRoute(operatorId, "GET", "/manager/passport/user/getByCondition");
        List<AdminUserVO> result = new ArrayList<>();
        for (String id : settings.getManageableUserIds()) {
            AdminUser target = target(operatorId, id, false);
            AdminUserVO view = new AdminUserVO(target);
            // Scope readback includes only the approved account identifier/state/display fields.
            view.setMobile(null); view.setEmail(null); view.setDescription(null);
            view.setDepartmentId(null); view.setRoleIds(null); view.setCreateBy(null); view.setUpdateBy(null);
            result.add(view);
        }
        return result;
    }

    public List<Role> roles(String operatorId) {
        requireRoute(operatorId, "GET", "/manager/permission/role");
        return settings.getRoleAIds().stream().map(id -> {
            Role source = validateReadRole(id, false);
            Role view = new Role(); view.setId(id); view.setName(source.getName());
            view.setDescription(source.getDescription()); return view;
        }).toList();
    }

    public List<UserRole> userRoles(String operatorId, String targetId) {
        requireRoute(operatorId, "GET", "/manager/permission/userRole/" + targetId);
        target(operatorId, targetId, false);
        return directRoles(targetId, false).stream().map(row -> new UserRole(targetId, row.getRoleId())).toList();
    }

    public List<RoleMenu> roleMenus(String operatorId, String roleId) {
        requireRoute(operatorId, "GET", "/manager/permission/roleMenu/" + roleId);
        require(settings.getRoleAIds().contains(roleId));
        validRole(roleId, false);
        List<RoleMenu> rows = validateAExistingGrants(roleId, false);
        return rows.stream().map(row -> {
            RoleMenu view = new RoleMenu(); view.setRoleId(roleId); view.setMenuId(row.getMenuId()); view.setIsSuper(false); return view;
        }).toList();
    }

    public List<Menu> menuTreeNodes(String operatorId) {
        requireRoute(operatorId, "GET", "/manager/permission/menu/tree");
        return settings.getReadMenuIds().stream().map(id -> {
            Menu menu = validMenu(id, false); validateMenuDefinition(menu, true); return menu;
        }).toList();
    }

    AdminUser target(String operatorId, String targetId, boolean lock) {
        require(id(targetId) && !targetId.equals(operatorId) && settings.getManageableUserIds().contains(targetId));
        AdminUser target = account(targetId, lock, false);
        Set<String> ids = effectiveRoles(target, lock);
        require(settings.getRoleAIds().containsAll(ids)); // B, unknown and extra roles cannot be overwritten.
        for (String id : ids) validRole(id, lock);
        return target;
    }

    AdminUser account(String id, boolean lock, boolean active) {
        require(id(id));
        AdminUser account = admins.selectOne(query("id", id, lock));
        require(account != null && id.equals(account.getId()) && text(account.getUsername())
                && Boolean.FALSE.equals(account.getDeleteFlag()) && Boolean.FALSE.equals(account.getIsSuper())
                && account.getStatus() != null && (!active || Boolean.TRUE.equals(account.getStatus())));
        return account;
    }

    Set<String> effectiveRoles(AdminUser admin, boolean lock) {
        Set<String> ids = new LinkedHashSet<>();
        for (UserRole row : directRoles(admin.getId(), lock)) require(ids.add(row.getRoleId()));
        for (DepartmentRole row : inheritedRoles(admin, lock)) {
            require(row != null && admin.getDepartmentId().equals(row.getDepartmentId())
                    && text(row.getRoleId()) && Boolean.FALSE.equals(row.getDeleteFlag()));
            ids.add(row.getRoleId());
        }
        return ids;
    }

    List<UserRole> directRoles(String userId, boolean lock) {
        List<UserRole> rows = list(userRoles.selectList(query("user_id", userId, lock)));
        for (UserRole row : rows) require(row != null && userId.equals(row.getUserId()) && text(row.getRoleId()));
        return rows;
    }

    List<DepartmentRole> inheritedRoles(AdminUser admin, boolean lock) {
        return text(admin.getDepartmentId()) ? list(departmentRoles.selectList(query("department_id", admin.getDepartmentId(), lock))) : List.of();
    }

    Role validRole(String id, boolean lock) {
        Role role = roles.selectOne(query("id", id, lock));
        require(role != null && id.equals(role.getId()) && Boolean.FALSE.equals(role.getDeleteFlag()));
        return role;
    }

    Role validateReadRole(String id, boolean lock) {
        require(settings.getRoleAIds().contains(id));
        Role role = validRole(id, lock);
        validateAExistingGrants(id, lock);
        return role;
    }

    Menu validMenu(String id, boolean lock) {
        Menu menu = menus.selectOne(query("id", id, lock));
        require(menu != null && id.equals(menu.getId()) && Boolean.FALSE.equals(menu.getDeleteFlag()));
        return menu;
    }

    void validateAExistingGrant(String roleId, RoleMenu row, boolean lock) {
        require(row != null && roleId.equals(row.getRoleId()) && Boolean.FALSE.equals(row.getDeleteFlag())
                && Boolean.FALSE.equals(row.getIsSuper()) && settings.getReadMenuIds().contains(row.getMenuId()));
        validateMenuDefinition(validMenu(row.getMenuId(), lock), true);
    }

    private List<RoleMenu> validateAExistingGrants(String roleId, boolean lock) {
        List<RoleMenu> rows = list(roleMenus.selectList(query("role_id", roleId, lock)));
        Set<String> seen = new HashSet<>();
        for (RoleMenu row : rows) {
            validateAExistingGrant(roleId, row, lock);
            require(seen.add(row.getMenuId()));
        }
        return rows;
    }

    void validateMenuDefinition(Menu menu, boolean readOnly) {
        if (menu.getPermission() == null || menu.getPermission().isBlank()) return; // Structural node, never grants an API.
        for (String path : menu.getPermission().split(",", -1)) {
            require(path.equals(path.trim()) && !path.isBlank() && safeTemplate(path));
            require(readOnly ? PREP_READ.contains(path) || SELF_READ.contains(path)
                    : MAINTENANCE.keySet().stream().anyMatch(route -> route.endsWith(" " + path)));
        }
    }

    private boolean hasGrant(Context context, String method, String path, Set<String> allowedIds) {
        for (RoleMenu row : context.grants()) {
            if (!allowedIds.contains(row.getMenuId()) || (!method.equals("GET") && !Boolean.TRUE.equals(row.getIsSuper()))) continue;
            Menu menu = context.menus().stream().filter(item -> item.getId().equals(row.getMenuId())).findFirst().orElse(null);
            if (menu != null && menu.getPermission() != null) {
                for (String template : menu.getPermission().split(",", -1)) {
                    // describe() compares validated fixed templates; actual HTTP paths first
                    // pass requireRoute/allowedRoute, whose dynamic segments remain strict IDs.
                    if (template.equals(path) || matches(template, path)) return true;
                }
            }
        }
        return false;
    }

    private void configured() {
        if (!enabled() || settings.getRoleAIds().isEmpty() || settings.getRoleBIds().isEmpty()
                || settings.getReadMenuIds().isEmpty()) throw new SessionException(AUTHORIZATION_REQUIRED);
        for (Set<String> values : List.of(settings.getRoleAIds(), settings.getRoleBIds(), settings.getManageableUserIds(),
                settings.getReadMenuIds(), settings.getMaintenanceMenuIds())) require(values.stream().allMatch(ManagerAccessPolicy::id));
        require(Collections.disjoint(settings.getRoleAIds(), settings.getRoleBIds())
                && Collections.disjoint(settings.getReadMenuIds(), settings.getMaintenanceMenuIds()));
    }

    public static boolean safePath(String path) {
        return path != null && path.startsWith("/manager/") && !path.contains("//") && !path.contains("..")
                && path.chars().noneMatch(c -> c <= 32 || c == 127 || "%\\;?*#".indexOf(c) >= 0);
    }
    private static boolean safeTemplate(String path) { return safePath(path); }
    private static boolean matchesRoute(String route, String method, String path) {
        String[] parts = route.split(" ", 2); return parts[0].equals(method) && matches(parts[1], path);
    }
    public static boolean matches(String template, String path) {
        if (!safePath(path) || !safeTemplate(template)) return false;
        String[] expected = template.split("/", -1), actual = path.split("/", -1);
        if (expected.length != actual.length) return false;
        for (int i = 0; i < expected.length; i++) {
            if (expected[i].matches("\\{[A-Za-z][A-Za-z0-9]*}")) { if (!id(actual[i])) return false; }
            else if (!expected[i].equals(actual[i])) return false;
        }
        return true;
    }
    static <T> QueryWrapper<T> query(String field, Object value, boolean lock) {
        QueryWrapper<T> query = new QueryWrapper<T>().eq(field, value);
        return lock ? query.last("FOR UPDATE") : query;
    }
    static <T> List<T> list(List<T> value) { require(value != null); return value; }
    static boolean id(String value) { return value != null && value.matches("[A-Za-z0-9_-]{1,128}"); }
    static boolean text(String value) { return value != null && !value.isBlank() && value.equals(value.trim()); }
    static void require(boolean condition) { if (!condition) throw new SessionException(DENIED); }
}
