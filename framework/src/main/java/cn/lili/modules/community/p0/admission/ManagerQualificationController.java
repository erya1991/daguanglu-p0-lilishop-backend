package cn.lili.modules.community.p0.admission;
import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@cn.lili.modules.community.p0.support.P0BusinessEnabled
@RequestMapping(Stage1Routes.MANAGER+"/qualifications")
public class ManagerQualificationController {
    private final AdmissionService service;
    public ManagerQualificationController(AdmissionService service){this.service=service;}
    @PostMapping("/{id}/revoke") public Object revoke(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.revokeQualification(id,body));}
}
