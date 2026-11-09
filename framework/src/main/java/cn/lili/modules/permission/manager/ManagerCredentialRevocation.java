package cn.lili.modules.permission.manager;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.util.List;
/** A persisted cutoff invalidates every session generation, including refresh; native token caches are also purged by AdminUserService. */
@Service @RequiredArgsConstructor @ConditionalOnProperty(name="p0.uat.enabled",havingValue="true")
public class ManagerCredentialRevocation {
    private final JdbcTemplate jdbc;
    public void invalidate(List<String> ids) {
        long cutoff=System.currentTimeMillis();
        for(String id:ids)jdbc.update("INSERT INTO li_p0_manager_security(admin_id,session_cutoff) VALUES(?,?) ON DUPLICATE KEY UPDATE session_cutoff=GREATEST(session_cutoff,VALUES(session_cutoff))",id,cutoff);
    }
}
