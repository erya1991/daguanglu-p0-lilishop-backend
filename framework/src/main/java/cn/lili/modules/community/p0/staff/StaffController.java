package cn.lili.modules.community.p0.staff;

import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController
@cn.lili.modules.community.p0.support.P0BusinessEnabled
@RequestMapping(Stage1Routes.STORE)
public class StaffController {
    private final StaffService service;public StaffController(StaffService service){this.service=service;}
    @GetMapping("/staff") public Object staff(@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(service.page(false,pageNumber,pageSize));}
    @GetMapping("/staff/{id}") public Object detail(@PathVariable String id){return Stage1Responses.success(service.detail(id));}
    @GetMapping("/staff-role-options") public Object roles(){return Stage1Responses.success(service.roles());}
    @GetMapping("/staff-invitations") public Object invitations(@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(service.page(true,pageNumber,pageSize));}
    @GetMapping("/staff-invitations/{id}") public Object invitation(@PathVariable String id){return Stage1Responses.success(service.invitationDetail(id));}
    @PostMapping("/staff-invitations") public Object invite(@RequestBody Map<String,Object> body){return Stage1Responses.success(service.create(body));}
    @PostMapping("/staff-invitations/{id}/confirm") public Object confirm(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.manageInvitation(id,body,"confirm"));}
    @PostMapping("/staff-invitations/{id}/reject") public Object reject(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.manageInvitation(id,body,"reject"));}
    @PostMapping("/staff-invitations/{id}/revoke") public Object revoke(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.manageInvitation(id,body,"revoke"));}
    @PutMapping("/staff/{id}/roles") public Object roles(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.change(id,body,"roles"));}
    @PostMapping("/staff/{id}/enable") public Object enable(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.change(id,body,"enable"));}
    @PostMapping("/staff/{id}/disable") public Object disable(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.change(id,body,"disable"));}
    @PostMapping("/staff/{id}/revoke") public Object remove(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(service.change(id,body,"revoke"));}
}
