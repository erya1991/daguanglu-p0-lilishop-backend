package cn.lili.modules.permission.mapper;

import cn.lili.modules.permission.entity.dos.Menu;
import cn.lili.modules.permission.entity.vo.UserMenuVO;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 菜单数据处理层
 *
 * @author Chopper
 * @since 2020-11-22 09:17
 */
public interface MenuMapper extends BaseMapper<Menu> {

    /**
     * 根据用户获取菜单列表
     *
     * @param userId 用户ID
     * @return 菜单列表
     */
    @Select("SELECT DISTINCT m.* FROM li_menu m JOIN li_role_menu rm ON rm.menu_id=m.id JOIN li_role r ON r.id=rm.role_id WHERE m.delete_flag=0 AND rm.delete_flag=0 AND r.delete_flag=0 AND (EXISTS(SELECT 1 FROM li_user_role ur WHERE ur.user_id=#{userId} AND ur.role_id=r.id) OR EXISTS(SELECT 1 FROM li_department_role dr JOIN li_department d ON d.id=dr.department_id JOIN li_admin_user a ON a.department_id=d.id WHERE a.id=#{userId} AND dr.role_id=r.id AND dr.delete_flag=0 AND d.delete_flag=0)) ORDER BY m.sort_order")
    List<Menu> findByUserId(String userId);

    /**
     * 根据用户获取菜单权限
     *
     * @param userId 用户ID
     * @return 用户菜单VO列表
     */
    @Select("SELECT DISTINCT rm.is_super,m.* FROM li_menu m JOIN li_role_menu rm ON rm.menu_id=m.id JOIN li_role r ON r.id=rm.role_id WHERE m.delete_flag=0 AND rm.delete_flag=0 AND r.delete_flag=0 AND (EXISTS(SELECT 1 FROM li_user_role ur WHERE ur.user_id=#{userId} AND ur.role_id=r.id) OR EXISTS(SELECT 1 FROM li_department_role dr JOIN li_department d ON d.id=dr.department_id JOIN li_admin_user a ON a.department_id=d.id WHERE a.id=#{userId} AND dr.role_id=r.id AND dr.delete_flag=0 AND d.delete_flag=0)) ORDER BY m.sort_order")
    List<UserMenuVO> getUserRoleMenu(String userId);
}