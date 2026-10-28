package cn.lili.modules.community.p0.support;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
@RequestMapping({Stage1Routes.MANAGER,Stage1Routes.STORE,Stage1Routes.APPLICANT,Stage1Routes.BUYER})
public class Stage1CommonController {
    private final Stage1Mutations mutations;private final Stage1Files files;private final Stage1Regions regions;private final Stage1Context context;
    public Stage1CommonController(Stage1Mutations mutations,Stage1Files files,Stage1Regions regions,Stage1Context context){this.mutations=mutations;this.files=files;this.regions=regions;this.context=context;}
    @GetMapping("/regions") public Object regions(@RequestParam(required=false) String parentId){context.current();return Stage1Responses.success(regions.children(parentId));}
    @GetMapping("/operations/{requestId}") public Object operation(@PathVariable String requestId){return Stage1Responses.success(mutations.operation(requestId));}
    @PostMapping("/operations/{requestId}/abandon") public Object abandon(@PathVariable String requestId,@RequestBody Object body){return Stage1Responses.success(mutations.abandon(requestId,body));}
    @PostMapping(value="/files",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Object upload(@RequestParam String metadata,@RequestPart MultipartFile file){return Stage1Responses.success(files.upload(metadata,file));}
    @GetMapping("/files/{id}/preview") public ResponseEntity<byte[]> file(@PathVariable String id){
        var value=files.read(id);
        return response(value);
    }
    @GetMapping("/goods/{id}/images/{fileId}") public ResponseEntity<byte[]> image(@PathVariable String id,@PathVariable String fileId){return response(files.goodsImage(id,fileId));}
    private ResponseEntity<byte[]> response(Stage1Files.Download value){
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(value.mime())).cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options","nosniff").header("Content-Security-Policy","sandbox; default-src 'none'")
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(value.id()).build().toString()).body(value.bytes());
    }
}
