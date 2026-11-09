package cn.lili.controller.manager.wechat;

import cn.lili.common.aop.annotation.DemoSite;
import cn.lili.common.enums.ResultUtil;
import cn.lili.common.vo.PageVO;
import cn.lili.common.vo.ResultMessage;
import cn.lili.common.vo.SearchVO;
import cn.lili.modules.wechat.entity.dos.WechatMPMessage;
import cn.lili.modules.wechat.service.WechatMPMessageService;
import com.baomidou.mybatisplus.core.metadata.IPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;


/**
 * @author Chopper
 */
@RestController
@Tag(name = "微信小程序消息订阅接口")
@RequestMapping("/manager/wechat/wechatMPMessage")
public class WechatMPMessageManagerController {
    @Autowired
    private WechatMPMessageService wechatMPMessageService;

    @DemoSite
    @GetMapping("/init")
    @Operation(summary = "初始化微信小程序消息订阅")
    public ResultMessage init() {
        wechatMPMessageService.init();
        return ResultUtil.success();
    }

    @GetMapping("/{id}")
    @Operation(summary = "查看微信小程序消息订阅详情")
    @Parameter(name = "id", description = "微信小程序消息订阅ID", required = true)
    public ResultMessage<WechatMPMessage> get(@PathVariable String id) {

        WechatMPMessage wechatMPMessage = wechatMPMessageService.getById(id);
        return ResultUtil.data(wechatMPMessage);
    }

    @GetMapping
    @Operation(summary = "分页获取微信小程序消息订阅")
    @Parameter(name = "entity", description = "微信小程序消息订阅查询参数", required = true)
    @Parameter(name = "searchVo", description = "分页参数", required = true)
    @Parameter(name = "page", description = "分页参数", required = true)
    public ResultMessage<IPage<WechatMPMessage>> getByPage(WechatMPMessage entity,
                                                           SearchVO searchVo,
                                                           PageVO page, @RequestParam(required=false) Boolean enable) {
        // Entity defaults are creation defaults, not implicit list filters.
        entity.setEnable(enable);
        IPage<WechatMPMessage> data = wechatMPMessageService.getByPage(entity, searchVo, page);
        return new ResultUtil<IPage<WechatMPMessage>>().setData(data);
    }

    @DemoSite
    @PostMapping
    @Operation(summary = "新增微信小程序消息订阅")
    public ResultMessage<WechatMPMessage> save(WechatMPMessage wechatMPMessage) {

        wechatMPMessageService.save(wechatMPMessage);
        return ResultUtil.data(wechatMPMessage);
    }

    @DemoSite
    @PutMapping("/{id}")
    @Operation(summary = "更新微信小程序消息订阅")
    @Parameter(name = "id", description = "微信小程序消息订阅ID", required = true)
    @Parameter(name = "wechatMPMessage", description = "微信小程序消息订阅参数", required = true)
    public ResultMessage<WechatMPMessage> update(@PathVariable String id, WechatMPMessage wechatMPMessage) {
        WechatMPMessage safe = new WechatMPMessage();
        safe.setId(id);
        safe.setEnable(wechatMPMessage.getEnable());
        wechatMPMessageService.updateById(safe);
        return ResultUtil.data(wechatMPMessage);
    }

    @DemoSite
    @DeleteMapping("/{ids}")
    @Operation(summary = "删除微信小程序消息订阅")
    @Parameter(name = "ids", description = "微信小程序消息订阅ID列表", required = true)
    public ResultMessage<Object> delAllByIds(@PathVariable List<String> ids) {

        wechatMPMessageService.removeByIds(ids);
        return ResultUtil.success();
    }
}
