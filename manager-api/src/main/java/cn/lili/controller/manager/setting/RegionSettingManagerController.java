package cn.lili.controller.manager.setting;

import cn.lili.common.aop.annotation.DemoSite;
import cn.lili.common.enums.ResultUtil;
import cn.lili.common.vo.ResultMessage;
import cn.lili.modules.system.entity.dos.Region;
import cn.lili.modules.system.service.RegionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * 管理端,行政地区管理接口
 *
 * @author Chopper
 * @since 2020/12/2 10:40
 */
@RestController
@Tag(name = "管理端,行政地区管理接口")
@RequestMapping("/manager/setting/region")
public class RegionSettingManagerController {
    @Autowired
    private RegionService regionService;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @org.springframework.beans.factory.annotation.Value("${p0.uat.enabled:false}") private boolean uat;

    private void requireUnused(Region row) {
        if(row==null)throw new cn.lili.common.exception.ServiceException("REGION_NOT_FOUND");
        String id=row.getId(),code=row.getAdCode();
        long refs=jdbc.queryForObject("SELECT (SELECT COUNT(*) FROM li_region WHERE parent_id=? AND delete_flag=0)+(SELECT COUNT(*) FROM li_store WHERE FIND_IN_SET(?,store_address_id_path)>0)+(SELECT COUNT(*) FROM li_store_detail WHERE FIND_IN_SET(?,company_address_id_path)>0)+(SELECT COUNT(*) FROM li_member WHERE FIND_IN_SET(?,region_id)>0)+(SELECT COUNT(*) FROM li_member_address WHERE FIND_IN_SET(?,consignee_address_id_path)>0)+(SELECT COUNT(*) FROM li_order WHERE FIND_IN_SET(?,consignee_address_id_path)>0)+(SELECT COUNT(*) FROM li_p0_point_control WHERE province_code=? OR city_code=? OR district_code=?)+(SELECT COUNT(*) FROM li_p0_fulfillment_config WHERE JSON_SEARCH(methods,'one',?) IS NOT NULL)",Long.class,id,id,id,id,code,code,code,code,code,code);
        if(refs>0)throw new cn.lili.common.exception.ServiceException("REGION_IN_USE");
    }
    private void prepare(Region row,String id) {
        if(!uat)return;
        if(row.getAdCode()==null||!row.getAdCode().matches("[0-9]{6,12}")||row.getOrderNum()==null)throw new cn.lili.common.exception.ServiceException("REGION_INVALID");
        var levels=java.util.List.of("province","city","district","street");
        Region parent="0".equals(row.getParentId())?null:regionService.getById(row.getParentId());
        int level=levels.indexOf(row.getLevel());
        if(level<0||(parent==null?level!=0:level!=levels.indexOf(parent.getLevel())+1)||(!"0".equals(row.getParentId())&&parent==null))throw new cn.lili.common.exception.ServiceException("REGION_HIERARCHY_INVALID");
        if(jdbc.queryForObject("SELECT COUNT(*) FROM li_region WHERE ad_code=? AND delete_flag=0 AND id<>?",Long.class,row.getAdCode(),id==null?"0":id)>0)throw new cn.lili.common.exception.ServiceException("REGION_CODE_EXISTS");
        row.setPath(parent==null?"0":parent.getPath().endsWith(","+parent.getId())||parent.getPath().equals(parent.getId())?parent.getPath():parent.getPath()+","+parent.getId());
    }

    @DemoSite
    @PostMapping("/sync")
    @Operation(summary = "同步高德行政地区数据")
    @Parameter(name = "url", description = "高德行政地区数据接口", required = true)
    public void synchronizationData(String url) {
        regionService.synchronizationData(url);
    }

    @GetMapping("/{id}")
    @Operation(summary = "通过id获取地区详情")
    @Parameter(name = "id", description = "地区ID", required = true)
    public ResultMessage<Region> get(@PathVariable String id) {
        return ResultUtil.data(regionService.getById(id));
    }

    @GetMapping("/item/{id}")
    @Operation(summary = "通过id获取子地区")
    @Parameter(name = "id", description = "地区ID", required = true)
    public ResultMessage<List<Region>> getItem(@PathVariable String id) {
        return ResultUtil.data(regionService.getItem(id));
    }

    @DemoSite
    @PutMapping("/{id}")
    @Operation(summary = "更新地区")
    @Parameter(name = "id", description = "地区ID", required = true)
    @Parameter(name = "region", description = "地区信息", required = true)
    public ResultMessage<Region> update(@PathVariable String id, @Valid Region region) {
        if(uat){Region prior=regionService.getById(id);if(prior==null)throw new cn.lili.common.exception.ServiceException("REGION_NOT_FOUND");if(!java.util.Objects.equals(prior.getAdCode(),region.getAdCode())||!java.util.Objects.equals(prior.getParentId(),region.getParentId())||!java.util.Objects.equals(prior.getLevel(),region.getLevel()))requireUnused(prior);}
        prepare(region,id);
        region.setId(id);
        regionService.updateById(region);
        return ResultUtil.data(region);
    }

    @DemoSite
    @PostMapping("/save")
    @Operation(summary = "增加地区")
    @Parameter(name = "region", description = "地区信息", required = true)
    public ResultMessage<Region> save(@Valid Region region) {
        prepare(region,null);
        if(uat){region.setId(null);region.setDeleteFlag(false);}
        regionService.save(region);
        return ResultUtil.data(region);
    }

    @DemoSite
    @DeleteMapping("/{ids}")
    @Operation(summary = "批量通过id删除")
    @Parameter(name = "ids", description = "地区ID列表", required = true)
    public ResultMessage<Object> delAllByIds(@PathVariable List<String> ids) {
        if(uat)for(String id:ids)requireUnused(regionService.getById(id));
        regionService.removeByIds(ids);
        return ResultUtil.success();
    }
}
