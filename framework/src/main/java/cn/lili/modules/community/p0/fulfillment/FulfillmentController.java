package cn.lili.modules.community.p0.fulfillment;
import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
public class FulfillmentController {
    private final FulfillmentService service;private final Stage1Context context;public FulfillmentController(FulfillmentService service,Stage1Context context){this.service=service;this.context=context;}
    @GetMapping(Stage1Routes.STORE+"/fulfillment") public Object configuration(){return Stage1Responses.success(service.configuration(store()));}
    @PutMapping(Stage1Routes.STORE+"/fulfillment") public Object configuration(@RequestBody Map<String,Object> body){return Stage1Responses.success(service.saveConfiguration(body));}
    @GetMapping(Stage1Routes.MANAGER+"/stores/{id}/fulfillment") public Object configuration(@PathVariable String id){return Stage1Responses.success(service.configuration(id));}
    @GetMapping(Stage1Routes.MANAGER+"/stores/{id}/points") public Object points(@PathVariable String id,@RequestParam(required=false) String type,@RequestParam(required=false) Boolean enabled,@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(service.points(id,type,enabled,pageNumber,pageSize));}
    @GetMapping(Stage1Routes.STORE+"/points") public Object points(@RequestParam(required=false) String type,@RequestParam(required=false) Boolean enabled,@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(service.points(store(),type,enabled,pageNumber,pageSize));}
    @GetMapping(Stage1Routes.STORE+"/points/{id}") public Object point(@PathVariable String id){return Stage1Responses.success(service.detail(id));}
    @PostMapping(Stage1Routes.STORE+"/points") public Object point(@RequestBody Map<String,Object> body){return Stage1Responses.success(service.savePoint(null,body));}
    @PutMapping(Stage1Routes.STORE+"/points/{id}") public Object point(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.savePoint(id,body));}
    @PostMapping(Stage1Routes.STORE+"/points/{id}/{action:enable|disable}") public Object command(@PathVariable String id,@PathVariable String action,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.pointCommand(id,body,action.equals("enable")));}
    private String store(){return context.require(SessionDomain.STORE).store();}
}
