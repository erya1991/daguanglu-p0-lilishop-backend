package cn.lili.controller.manager.permission;

import cn.lili.common.enums.ResultCode;
import cn.lili.common.enums.ResultUtil;
import cn.lili.common.vo.ResultMessage;
import cn.lili.modules.permission.entity.dos.RoleMenu;
import cn.lili.modules.permission.service.RoleMenuService;
import cn.lili.controller.manager.passport.ManagerWebSupport;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;


/**
 * 管理端,角色菜单接口
 *
 * @author Chopper
 * @since 2020/11/22 11:40
 */
@RestController
@Tag(name = "管理端,角色菜单接口")
@RequestMapping("/manager/permission/roleMenu")
public class RoleMenuManagerController {
    @Autowired
    private RoleMenuService roleMenuService;
    @Autowired(required = false)
    private ManagerWebSupport managerWeb;

    @Operation(description = "查看某角色拥有到菜单")
    @GetMapping("/{roleId}")
    @Parameter(name = "roleId", description = "角色ID")
    public ResultMessage<List<RoleMenu>> get(@PathVariable String roleId) {
        if (managerWeb != null && managerWeb.scoped()) {
            if (managerWeb == null) throw new cn.lili.common.exception.ServiceException("CONFIG_REQUIRED");
            return ResultUtil.data(managerWeb.policy.roleMenus(managerWeb.actor(), roleId));
        }
        return ResultUtil.data(roleMenuService.findByRoleId(roleId));
    }

    @Operation(description = "保存角色菜单")
    @PostMapping("/{roleId}")
    @Parameter(name = "roleId", description = "角色ID")
    @Parameter(name = "roleMenus", description = "角色菜单列表")
    public ResultMessage save(@PathVariable String roleId, @RequestBody Object payload) {
        if (managerWeb != null && managerWeb.scoped()) {
            if (managerWeb == null) throw new cn.lili.common.exception.ServiceException("CONFIG_REQUIRED");
            managerWeb.maintenance.replaceRoleMenus(managerWeb.actor(), roleId, ManagerWebSupport.ids(payload, "menuIds"));
            return ResultUtil.success();
        }
        var legacy = new com.google.gson.Gson().toJsonTree(payload);
        if (!legacy.isJsonArray()) throw new cn.lili.common.exception.ServiceException(ResultCode.PARAMS_ERROR);
        // Preserve the historical UI's 0/1 flag wire format outside scoped A/B Sessions.
        for (var item : legacy.getAsJsonArray()) {
            if (item.isJsonObject() && item.getAsJsonObject().has("isSuper")) {
                var flag = item.getAsJsonObject().get("isSuper");
                if (flag.isJsonPrimitive() && flag.getAsJsonPrimitive().isNumber()) {
                    String value = flag.getAsString();
                    if (!value.equals("0") && !value.equals("1"))
                        throw new cn.lili.common.exception.ServiceException(ResultCode.PARAMS_ERROR);
                    item.getAsJsonObject().addProperty("isSuper", value.equals("1"));
                }
            }
        }
        List<RoleMenu> roleMenus = new com.google.gson.Gson().fromJson(legacy,
                new com.google.gson.reflect.TypeToken<List<RoleMenu>>() {}.getType());
        roleMenuService.updateRoleMenu(roleId, roleMenus);
        return ResultUtil.success();
    }

}
