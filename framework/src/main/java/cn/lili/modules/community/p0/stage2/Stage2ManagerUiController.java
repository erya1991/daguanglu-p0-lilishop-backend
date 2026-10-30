package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@ConditionalOnProperty(name={"p0.stage2.enabled","p0.stage2.web-ui-enabled"},havingValue="true")
@RequestMapping(Stage1Routes.MANAGER+"/ui")
public class Stage2ManagerUiController {
    private final Stage2ManagerUiService service;
    public Stage2ManagerUiController(Stage2ManagerUiService service){this.service=service;}
    @GetMapping("/stage2-goods") public Object goods(@RequestParam(required=false)String targetStore,@RequestParam(required=false)String status,@RequestParam(required=false)String q,@RequestParam(required=false)Boolean forced,@RequestParam(required=false)String marketStatus,@RequestParam(required=false)String stockState,@RequestParam(defaultValue="1")int pageNumber,@RequestParam(defaultValue="20")int pageSize){return Stage1Responses.success(service.goods(targetStore,status,q,forced,marketStatus,stockState,pageNumber,pageSize));}
    @GetMapping("/stage2-goods/{id}") public Object detail(@PathVariable String id){return Stage1Responses.success(service.detail(id));}
    @GetMapping("/stage2-stores") public Object stores(@RequestParam(required=false)String q,@RequestParam(required=false)String selected,@RequestParam(defaultValue="1")int pageNumber,@RequestParam(defaultValue="20")int pageSize){return Stage1Responses.success(service.stores(q,selected,pageNumber,pageSize));}
}
