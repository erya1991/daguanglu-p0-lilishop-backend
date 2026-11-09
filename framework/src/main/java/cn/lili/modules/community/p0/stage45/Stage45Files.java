package cn.lili.modules.community.p0.stage45;

import cn.lili.common.security.session.SessionDomain;
import cn.lili.modules.community.p0.support.*;
import cn.lili.modules.community.p0.stage2.Stage2Settings;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.transaction.support.*;
import java.nio.file.*;
import java.io.*;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Native file metadata and bytes; every Stage45 attachment is bound to one order and subject. */
@Service @ConditionalOnProperty(name="p0.stage45.enabled",havingValue="true")
public class Stage45Files {
    private final Stage45Data d;private final Stage2Settings settings;private final Stage1Files nativeFiles;
    public Stage45Files(Stage45Data d,Stage2Settings settings,Stage1Files nativeFiles){this.d=d;this.settings=settings;this.nativeFiles=nativeFiles;}
    public Stage1Files.Download productImage(String orderId,String itemId){
        var order=d.order(orderId,"attachment.read");var item=d.item(order,itemId);var snapshot=object(order.get("payload_json"));Stage1Exception.require(hash(snapshot).equals(id(order,"content_hash")),409,"SNAPSHOT_INVALID");
        String ref=null;for(var raw:snapshot.getAsJsonObject("order").getAsJsonArray("items")){var line=raw.getAsJsonObject();if(id(item,"sku_id").equals(line.get("skuId").getAsString()))ref=line.has("image")&&!line.get("image").isJsonNull()?line.get("image").getAsString():null;}
        Stage1Exception.require(ref!=null&&ref.matches("stage1-file:[1-9][0-9]{0,18}"),404,"FILE_UNAVAILABLE");var image=nativeFiles.historicalGoodsImage(ref.substring(12),id(order,"store_id"),id(item,"goods_id"));d.context.recheck(d.context.current());return image;
    }
    public Object upload(String metadata,MultipartFile upload) {
        var f=new Fields(metadata).only("operationId","orderId","purpose");String orderId=f.id("orderId",true);
        String purpose=f.choice("purpose",Set.of("AFTERSALE","EVALUATION"),true);
        var order=d.order(orderId,"attachment.write");var actor=d.context.current();
        if(purpose.equals("EVALUATION"))Stage1Exception.require(actor.domain()==SessionDomain.RESIDENT,403,"FORBIDDEN");
        Stage1Exception.require(upload!=null&&!upload.isEmpty()&&upload.getSize()<=5*1024*1024,422,"FILE_SIZE_INVALID");
        byte[] bytes;try(var input=upload.getInputStream()){bytes=input.readNBytes(5*1024*1024+1);}catch(IOException e){throw new Stage1Exception(503,"FILE_UNAVAILABLE");}
        Stage1Exception.require(bytes.length>0&&bytes.length<=5*1024*1024,422,"FILE_SIZE_INVALID");
        String mime=Stage1Files.detect(bytes,true),sha=digest(bytes);
        var command=map("operationId",f.text("operationId",8,64,true),"orderId",orderId,"purpose",purpose,"sha256",sha);
        return d.command(order,"attachment.write",orderId,command,()->{
            String file=d.db.id(),key=UUID.randomUUID().toString();Path target=storage(key);
            try{Files.write(target,bytes,StandardOpenOption.CREATE_NEW);}catch(IOException e){throw new Stage1Exception(503,"FILE_UNAVAILABLE");}
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization(){
                @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{Files.deleteIfExists(target);}catch(IOException ignored){/* Own unreferenced bytes remain inaccessible. */}}
            });
            d.db.nativeInsert("li_file",map("id",file,"delete_flag",false,"create_by",actor.id(),"create_time",d.clock.instant(),"name",file,"file_type",mime,"url","stage45-file:"+file,"file_size",bytes.length,"file_category",purpose));
            d.db.update("INSERT INTO li_p0_file_control(file_id,actor_id,identity_domain,store_id,purpose,private_file,storage_key,mime_type,byte_size,sha256,status,created_at,expires_at) VALUES(?,?,?,?,?,1,?,?,?,?,'UPLOADED',?,?)",file,actor.id(),actor.domain().name(),order.get("store_id"),purpose,key,mime,bytes.length,sha,d.clock.instant(),d.clock.instant().plusSeconds(86400));
            d.db.update("INSERT INTO li_p0_s45_attachment(file_id,order_id,subject_type) VALUES(?,?,?)",file,orderId,purpose);
            return map("id",file,"purpose",purpose,"mimeType",mime,"size",bytes.length);
        });
    }
    public void bind(List<String> files,Map<String,Object> order,String purpose,String subject) {
        Stage1Exception.require(files.size()<=10&&new HashSet<>(files).size()==files.size(),422,"FILES_INVALID");
        var actor=d.context.current();
        for(String file:files){
            var r=d.db.required("SELECT f.*,a.order_id,a.subject_id FROM li_p0_file_control f JOIN li_p0_s45_attachment a ON a.file_id=f.file_id WHERE f.file_id=? FOR UPDATE",file);
            Stage1Exception.require(actor.id().equals(id(r,"actor_id"))&&actor.domain().name().equals(id(r,"identity_domain"))&&id(order,"id").equals(id(r,"order_id"))&&purpose.equals(id(r,"purpose")),404,"FILE_UNAVAILABLE");
            Stage1Exception.require(("UPLOADED".equals(id(r,"status"))&&instant(r.get("expires_at")).isAfter(d.clock.instant()))||("BOUND".equals(id(r,"status"))&&subject.equals(id(r,"subject_id"))),422,"FILE_UNAVAILABLE");
            readBytes(r);
            d.db.update("UPDATE li_p0_file_control SET status='BOUND',bound_object_id=?,expires_at=NULL WHERE file_id=?",subject,file);
            d.db.update("UPDATE li_p0_s45_attachment SET subject_id=? WHERE file_id=?",subject,file);
        }
    }
    public Stage1Files.Download read(String file){
        var r=d.db.required("SELECT f.*,a.order_id,a.subject_type,a.subject_id FROM li_p0_file_control f JOIN li_p0_s45_attachment a ON a.file_id=f.file_id WHERE f.file_id=?",file);
        var actor=d.context.current();boolean publicEvaluation="EVALUATION".equals(id(r,"purpose"))&&r.get("subject_id")!=null&&d.db.count("SELECT COUNT(*) FROM li_member_evaluation WHERE id=? AND status='OPEN' AND delete_flag=0",r.get("subject_id"))==1;
        if(!publicEvaluation){
            var order=d.order(id(r,"order_id"),"attachment.read");
            if(r.get("subject_id")==null)Stage1Exception.require(actor.id().equals(id(r,"actor_id"))&&actor.domain().name().equals(id(r,"identity_domain"))&&instant(r.get("expires_at")).isAfter(d.clock.instant()),404,"FILE_UNAVAILABLE");
            if("AFTERSALE".equals(id(r,"purpose"))&&r.get("subject_id")!=null)d.auth.order(actor,order,"aftersale.read");
            if("EVALUATION".equals(id(r,"purpose"))&&r.get("subject_id")!=null)d.auth.order(actor,order,"evaluation.read");
        }
        d.context.recheck(actor);return new Stage1Files.Download(file,id(r,"mime_type"),readBytes(r),!publicEvaluation);
    }
    private Path storage(String key){
        try{Stage1Exception.require(key!=null&&key.matches("[a-f0-9-]{36}"),503,"FILE_UNAVAILABLE");Path root=Path.of(settings.getFileRoot()).toRealPath(),dir=root.resolve("private").toRealPath();Stage1Exception.require(dir.startsWith(root),503,"FILE_UNAVAILABLE");return dir.resolve(key);}
        catch(IOException e){throw new Stage1Exception(503,"FILE_UNAVAILABLE");}
    }
    private byte[] readBytes(Map<String,Object> row){
        try{Path p=storage(id(row,"storage_key"));Stage1Exception.require(Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS)&&Files.size(p)==number(row,"byte_size"),503,"FILE_UNAVAILABLE");byte[] bytes=Files.readAllBytes(p);Stage1Exception.require(digest(bytes).equals(id(row,"sha256")),503,"FILE_UNAVAILABLE");return bytes;}
        catch(IOException e){throw new Stage1Exception(503,"FILE_UNAVAILABLE");}
    }
}
