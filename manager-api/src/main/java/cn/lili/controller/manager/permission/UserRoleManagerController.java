package cn.lili.controller.manager.permission;

import cn.lili.common.enums.ResultCode;
import cn.lili.common.enums.ResultUtil;
import cn.lili.common.vo.ResultMessage;
import cn.lili.modules.permission.entity.dos.UserRole;
import cn.lili.modules.permission.service.UserRoleService;
import cn.lili.controller.manager.passport.ManagerWebSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;


/**
 * 管理端,管理员角色接口
 *
 * @author Chopper
 * @since 2020/11/22 11:53
 */
@RestController
@Tag(name = "管理端,管理员角色接口")
@RequestMapping("/manager/permission/userRole")
public class UserRoleManagerController {
    @Autowired
    private UserRoleService userRoleService;
    @Autowired(required = false)
    private ManagerWebSupport managerWeb;

    @Operation(description = "查看管理员角色")
    @Parameter(name = "userId", description = "管理员ID", required = true)
    @GetMapping("/{userId}")
    public ResultMessage<List<UserRole>> get(@PathVariable String userId) {
        if (ManagerWebSupport.active()) {
            if (managerWeb == null) throw new cn.lili.common.exception.ServiceException("CONFIG_REQUIRED");
            return ResultUtil.data(managerWeb.policy.userRoles(managerWeb.actor(), userId));
        }
        return ResultUtil.data(userRoleService.listByUserId(userId));
    }

    @PutMapping(value = "/{userId}", consumes = "application/json")
    public ResultMessage<Object> updateScoped(@PathVariable String userId, @RequestBody Object payload) {
        if (!ManagerWebSupport.active() || managerWeb == null)
            throw new cn.lili.common.exception.ServiceException(cn.lili.common.enums.ResultCode.USER_AUTHORITY_ERROR);
        managerWeb.maintenance.replaceUserRoles(managerWeb.actor(), userId, ManagerWebSupport.ids(payload, "roleIds"));
        return ResultUtil.success();
    }

    @Operation(description = "更新角色菜单")
    @Parameter(name = "userId", description = "管理员ID", required = true)
    @PutMapping("/{userId}")
    public ResultMessage<UserRole> update(@PathVariable String userId, List<UserRole> userRole) {
        if (ManagerWebSupport.active())
            throw new cn.lili.common.exception.ServiceException(cn.lili.common.enums.ResultCode.USER_AUTHORITY_ERROR);
        userRoleService.updateUserRole(userId, userRole);
        return ResultUtil.success();
    }

}
