package cn.lili.modules.community.p0.member;

import cn.lili.modules.community.p0.support.*;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@P0BusinessEnabled
@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="p0.uat.enabled",havingValue="true")
@RequestMapping(Stage1Routes.MANAGER + "/residents")
public class ResidentManagerController {
    private final ResidentManagerService service;
    public ResidentManagerController(ResidentManagerService service){this.service=service;}
    @PostMapping("/search") public Object search(@RequestBody Map<String,Object> body){return Stage1Responses.success(service.search(body));}
    @GetMapping("/{id}") public Object detail(@PathVariable String id){return Stage1Responses.success(service.detail(id));}
    @GetMapping("/{id}/orders") public Object orders(@PathVariable String id,@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(service.orders(id,pageNumber,pageSize));}
    @GetMapping("/{id}/aftersales") public Object aftersales(@PathVariable String id,@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(service.aftersales(id,pageNumber,pageSize));}
    @GetMapping("/{id}/impact") public Object impact(@PathVariable String id){return Stage1Responses.success(service.impact(id));}
    @PostMapping("/{id}/disable") public Object disable(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.change(id,body,false));}
    @PostMapping("/{id}/enable") public Object enable(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.change(id,body,true));}
}
