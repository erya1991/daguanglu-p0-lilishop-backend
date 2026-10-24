package cn.lili.modules.member.mapper;

import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/** Fresh grants scoped to the validated Store; the legacy menu cache/query is not an authority. */
@Mapper
public interface MerchantGrantMapper {
    @Select("""
        SELECT DISTINCT r.id AS role_id, r.store_id, m.id AS menu_id, m.permission,
                        rm.is_super AS write_grant
        FROM li_store_role r
        INNER JOIN li_store_menu_role rm ON rm.role_id=r.id AND rm.store_id=r.store_id
        INNER JOIN li_store_menu m ON m.id=rm.menu_id
        WHERE r.store_id=#{storeId} AND r.delete_flag=0 AND rm.delete_flag=0 AND m.delete_flag=0
          AND (EXISTS (SELECT 1 FROM li_clerk_role cr
                       WHERE cr.role_id=r.id AND cr.clerk_id=#{clerkId})
            OR EXISTS (SELECT 1 FROM li_store_department_role dr
                       INNER JOIN li_store_department d ON d.id=dr.department_id
                       WHERE dr.role_id=r.id AND d.id=#{departmentId}
                         AND d.store_id=r.store_id AND dr.delete_flag=0 AND d.delete_flag=0))
        """)
    List<Grant> current(@Param("clerkId") String clerkId, @Param("storeId") String storeId,
                        @Param("departmentId") String departmentId);

    @Data
    class Grant {
        private String roleId;
        private String storeId;
        private String menuId;
        private String permission;
        private Boolean writeGrant;
    }
}
