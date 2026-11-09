package cn.lili.modules.permission.serviceimpl;

import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.modules.permission.entity.dos.DepartmentRole;
import cn.lili.modules.permission.mapper.DepartmentRoleMapper;
import cn.lili.modules.permission.service.DepartmentRoleService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 部门角色业务层实现
 *
 * @author Chopper
 * @since 2020/11/22 12:08
 */
@Service
public class DepartmentRoleServiceImpl extends ServiceImpl<DepartmentRoleMapper, DepartmentRole> implements DepartmentRoleService {

    @Autowired(required=false) private cn.lili.modules.permission.manager.NativeManagerAccess nativeManager;
    @Autowired
    private Cache cache;

    @Override
    public List<DepartmentRole> listByDepartmentId(String departmentId) {
        QueryWrapper queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("department_id", departmentId);
        return this.baseMapper.selectList(queryWrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateByDepartmentId(String departmentId, List<DepartmentRole> departmentRoles) {
        QueryWrapper queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("department_id", departmentId);
        this.remove(queryWrapper);

        if (departmentRoles == null || departmentRoles.size()>100) throw new cn.lili.common.exception.ServiceException(cn.lili.common.enums.ResultCode.PARAMS_ERROR);
        if(nativeManager!=null)nativeManager.reference("department",departmentId);
        var ids=new java.util.HashSet<String>();var replacements=new java.util.ArrayList<DepartmentRole>();
        for(var relation:departmentRoles){
            if(relation==null||!departmentId.equals(relation.getDepartmentId())||relation.getRoleId()==null||!relation.getRoleId().matches("[0-9]{1,19}")||!ids.add(relation.getRoleId()))throw new cn.lili.common.exception.ServiceException(cn.lili.common.enums.ResultCode.PARAMS_ERROR);
            if(nativeManager!=null)nativeManager.reference("role",relation.getRoleId());
            replacements.add(new DepartmentRole(relation.getRoleId(),departmentId));
        }
        if(!replacements.isEmpty()&&!this.saveBatch(replacements))throw new cn.lili.common.exception.ServiceException(cn.lili.common.enums.ResultCode.ERROR);
        cache.vagueDel(CachePrefix.USER_MENU.getPrefix(UserEnums.MANAGER));
        cache.vagueDel(CachePrefix.PERMISSION_LIST.getPrefix(UserEnums.MANAGER));
    }

    @Override
    public void deleteByDepartment(List<String> ids) {
        QueryWrapper queryWrapper = new QueryWrapper<>();
        queryWrapper.in("department_id", ids);
        this.remove(queryWrapper);
        cache.vagueDel(CachePrefix.USER_MENU.getPrefix(UserEnums.MANAGER));
        cache.vagueDel(CachePrefix.PERMISSION_LIST.getPrefix(UserEnums.MANAGER));
    }
}