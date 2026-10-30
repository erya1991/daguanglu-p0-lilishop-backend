package cn.lili.modules.community.p0.catalog;
import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@cn.lili.modules.community.p0.support.P0BusinessEnabled
@RequestMapping(Stage1Routes.BUYER)
public class ConsumerGoodsController {
    private final ConsumerGoodsService service;public ConsumerGoodsController(ConsumerGoodsService service){this.service=service;}
    @GetMapping("/goods/{id}") public Object detail(@PathVariable String id){return Stage1Responses.success(service.detail(id));}
    @GetMapping("/stores/{id}/goods") public Object list(@PathVariable String id,@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(service.list(id,pageNumber,pageSize));}
    @PostMapping("/precheck") public Object precheck(@RequestBody Map<String,Object> body){return Stage1Responses.success(service.precheck(body));}
}
