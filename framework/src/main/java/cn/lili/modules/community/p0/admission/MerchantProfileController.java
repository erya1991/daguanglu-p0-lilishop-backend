package cn.lili.modules.community.p0.admission;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@cn.lili.modules.community.p0.support.P0BusinessEnabled
@RequestMapping(Stage1Routes.STORE)
public class MerchantProfileController {
    private final AdmissionService service;private final Stage1Context context;private final Stage1Access access;
    public MerchantProfileController(AdmissionService service,Stage1Context context,Stage1Access access){this.service=service;this.context=context;this.access=access;}
    @GetMapping("/profile") public Object profile(){return Stage1Responses.success(service.profile());}
    @PutMapping("/profile") public Object update(@RequestBody Map<String,Object> body){return Stage1Responses.success(service.ordinaryProfile(body));}
    @GetMapping("/profile-change/current") public Object change(){return Stage1Responses.success(service.currentChange());}
    @PostMapping("/profile-change/submit") public Object submit(@RequestBody Map<String,Object> body){return Stage1Responses.success(service.submitChange(body));}
    @GetMapping("/qualifications") public Object qualifications(){return Stage1Responses.success(service.qualifications(context.require(SessionDomain.STORE).store()));}
    @GetMapping("/qualifications/{id}") public Object qualification(@PathVariable String id){return Stage1Responses.success(service.qualificationDetail(id));}
    @PostMapping("/qualifications") public Object createQualification(@RequestBody Map<String,Object> body){return Stage1Responses.success(service.qualificationDraft(null,body));}
    @PutMapping("/qualifications/{id}/draft") public Object saveQualification(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.qualificationDraft(id,body));}
    @PostMapping("/qualifications/{id}/submit") public Object submitQualification(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.submitQualification(id,body));}
    @GetMapping("/context") public Object context(){var actor=context.require(SessionDomain.STORE);return Stage1Responses.success(access.contextView(actor.user()));}
}
