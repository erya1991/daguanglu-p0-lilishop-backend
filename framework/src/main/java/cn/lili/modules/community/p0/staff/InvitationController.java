package cn.lili.modules.community.p0.staff;

import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@cn.lili.modules.community.p0.support.P0BusinessEnabled
@RequestMapping({Stage1Routes.STORE,Stage1Routes.APPLICANT})
public class InvitationController {
    private final StaffService service;public InvitationController(StaffService service){this.service=service;}
    @PostMapping("/invitations/preview") public Object preview(@RequestBody Map<String,Object> body){return Stage1Responses.success(service.preview(body));}
    @PostMapping("/invitations/accept") public Object accept(@RequestBody Map<String,Object> body){return Stage1Responses.success(service.respond(body,true));}
    @PostMapping("/invitations/reject") public Object reject(@RequestBody Map<String,Object> body){return Stage1Responses.success(service.respond(body,false));}
}
