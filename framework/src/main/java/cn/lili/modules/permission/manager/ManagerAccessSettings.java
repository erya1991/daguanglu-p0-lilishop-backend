package cn.lili.modules.permission.manager;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/** Explicit owner-approved IDs; never seeded with synthetic or name-derived production IDs. */
@Component
@ConfigurationProperties(prefix = "lili.stage0-manager")
public class ManagerAccessSettings {
    private boolean enabled;
    private Set<String> roleAIds = new LinkedHashSet<>();
    private Set<String> roleBIds = new LinkedHashSet<>();
    private Set<String> manageableUserIds = new LinkedHashSet<>();
    private Set<String> readMenuIds = new LinkedHashSet<>();
    private Set<String> maintenanceMenuIds = new LinkedHashSet<>();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Set<String> getRoleAIds() { return roleAIds; }
    public void setRoleAIds(Set<String> values) { roleAIds = copy(values); }
    public Set<String> getRoleBIds() { return roleBIds; }
    public void setRoleBIds(Set<String> values) { roleBIds = copy(values); }
    public Set<String> getManageableUserIds() { return manageableUserIds; }
    public void setManageableUserIds(Set<String> values) { manageableUserIds = copy(values); }
    public Set<String> getReadMenuIds() { return readMenuIds; }
    public void setReadMenuIds(Set<String> values) { readMenuIds = copy(values); }
    public Set<String> getMaintenanceMenuIds() { return maintenanceMenuIds; }
    public void setMaintenanceMenuIds(Set<String> values) { maintenanceMenuIds = copy(values); }

    private static Set<String> copy(Set<String> values) {
        return values == null ? new LinkedHashSet<>() : new LinkedHashSet<>(values);
    }
}
