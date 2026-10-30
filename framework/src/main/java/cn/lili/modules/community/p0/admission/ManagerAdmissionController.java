package cn.lili.modules.community.p0.admission;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@cn.lili.modules.community.p0.support.P0BusinessEnabled
@RequestMapping(Stage1Routes.MANAGER)
public class ManagerAdmissionController {
    private final AdmissionService service;private final Stage1Context context;
    public ManagerAdmissionController(AdmissionService service,Stage1Context context){this.service=service;this.context=context;}
    @GetMapping("/applications") public Object list(@RequestParam(required=false) String kind,@RequestParam(required=false) String status,@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(service.queue(kind,status,pageNumber,pageSize));}
    @GetMapping("/applications/{id}") public Object detail(@PathVariable String id){return Stage1Responses.success(service.detail(id));}
    @PostMapping("/applications/{id}/approve") public Object approve(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.review(id,body,true));}
    @PostMapping("/applications/{id}/reject") public Object reject(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.review(id,body,false));}
    @GetMapping("/stores") public Object stores(@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(service.stores(pageNumber,pageSize));}
    @GetMapping("/stores/{id}") public Object store(@PathVariable String id){context.require(SessionDomain.MANAGER);return Stage1Responses.success(service.profile(id));}
    @PostMapping("/stores/{id}/close") public Object close(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.supervise(id,body,false));}
    @PostMapping("/stores/{id}/reopen") public Object reopen(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.supervise(id,body,true));}
}
