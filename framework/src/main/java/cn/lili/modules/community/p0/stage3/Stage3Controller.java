package cn.lili.modules.community.p0.stage3;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@RestController @ConditionalOnProperty(name="p0.stage3.enabled",havingValue="true")
public class Stage3Controller {
    private final Stage3Orders orders;private final Stage3Verification verification;private final Stage3Messages messages;private final Stage3Records records;private final Stage1Context context;private final Stage3Authorization auth;
    public Stage3Controller(Stage3Orders orders,Stage3Verification verification,Stage3Messages messages,Stage3Records records,Stage1Context context,Stage3Authorization auth){this.orders=orders;this.verification=verification;this.messages=messages;this.records=records;this.context=context;this.auth=auth;}
    private static Map<String,String> cleanQuery(Map<String,String> input){var result=new LinkedHashMap<>(input);String cache=result.remove("_t");Stage1Exception.require(cache==null||cache.matches("[0-9]{1,13}"),422,"QUERY_INVALID");return result;}
    private static ResponseEntity<Object> ok(Object result){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Stage1Responses.success(result));}
    @GetMapping({Stage3Routes.BUYER+"/orders",Stage3Routes.STORE+"/orders",Stage3Routes.MANAGER+"/trades"}) public Object list(@RequestParam Map<String,String> query){return ok(orders.list(cleanQuery(query)));}
    @GetMapping({Stage3Routes.BUYER+"/orders/{id}",Stage3Routes.STORE+"/orders/{id}",Stage3Routes.MANAGER+"/orders/{id}"}) public Object detail(@PathVariable String id){return ok(orders.detail(id));}
    @GetMapping({Stage3Routes.BUYER+"/operations/{op}",Stage3Routes.STORE+"/operations/{op}"}) public Object operation(@PathVariable String op){return ok(orders.operation(op));}
    @PostMapping(Stage3Routes.BUYER+"/orders/{id}/receive") public Object receive(@PathVariable String id,@RequestBody Object body){return ok(orders.command(id,"receive",body));}
    @PostMapping(Stage3Routes.STORE+"/orders/{id}/{action:prepare|dispatch|delivered|ship}") public Object command(@PathVariable String id,@PathVariable String action,@RequestBody Object body){return ok(orders.command(id,action,body));}
    @PostMapping(Stage3Routes.STORE+"/orders/{id}/exceptions") public Object exception(@PathVariable String id,@RequestBody Object body){return ok(orders.exception(id,body));}
    @GetMapping(Stage3Routes.BUYER+"/orders/{id}/credential") public Object credential(@PathVariable String id){return ok(verification.credential(id));}
    @GetMapping(Stage3Routes.STORE+"/points") public Object points(){return ok(verification.points());}
    @GetMapping(Stage3Routes.STORE+"/context") public Object storeContext(){var actor=context.require(SessionDomain.STORE);return ok(map("storeId",actor.store(),"memberId",actor.id(),"clerkId",actor.clerk(),"permissions",auth.storePermissions(actor.user())));}
    @PostMapping(Stage3Routes.STORE+"/verify/lookup") public Object lookup(@RequestBody Object body){return ok(verification.lookup(body));}
    @PostMapping(Stage3Routes.STORE+"/verify/confirm") public Object confirm(@RequestBody Object body){return ok(verification.confirm(body));}
    @GetMapping(Stage3Routes.STORE+"/workbench") public Object workbench(){return ok(orders.workbench());}
    @GetMapping({Stage3Routes.BUYER+"/messages",Stage3Routes.STORE+"/messages"}) public Object messages(@RequestParam(defaultValue="1")int pageNumber,@RequestParam(defaultValue="20")int pageSize){return ok(messages.list(pageNumber,pageSize));}
    @PostMapping({Stage3Routes.BUYER+"/messages/{id}/read",Stage3Routes.STORE+"/messages/{id}/read"}) public Object read(@PathVariable String id,@RequestBody Object body){return ok(messages.read(id,body));}
    @GetMapping(Stage3Routes.MANAGER+"/verifications") public Object records(@RequestParam Map<String,String> query){return ok(records.list(cleanQuery(query)));}
    @GetMapping(Stage3Routes.MANAGER+"/verifications/{id}") public Object record(@PathVariable String id){return ok(records.detail(id));}
}
