package cn.lili.modules.community.p0.presentation;

import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController
@cn.lili.modules.community.p0.support.P0BusinessEnabled
@org.springframework.context.annotation.Conditional(ManagerPresentationCondition.class)
@RequestMapping(Stage1Routes.MANAGER+"/ui")
public class ManagerPresentationController {
    private final ManagerPresentationService service;private final ManagerLookupService lookup;
    public ManagerPresentationController(ManagerPresentationService service,ManagerLookupService lookup){this.service=service;this.lookup=lookup;}
    @GetMapping("/applications") public Object applications(@RequestParam(required=false) String kind,@RequestParam(required=false) String status,@RequestParam(required=false) String q,@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(service.applications(kind,status,q,pageNumber,pageSize));}
    @GetMapping("/applications/{id}") public Object application(@PathVariable String id,@RequestParam(required=false) Integer revision){return Stage1Responses.success(service.application(id,revision));}
    @GetMapping("/stores") public Object stores(@RequestParam(required=false) String q,@RequestParam(required=false) String state,@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(service.stores(q,state,pageNumber,pageSize));}
    @GetMapping("/stores/{id}") public Object store(@PathVariable String id){return Stage1Responses.success(service.store(id));}
    @GetMapping("/stores/{id}/staff") public Object staff(@PathVariable String id,@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(service.staff(id,pageNumber,pageSize));}
    @GetMapping("/stores/{id}/history") public Object history(@PathVariable String id,@RequestParam(required=false) String objectType,@RequestParam(required=false) String objectId,@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(service.objectHistory(id,objectType,objectId,pageNumber,pageSize));}
    @GetMapping({"/goods","/reviews"}) public Object goods(jakarta.servlet.http.HttpServletRequest request,@RequestParam(required=false) String targetStore,@RequestParam(required=false) String status,@RequestParam(required=false) String q,@RequestParam(required=false) Boolean forced,@RequestParam(required=false) String marketStatus,@RequestParam(required=false) String stockState,@RequestParam(required=false) String categoryId,@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(service.goods(targetStore,status,q,forced,marketStatus,stockState,categoryId,request.getRequestURI().endsWith("/reviews"),pageNumber,pageSize));}
    @GetMapping("/goods/{id}") public Object goods(@PathVariable String id){return Stage1Responses.success(service.goodsDetail(id));}
    @GetMapping("/stores/{id}/point") public Object point(@PathVariable String id,@RequestParam String pointId){return Stage1Responses.success(service.point(id,pointId));}
    @GetMapping("/lookups/{type:stores|agreements|categories|units|channel-configs|receivers}") public Object options(@PathVariable String type,@RequestParam(required=false) String q,@RequestParam(required=false) String selected,@RequestParam(required=false) String targetStore,@RequestParam(required=false) String configId,@RequestParam(required=false) String purpose,@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(lookup.options(type,q,selected,targetStore,configId,purpose,pageNumber,pageSize));}
    @GetMapping("/region-names") public Object regionNames(@RequestParam String codes){return Stage1Responses.success(lookup.regionNames(codes));}
}
