package cn.lili.modules.community.p0.stage45;

import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.http.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@RestController @ConditionalOnProperty(name="p0.stage45.enabled",havingValue="true")
public class Stage45Controller {
    private final Stage45Data d;private final Stage45AfterSales afterSales;private final Stage45Evaluations evaluations;private final Stage45Files files;private final Stage45Funds funds;private final Stage45FundViews views;private final Stage45Messages messages;private final Stage45Channels channels;private final Stage45Reconciliation reconciliation;
    public Stage45Controller(Stage45Data d,Stage45AfterSales afterSales,Stage45Evaluations evaluations,Stage45Files files,Stage45Funds funds,Stage45FundViews views,Stage45Messages messages,Stage45Channels channels,Stage45Reconciliation reconciliation){this.d=d;this.afterSales=afterSales;this.evaluations=evaluations;this.files=files;this.funds=funds;this.views=views;this.messages=messages;this.channels=channels;this.reconciliation=reconciliation;}
    private static ResponseEntity<Object> ok(Object value){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Stage1Responses.success(value));}
    private static Map<String,String> query(Map<String,String> input){var out=new LinkedHashMap<>(input);String cache=out.remove("_t");Stage1Exception.require(cache==null||cache.matches("[0-9]{1,13}"),422,"QUERY_INVALID");return out;}
    @GetMapping({Stage45Routes.BUYER+"/context",Stage45Routes.STORE+"/context",Stage45Routes.MANAGER+"/context"})
    public Object context(){var actor=d.context.current();return ok(map("identityDomain",actor.domain().name(),"memberId",actor.id(),"storeId",actor.store(),"clerkId",actor.clerk(),"permissions",d.auth.permissions(actor.user()),"mode","MOCK","serverTime",d.clock.instant()));}
    @GetMapping({Stage45Routes.BUYER+"/operations/{op}",Stage45Routes.STORE+"/operations/{op}",Stage45Routes.MANAGER+"/operations/{op}"})
    public Object operation(@PathVariable String op){return ok(d.operation(op));}
    @PostMapping({Stage45Routes.BUYER+"/operations/{op}/abandon",Stage45Routes.STORE+"/operations/{op}/abandon",Stage45Routes.MANAGER+"/operations/{op}/abandon"})
    public Object abandon(@PathVariable String op,@RequestBody Object body){return ok(d.abandon(op,body));}
    @GetMapping({Stage45Routes.BUYER+"/orders/{id}/eligibility",Stage45Routes.STORE+"/orders/{id}/eligibility",Stage45Routes.MANAGER+"/orders/{id}/eligibility"})
    public Object eligibility(@PathVariable String id){return ok(afterSales.eligibility(id));}
    @GetMapping({Stage45Routes.BUYER+"/aftersales",Stage45Routes.STORE+"/aftersales",Stage45Routes.MANAGER+"/aftersales"})
    public Object afterSales(@RequestParam Map<String,String> q){return ok(afterSales.list(query(q)));}
    @GetMapping({Stage45Routes.BUYER+"/aftersales/{id}",Stage45Routes.STORE+"/aftersales/{id}",Stage45Routes.MANAGER+"/aftersales/{id}"})
    public Object afterSale(@PathVariable String id){return ok(afterSales.detail(id));}
    @PostMapping(Stage45Routes.BUYER+"/orders/{id}/aftersales")
    public Object apply(@PathVariable String id,@RequestBody Object body){return ok(afterSales.apply(id,body));}
    @PostMapping({Stage45Routes.BUYER+"/aftersales/{id}/{action:cancel|return|dispute|evidence}",Stage45Routes.STORE+"/aftersales/{id}/{action:approve|reject|receive|exception}",Stage45Routes.MANAGER+"/aftersales/{id}/{action:resolve}"})
    public Object afterSaleCommand(@PathVariable String id,@PathVariable String action,@RequestBody Object body){return ok(afterSales.act(id,action,body));}
    @GetMapping({Stage45Routes.BUYER+"/evaluations",Stage45Routes.STORE+"/evaluations",Stage45Routes.MANAGER+"/evaluations"})
    public Object evaluations(@RequestParam Map<String,String> q){return ok(evaluations.list(query(q),null));}
    @GetMapping({Stage45Routes.BUYER+"/evaluations/{id}",Stage45Routes.STORE+"/evaluations/{id}",Stage45Routes.MANAGER+"/evaluations/{id}"})
    public Object evaluation(@PathVariable String id){return ok(evaluations.detail(id));}
    @GetMapping({Stage45Routes.BUYER+"/evaluation-summary",Stage45Routes.STORE+"/evaluation-summary",Stage45Routes.MANAGER+"/evaluation-summary"})
    public Object evaluationSummary(){return ok(evaluations.summary(null));}
    @GetMapping(Stage45Routes.BUYER+"/goods/{id}/evaluations")
    public Object publicEvaluations(@PathVariable String id,@RequestParam Map<String,String> q){return ok(map("page",evaluations.list(query(q),id),"summary",evaluations.summary(id)));}
    @PostMapping(Stage45Routes.BUYER+"/orders/{id}/evaluations")
    public Object evaluate(@PathVariable String id,@RequestBody Object body){return ok(evaluations.submit(id,body));}
    @PostMapping(Stage45Routes.MANAGER+"/evaluations/{id}/moderate")
    public Object moderate(@PathVariable String id,@RequestBody Object body){return ok(evaluations.moderate(id,body));}
    @PostMapping(value={Stage45Routes.BUYER+"/attachments",Stage45Routes.STORE+"/attachments",Stage45Routes.MANAGER+"/attachments"},consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object upload(@RequestPart("metadata")String metadata,@RequestPart("file")MultipartFile file){return ok(files.upload(metadata,file));}
    @GetMapping({Stage45Routes.BUYER+"/orders/{order}/items/{item}/image",Stage45Routes.STORE+"/orders/{order}/items/{item}/image",Stage45Routes.MANAGER+"/orders/{order}/items/{item}/image"})
    public Object productImage(@PathVariable String order,@PathVariable String item){var file=files.productImage(order,item);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType(file.mime())).body(file.bytes());}
    @GetMapping({Stage45Routes.BUYER+"/attachments/{id}",Stage45Routes.STORE+"/attachments/{id}",Stage45Routes.MANAGER+"/attachments/{id}"})
    public Object attachment(@PathVariable String id){var file=files.read(id);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options","nosniff").contentType(MediaType.parseMediaType(file.mime())).body(file.bytes());}
    @GetMapping({Stage45Routes.STORE+"/funds",Stage45Routes.MANAGER+"/funds"})
    public Object funds(@RequestParam Map<String,String> q){return ok(views.list(query(q),false));}
    @GetMapping({Stage45Routes.STORE+"/orders/{id}/funds",Stage45Routes.MANAGER+"/orders/{id}/funds"})
    public Object funds(@PathVariable String id){return ok(views.detail(id));}
    @GetMapping({Stage45Routes.STORE+"/tasks/{id}",Stage45Routes.MANAGER+"/tasks/{id}"})
    public Object task(@PathVariable String id){return ok(views.taskDetail(id));}
    @GetMapping(Stage45Routes.MANAGER+"/exceptions")
    public Object exceptions(@RequestParam Map<String,String> q){return ok(views.list(query(q),true));}
    @PostMapping(Stage45Routes.MANAGER+"/tasks/{id}/recover")
    public Object recover(@PathVariable String id,@RequestBody Object body){return ok(funds.recover(id,body));}
    @PostMapping(Stage45Routes.MANAGER+"/orders/{id}/late-refund")
    public Object lateRefund(@PathVariable String id,@RequestBody Object body){return ok(funds.lateRefund(id,body));}
    @GetMapping(Stage45Routes.MANAGER+"/channels")
    public Object channels(@RequestParam Map<String,String> q){return ok(channels.list(query(q)));}
    @GetMapping(Stage45Routes.MANAGER+"/channels/{id}")
    public Object channel(@PathVariable String id){return ok(channels.view(id));}
    @PostMapping(Stage45Routes.MANAGER+"/channels/{id}/sync")
    public Object channelSync(@PathVariable String id,@RequestBody Object body){return ok(channels.sync(id,body));}
    @GetMapping(Stage45Routes.MANAGER+"/rules")
    public Object rules(@RequestParam Map<String,String> q){return ok(channels.rules(query(q)));}
    @GetMapping(Stage45Routes.MANAGER+"/rules/{id}")
    public Object rule(@PathVariable String id){return ok(channels.rule(id));}
    @GetMapping(Stage45Routes.MANAGER+"/reconciliation-accounts")
    public Object reconciliationAccounts(){return ok(reconciliation.accounts());}
    @GetMapping(Stage45Routes.MANAGER+"/reconciliations")
    public Object reconciliations(@RequestParam Map<String,String> q){return ok(reconciliation.list(query(q)));}
    @GetMapping(Stage45Routes.MANAGER+"/reconciliations/{id}")
    public Object reconciliation(@PathVariable String id){return ok(reconciliation.detail(id));}
    @PostMapping(Stage45Routes.MANAGER+"/reconciliations")
    public Object createReconciliation(@RequestBody Object body){return ok(reconciliation.create(body));}
    @PostMapping(Stage45Routes.MANAGER+"/reconciliation-lines/{id}/review")
    public Object reviewLine(@PathVariable String id,@RequestBody Object body){return ok(reconciliation.review(id,body));}
    @GetMapping({Stage45Routes.BUYER+"/messages",Stage45Routes.STORE+"/messages"})
    public Object messages(@RequestParam Map<String,String> q){return ok(messages.list(query(q)));}
    @PostMapping({Stage45Routes.BUYER+"/messages/{id}/read",Stage45Routes.STORE+"/messages/{id}/read"})
    public Object read(@PathVariable String id,@RequestBody Object body){return ok(messages.read(id,body));}
}
