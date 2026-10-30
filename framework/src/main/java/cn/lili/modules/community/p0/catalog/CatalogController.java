package cn.lili.modules.community.p0.catalog;
import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@cn.lili.modules.community.p0.support.P0BusinessEnabled
public class CatalogController {
    private final CatalogService service;public CatalogController(CatalogService service){this.service=service;}
    @GetMapping({Stage1Routes.APPLICANT+"/admission-options",Stage1Routes.STORE+"/admission-options"}) public Object admissionOptions(){return Stage1Responses.success(service.admissionOptions());}
    @GetMapping({Stage1Routes.MANAGER+"/categories",Stage1Routes.STORE+"/categories"}) public Object categories(){return Stage1Responses.success(service.list("CATEGORY"));}
    @GetMapping({Stage1Routes.MANAGER+"/units",Stage1Routes.STORE+"/units"}) public Object units(){return Stage1Responses.success(service.list("UNIT"));}
    @GetMapping(Stage1Routes.MANAGER+"/qualification-types") public Object qualificationTypes(){return Stage1Responses.success(service.qualificationTypes());}
    @GetMapping(Stage1Routes.MANAGER+"/{collection:categories|units}/{id}") public Object detail(@PathVariable String collection,@PathVariable String id,@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(service.detail(type(collection),id,pageNumber,pageSize));}
    @PostMapping(Stage1Routes.MANAGER+"/{collection:categories|units}") public Object create(@PathVariable String collection,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.save(type(collection),null,body));}
    @PutMapping(Stage1Routes.MANAGER+"/{collection:categories|units}/{id}") public Object edit(@PathVariable String collection,@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.save(type(collection),id,body));}
    @PostMapping(Stage1Routes.MANAGER+"/{collection:categories|units}/{id}/{action:enable|disable}") public Object command(@PathVariable String collection,@PathVariable String id,@PathVariable String action,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.command(type(collection),id,body,action.equals("enable")));}
    private String type(String collection){return collection.equals("categories")?"CATEGORY":"UNIT";}
}
