package cn.lili.modules.permission.manager;

import cn.lili.common.security.session.*;
import cn.lili.modules.permission.entity.dos.Menu;
import cn.lili.modules.permission.mapper.MenuMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.PatternMatchUtils;
import java.util.*;

/** Reads the native effective grants on every request; no parallel authorization store or super bypass. */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name="p0.uat.enabled",havingValue="true")
public class NativeManagerAccess {
    private final JdbcTemplate jdbc;
    private final MenuMapper menus;
    public void reference(String kind,String id) {
        String table=switch(kind){case "user"->"li_admin_user";case "role"->"li_role";case "menu"->"li_menu";case "department"->"li_department";default->throw new IllegalArgumentException("Invalid native reference");};
        require(id!=null&&id.matches("[0-9]{1,19}")&&Long.valueOf(1).equals(jdbc.queryForObject("SELECT COUNT(*) FROM "+table+" WHERE id=? AND delete_flag=0",Long.class,id)));
    }
    public void check(String actor) {
        require(Long.valueOf(1).equals(jdbc.queryForObject("SELECT COUNT(*) FROM li_admin_user WHERE id=? AND status=1 AND delete_flag=0",Long.class,actor)));
    }
    public boolean grants(String actor,String method,String path) {
        check(actor);
        if(!NativeManagerRoutes.allowed(method,path))return false;
        if(NativeManagerRoutes.self(method,path))return true;
        String resource=path.equals("/manager/common/upload/file")?"/manager/common/file":path;
        return menus.getUserRoleMenu(actor).stream().anyMatch(m->m.getPermission()!=null
            && (method.equals("GET")||Boolean.TRUE.equals(m.getIsSuper()))
            && Arrays.stream(m.getPermission().split(",")).map(String::trim).anyMatch(p->p.startsWith("/manager/")&&PatternMatchUtils.simpleMatch(p,resource)));
    }
    public void route(String actor,String method,String path) { require(grants(actor,method,path)); }
    public List<Menu> navigation(String actor) { check(actor);return menus.findByUserId(actor); }
    public List<Map<String,Object>> permissions(String actor) {
        check(actor);
        return menus.getUserRoleMenu(actor).stream().filter(m->m.getPermission()!=null&&!m.getPermission().isBlank())
            .map(m->Map.<String,Object>of("permission",m.getPermission(),"write",Boolean.TRUE.equals(m.getIsSuper()))).toList();
    }
    public boolean hasManagementEntry(String actor) {
        return managementNavigation(actor) && grants(actor,"POST","/manager/passport/user/register")
            && grants(actor,"GET","/manager/passport/user/getByCondition")
            && grants(actor,"POST","/manager/permission/role")
            && grants(actor,"POST","/manager/permission/roleMenu/0")
            && grants(actor,"PUT","/manager/passport/user/admin/edit")
            && grants(actor,"GET","/manager/permission/menu/tree");
    }
    private boolean managementNavigation(String actor) {
        var rows=menus.findByUserId(actor);if(rows==null)return false;
        Map<String,Menu> nodes=new HashMap<>();for(var row:rows)nodes.put(row.getId(),row);
        for(var row:rows){Set<String> seen=new HashSet<>();for(Menu n=row;n!=null;n=nodes.get(n.getParentId())){
            if(!seen.add(n.getId())||n.getName()==null||!n.getName().matches("[-a-zA-Z0-9_]+"))return false;
        }}
        for(String page:List.of("users","roles","menus")){
            String component=switch(page){case "users"->"sys/user-manage/userManage";case "roles"->"sys/role-manage/roleManage";default->"sys/menu-manage/menuManage";};
            String permission=switch(page){case "users"->"/manager/passport/user/*";case "roles"->"/manager/permission/role*";default->"/manager/permission/menu*";};
            if(rows.stream().noneMatch(m->("uat-"+page).equals(m.getName())&&("/settings/"+page).equals(m.getPath())&&component.equals(m.getFrontRoute())&&permission.equals(m.getPermission())))return false;
        }
        return true;
    }
    public void validateMenu(Menu menu) {
        require(menu.getName()!=null&&menu.getName().matches("[-a-zA-Z0-9_]{1,100}")&&menu.getTitle()!=null&&!menu.getTitle().isBlank());
        require(menu.getSortOrder()!=null&&Double.isFinite(menu.getSortOrder())&&menu.getParentId()!=null);
        Set<String> chain=new HashSet<>();String parent=menu.getParentId();if(menu.getId()!=null)chain.add(menu.getId());
        while(!"0".equals(parent)){
            require(chain.size()<32&&chain.add(parent));reference("menu",parent);
            parent=jdbc.queryForObject("SELECT parent_id FROM li_menu WHERE id=?",String.class,parent);require(parent!=null);
        }
    }
    public void requireManagementEntry() {
        boolean usable=jdbc.queryForList("SELECT id FROM li_admin_user WHERE status=1 AND delete_flag=0 AND password IS NOT NULL AND password<>''",String.class)
            .stream().anyMatch(this::hasManagementEntry);
        if(!usable)throw new cn.lili.common.exception.ServiceException("LAST_MANAGEMENT_ENTRY_REQUIRED");
    }
    public void checkSession(SessionRecord record) {
        if(record.identity().domain()!=SessionDomain.MANAGER)return;
        checkIssuedAt(record.identity().subject(),record.issuedAt());
    }
    public void checkIssuedAt(String actor,long issuedAt) {
        List<Long> cutoff=jdbc.queryForList("SELECT session_cutoff FROM li_p0_manager_security WHERE admin_id=?",Long.class,actor);
        require(cutoff.isEmpty() || issuedAt>cutoff.getFirst());
    }
    private static void require(boolean valid) { if(!valid)throw new SessionException(SessionException.Code.DENIED); }
}
