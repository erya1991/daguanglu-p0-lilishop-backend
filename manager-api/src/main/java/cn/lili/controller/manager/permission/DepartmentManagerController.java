package cn.lili.controller.manager.permission;

import cn.lili.common.enums.ResultUtil;
import cn.lili.common.vo.ResultMessage;
import cn.lili.common.vo.SearchVO;
import cn.lili.modules.permission.entity.dos.Department;
import cn.lili.modules.permission.entity.vo.DepartmentVO;
import cn.lili.modules.permission.service.DepartmentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;


/**
 * 管理端,部门管理接口
 *
 * @author Chopper
 * @since 2020/11/22 12:06
 */
@RestController
@Tag(name = "管理端,部门管理接口")
@RequestMapping("/manager/permission/department")
public class DepartmentManagerController {
    @Autowired
    private DepartmentService departmentService;
    @Autowired(required=false) private cn.lili.modules.permission.manager.NativeManagerAccess nativeAccess;
    private Department clean(Department input,String id){
        String parent=input.getParentId()==null?"0":input.getParentId();java.util.Set<String> seen=new java.util.HashSet<>();if(id!=null)seen.add(id);String current=parent;
        while(!"0".equals(current)){if(seen.size()>32||!seen.add(current))throw new cn.lili.common.exception.ServiceException(cn.lili.common.enums.ResultCode.PARAMS_ERROR);nativeAccess.reference("department",current);current=departmentService.getById(current).getParentId();}
        Department value=new Department();value.setId(id);value.setTitle(input.getTitle());value.setParentId(parent);value.setSortOrder(input.getSortOrder());return value;
    }

    @Operation(description = "查看部门详情")
    @Parameter(name = "id", description = "部门ID", required = true)
    @GetMapping("/{id}")
    public ResultMessage<Department> get(@PathVariable String id) {
        Department department = departmentService.getById(id);
        return ResultUtil.data(department);
    }

    @Operation(description = "获取树状结构")
    @Parameter(name = "entity", description = "部门查询参数", required = false)
    @Parameter(name = "searchVo", description = "分页参数", required = false)
    @GetMapping
    public ResultMessage<List<DepartmentVO>> getByPage(Department entity,
                                                       SearchVO searchVo) {
        return ResultUtil.data(departmentService.tree(entity, searchVo));

    }

    @PostMapping
    @Operation(description = "新增部门")
    @Parameter(name = "department", description = "部门参数", required = true)
    public ResultMessage<Department> save(@Validated Department department) {
        if(nativeAccess!=null)department=clean(department,null);
        departmentService.save(department);
        return ResultUtil.data(department);
    }

    @PutMapping("/{id}")
    @Operation(description = "更新部门")
    @Parameter(name = "id", description = "部门ID", required = true)
    @Parameter(name = "department", description = "部门参数", required = true)
    public ResultMessage<Department> update(@PathVariable String id, @Validated Department department) {
        if(nativeAccess!=null){nativeAccess.reference("department",id);department=clean(department,id);}
        department.setId(id);
        departmentService.updateById(department);
        return ResultUtil.data(department);
    }

    @DeleteMapping("/{ids}")
    @Operation(description = "删除部门")
    @Parameter(name = "ids", description = "部门ID列表", required = true)
    public ResultMessage<Object> delAllByIds(@PathVariable List<String> ids) {
        departmentService.deleteByIds(ids);
        return ResultUtil.success();
    }
}
