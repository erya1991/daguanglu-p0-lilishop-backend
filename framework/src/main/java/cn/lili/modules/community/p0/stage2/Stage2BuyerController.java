package cn.lili.modules.community.p0.stage2;

import cn.lili.modules.community.p0.support.Stage1Responses;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping(Stage2Routes.BASE)
@ConditionalOnProperty(name="p0.stage2.enabled",havingValue="true")
public class Stage2BuyerController {
    private final cn.lili.modules.community.p0.support.Stage1Files files;private final Stage2Catalog catalog;private final Stage2Cart cart;private final Stage2Addresses addresses;private final Stage2Transactions transactions;
    public Stage2BuyerController(Stage2Catalog catalog,Stage2Cart cart,Stage2Addresses addresses,Stage2Transactions transactions,cn.lili.modules.community.p0.support.Stage1Files files){this.files=files;this.catalog=catalog;this.cart=cart;this.addresses=addresses;this.transactions=transactions;}
    @GetMapping("/regions") public Object regions(@RequestParam(required=false) String parentId){transactions.member();return Stage1Responses.success(addresses.regions(parentId));}
    @GetMapping("/catalog") public Object catalog(){return Stage1Responses.success(catalog.categories());}
    @GetMapping("/goods") public Object goods(@RequestParam(defaultValue="")String keyword,@RequestParam(required=false)String categoryId,@RequestParam(defaultValue="COMPREHENSIVE")String sort,@RequestParam(defaultValue="1")int page,@RequestParam(defaultValue="20")int pageSize){return Stage1Responses.success(catalog.list(keyword,categoryId,sort,page,pageSize));}
    @GetMapping("/goods/{id}") public Object detail(@PathVariable String id){return Stage1Responses.success(catalog.detail(id));}
    @GetMapping("/goods/{id}/images/{fileId}") public org.springframework.http.ResponseEntity<byte[]> image(@PathVariable String id,@PathVariable String fileId){transactions.member();var file=files.goodsImage(id,fileId);return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.parseMediaType(file.mime())).cacheControl(org.springframework.http.CacheControl.noStore()).header("X-Content-Type-Options","nosniff").header("Content-Disposition","attachment").body(file.bytes());}
    @GetMapping("/cart") public Object cart(){return Stage1Responses.success(cart.read());}
    @PostMapping("/cart/items") public Object add(@RequestBody Object body){return Stage1Responses.success(cart.add(body));}
    @PutMapping("/cart/items/{id}") public Object change(@PathVariable String id,@RequestBody Object body){return Stage1Responses.success(cart.update(id,body));}
    @PostMapping("/cart/remove") public Object remove(@RequestBody Object body){return Stage1Responses.success(cart.remove(body));}
    @GetMapping("/addresses") public Object addresses(){return Stage1Responses.success(addresses.list());}
    @GetMapping("/addresses/{id}") public Object address(@PathVariable String id){return Stage1Responses.success(addresses.snapshot(id,transactions.member()));}
    @PostMapping("/addresses") public Object createAddress(@RequestBody Object body){return Stage1Responses.success(addresses.save(null,body));}
    @PutMapping("/addresses/{id}") public Object saveAddress(@PathVariable String id,@RequestBody Object body){return Stage1Responses.success(addresses.save(id,body));}
    @DeleteMapping("/addresses/{id}") public Object deleteAddress(@PathVariable String id,@RequestBody Object body){return Stage1Responses.success(addresses.remove(id,body));}
    @PostMapping("/addresses/{id}/default") public Object defaultAddress(@PathVariable String id,@RequestBody Object body){return Stage1Responses.success(addresses.makeDefault(id,body));}
    @PostMapping("/addresses/default/clear") public Object clearDefault(@RequestBody Object body){return Stage1Responses.success(addresses.makeDefault(null,body));}
}
