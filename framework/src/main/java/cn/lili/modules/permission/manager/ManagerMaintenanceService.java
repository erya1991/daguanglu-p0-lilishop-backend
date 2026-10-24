package cn.lili.modules.permission.manager;

import cn.lili.common.security.session.SessionException;
import cn.lili.modules.permission.entity.dos.*;
import cn.lili.modules.permission.mapper.*;
import cn.lili.modules.permission.service.RoleMenuService;
import cn.lili.modules.permission.service.UserRoleService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

import static cn.lili.common.security.session.SessionException.Code.DENIED;
import static cn.lili.modules.permission.manager.ManagerAccessPolicy.*;

/** Only the three approved W01 writes, rechecked under existing-table transaction locks. */
@Service
@RequiredArgsConstructor
public class ManagerMaintenanceService {
    private final ManagerAccessSettings settings;
    private final ManagerAccessPolicy policy;
    private final AdminUserMapper admins;
    private final UserRoleMapper userRoleMapper;
    private final DepartmentRoleMapper departmentRoleMapper;
    private final RoleMenuMapper roleMenuMapper;
    private final UserRoleService userRoles;
    private final RoleMenuService roleMenus;

    @Transactional(rollbackFor = Exception.class)
    public void setEnabled(String operatorId, String targetId, boolean enabled) {
        Context actor = policy.current(operatorId, true);
        policy.requireRoute(actor, "PUT", "/manager/passport/user/enable/" + targetId);
        AdminUser target = policy.target(operatorId, targetId, true);
        if (target.getStatus() == enabled) return; // Already at the authoritative desired state.
        UpdateWrapper<AdminUser> update = new UpdateWrapper<AdminUser>().eq("id", targetId)
                .eq("status", target.getStatus()).set("status", enabled);
        require(admins.update(null, update) == 1);
        // Both Session and compatible legacy paths re-read current DB status before use.
    }

    @Transactional(rollbackFor = Exception.class)
    public void replaceUserRoles(String operatorId, String targetId, List<String> roleIds) {
        Context actor = policy.current(operatorId, true);
        policy.requireRoute(actor, "PUT", "/manager/permission/userRole/" + targetId);
        AdminUser target = policy.target(operatorId, targetId, true);
        List<String> requested = approvedIds(roleIds, settings.getRoleAIds());
        // This endpoint changes direct grants only; do not pretend inherited grants were revoked.
        require(policy.inheritedRoles(target, true).isEmpty());
        requested.stream().sorted().forEach(id -> policy.validateReadRole(id, true));
        List<UserRole> current = policy.directRoles(targetId, true);
        Set<String> previous = new HashSet<>();
        current.forEach(row -> {
            require(previous.add(row.getRoleId()) && settings.getRoleAIds().contains(row.getRoleId()));
            policy.validateReadRole(row.getRoleId(), true);
        });
        String redundantIds = String.join(",", requested);
        if (previous.equals(new HashSet<>(requested)) && Objects.equals(target.getRoleIds(), redundantIds)) return;
        userRoles.updateUserRole(targetId, requested.stream().map(id -> new UserRole(targetId, id)).toList());
        require(admins.update(null, new UpdateWrapper<AdminUser>().eq("id", targetId)
                .set("role_ids", redundantIds)) == 1);
    }

    @Transactional(rollbackFor = Exception.class)
    public void replaceRoleMenus(String operatorId, String roleId, List<String> menuIds) {
        Context actor = policy.current(operatorId, true);
        policy.requireRoute(actor, "POST", "/manager/permission/roleMenu/" + roleId);
        require(settings.getRoleAIds().contains(roleId) && !actor.roleIds().contains(roleId));
        policy.validRole(roleId, true);
        assertNoBReference(roleId);
        List<String> requested = approvedIds(menuIds, settings.getReadMenuIds());
        settings.getReadMenuIds().stream().sorted()
                .forEach(id -> policy.validateMenuDefinition(policy.validMenu(id, true), true));
        // Structural tree nodes are not new grant capabilities.
        for (String id : requested) require(text(policy.validMenu(id, true).getPermission()));
        List<RoleMenu> previous = list(roleMenuMapper.selectList(query("role_id", roleId, true)));
        Set<String> seen = new HashSet<>();
        Set<String> replacementIds = new LinkedHashSet<>();
        for (RoleMenu row : previous) {
            policy.validateAExistingGrant(roleId, row, true);
            require(seen.add(row.getMenuId()));
            // The UI submits API leaves only. Keep already-approved navigation structure,
            // while never allowing a client to create a new structural relation.
            if (!text(policy.validMenu(row.getMenuId(), true).getPermission())) {
                replacementIds.add(row.getMenuId());
            }
        }
        replacementIds.addAll(requested);
        List<RoleMenu> replacements = replacementIds.stream().map(id -> {
            RoleMenu row = new RoleMenu(); row.setRoleId(roleId); row.setMenuId(id); row.setIsSuper(false); return row;
        }).toList();
        // No Menu definition is edited, so a shared node cannot gain new global semantics.
        roleMenus.updateRoleMenu(roleId, replacements);
    }

    private void assertNoBReference(String roleId) {
        Set<String> bUsers = usersReferencing(settings.getRoleBIds());
        Set<String> aUsers = usersReferencing(Set.of(roleId));
        require(Collections.disjoint(bUsers, aUsers));
    }

    private Set<String> usersReferencing(Set<String> roleIds) {
        require(!roleIds.isEmpty());
        Set<String> result = new HashSet<>();
        List<UserRole> direct = list(userRoleMapper.selectList(new QueryWrapper<UserRole>()
                .in("role_id", roleIds).last("FOR UPDATE")));
        for (UserRole row : direct) {
            require(row != null && roleIds.contains(row.getRoleId()) && text(row.getUserId()));
            result.add(row.getUserId());
        }
        Set<String> departments = new HashSet<>();
        List<DepartmentRole> inherited = list(departmentRoleMapper.selectList(new QueryWrapper<DepartmentRole>()
                .in("role_id", roleIds).last("FOR UPDATE")));
        for (DepartmentRole row : inherited) {
            require(row != null && roleIds.contains(row.getRoleId()) && text(row.getDepartmentId())
                    && Boolean.FALSE.equals(row.getDeleteFlag()));
            departments.add(row.getDepartmentId());
        }
        if (!departments.isEmpty()) {
            for (AdminUser user : list(admins.selectList(new QueryWrapper<AdminUser>()
                    .in("department_id", departments).last("FOR UPDATE")))) {
                require(user != null && text(user.getId()) && departments.contains(user.getDepartmentId()));
                result.add(user.getId());
            }
        }
        return result;
    }

    private static List<String> approvedIds(List<String> values, Set<String> allowed) {
        require(values != null && values.stream().allMatch(id -> id(id) && allowed.contains(id))
                && new HashSet<>(values).size() == values.size());
        return List.copyOf(values);
    }
}
