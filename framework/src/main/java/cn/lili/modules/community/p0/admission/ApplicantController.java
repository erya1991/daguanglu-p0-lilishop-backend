package cn.lili.modules.community.p0.admission;

import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
@RequestMapping(Stage1Routes.APPLICANT)
public class ApplicantController {
    private final AdmissionService service;public ApplicantController(AdmissionService service){this.service=service;}
    @GetMapping("/application") public Object own(){return Stage1Responses.success(service.own());}
    @PostMapping("/application") public Object create(@RequestBody Map<String,Object> body){return Stage1Responses.success(service.create(body));}
    @PutMapping("/application/{id}/draft") public Object draft(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.draft(id,body));}
    @PostMapping("/application/{id}/submit") public Object submit(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.submit(id,body));}
}
