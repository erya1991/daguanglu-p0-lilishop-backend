package cn.lili.modules.community.p0.channel;
import cn.lili.modules.community.p0.support.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import java.util.Map;
@RestController
@cn.lili.modules.community.p0.support.P0BusinessEnabled
@RequestMapping(Stage1Routes.MANAGER)
public class ManagerChannelController {
    private final ChannelConfigService configs;private final ChannelService channels;private final ReceiverService receivers;
    public ManagerChannelController(ChannelConfigService configs,ChannelService channels,ReceiverService receivers){this.configs=configs;this.channels=channels;this.receivers=receivers;}
    @GetMapping("/channel-configs") public Object configs(@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(configs.list(pageNumber,pageSize));}
    @GetMapping("/channel-configs/{id}") public Object config(@PathVariable String id){return Stage1Responses.success(configs.detail(id));}
    @PostMapping("/channel-configs") public Object createConfig(@RequestBody Map<String,Object> body){return Stage1Responses.success(configs.save(null,body));}
    @PutMapping("/channel-configs/{id}/draft") public Object editConfig(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(configs.save(id,body));}
    @PostMapping("/channel-configs/{id}/{action:enable|disable|check}") public Object configCommand(@PathVariable String id,@PathVariable String action,@RequestBody Map<String,Object> body){return Stage1Responses.success(configs.command(id,body,action));}
    @GetMapping("/submerchants") public Object relations(@RequestParam(name="targetStore",required=false) String storeId,@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(channels.list(storeId,pageNumber,pageSize));}
    @GetMapping("/submerchants/{id}") public Object relation(@PathVariable String id){return Stage1Responses.success(channels.detail(id));}
    @GetMapping("/submerchants/{id}/required-action") public ResponseEntity<Object> requiredAction(@PathVariable String id,@RequestParam long expectedVersion){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Pragma","no-cache").header("Referrer-Policy","no-referrer").body(Stage1Responses.success(channels.requiredAction(id,expectedVersion)));}
    @PostMapping("/submerchants") public Object createRelation(@RequestBody Map<String,Object> body){return Stage1Responses.success(channels.create(body));}
    @PutMapping("/submerchants/{id}/materials") public Object materials(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(channels.saveMaterials(id,body));}
    @PutMapping("/submerchants/{id}/receiver") public Object receiver(@PathVariable String id,@RequestBody Map<String,Object> body){return Stage1Responses.success(channels.receiver(id,body));}
    @PostMapping("/submerchants/{id}/{action:submit|query|enable|disable}") public Object command(@PathVariable String id,@PathVariable String action,@RequestBody Map<String,Object> body){return Stage1Responses.success(channels.command(id,body,action));}
    @GetMapping("/receivers") public Object receivers(@RequestParam(required=false) String configId,@RequestParam(defaultValue="1") int pageNumber,@RequestParam(defaultValue="20") int pageSize){return Stage1Responses.success(receivers.list(configId,pageNumber,pageSize));}
    @GetMapping("/receivers/{id}") public Object receiver(@PathVariable String id){return Stage1Responses.success(receivers.detail(id));}
    @PostMapping("/receivers") public Object createReceiver(@RequestBody Map<String,Object> body){return Stage1Responses.success(receivers.create(body));}
    @PostMapping("/receivers/{id}/{action:sync|enable|disable}") public Object receiverCommand(@PathVariable String id,@PathVariable String action,@RequestBody Map<String,Object> body){return Stage1Responses.success(receivers.command(id,body,action));}
}
