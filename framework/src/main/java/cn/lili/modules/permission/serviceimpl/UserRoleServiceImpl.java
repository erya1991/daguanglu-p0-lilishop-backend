package cn.lili.modules.permission.serviceimpl;

import cn.lili.cache.Cache;
import cn.lili.cache.CachePrefix;
import cn.lili.common.enums.ResultCode;
import cn.lili.common.exception.ServiceException;
import cn.lili.common.security.enums.UserEnums;
import cn.lili.modules.permission.entity.dos.UserRole;
import cn.lili.modules.permission.mapper.UserRoleMapper;
import cn.lili.modules.permission.service.UserRoleService;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户权限业务层实现
 *
 * @author Chopper
 * @since 2020/11/17 3:52 下午
 */
@Service
public class UserRoleServiceImpl extends ServiceImpl<UserRoleMapper, UserRole> implements UserRoleService {

    @Autowired
    private Cache cache;

    @Override
    public List<UserRole> listByUserId(String userId) {
        QueryWrapper<UserRole> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId);
        return this.baseMapper.selectList(queryWrapper);
    }

    @Override
    public List<String> listId(String userId) {
        List<UserRole> userRoleList = this.listByUserId(userId);
        List<String> strings = new ArrayList<>();
        userRoleList.forEach(item -> strings.add(item.getRoleId()));
        return strings;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateUserRole(String userId, List<UserRole> userRoles) {
        if (userId == null || userId.isBlank() || userRoles == null) {
            throw new ServiceException(ResultCode.PARAMS_ERROR);
        }
        List<UserRole> replacements = new ArrayList<>(userRoles.size());
        Set<String> roleIds = new HashSet<>();
        for (UserRole relation : userRoles) {
            if (relation == null || !userId.equals(relation.getUserId())
                    || relation.getRoleId() == null || relation.getRoleId().isBlank()
                    || !roleIds.add(relation.getRoleId())) {
                throw new ServiceException(ResultCode.PARAMS_ERROR);
            }
            replacements.add(new UserRole(userId, relation.getRoleId()));
        }
        // A zero-row delete is valid for a previously unassigned user.
        this.baseMapper.delete(new QueryWrapper<UserRole>().eq("user_id", userId));
        if (!replacements.isEmpty() && !this.saveBatch(replacements)) {
            throw new ServiceException(ResultCode.ERROR);
        }
        cache.vagueDel(CachePrefix.USER_MENU.getPrefix(UserEnums.MANAGER));
        cache.vagueDel(CachePrefix.PERMISSION_LIST.getPrefix(UserEnums.MANAGER));
    }

}
