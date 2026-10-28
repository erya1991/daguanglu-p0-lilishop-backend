package cn.lili.modules.community.p0.support;

import cn.lili.common.security.session.SessionDomain;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import javax.imageio.ImageIO;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

@Service
@ConditionalOnProperty(name="p0.stage1.enabled",havingValue="true")
public class Stage1Files {
    private final Stage1Db db;private final Stage1Context context;private final Stage1Access access;private final Stage1Mutations mutations;private final Stage1Settings settings;private final Clock clock;
    public Stage1Files(Stage1Db db,Stage1Context context,Stage1Access access,Stage1Mutations mutations,Stage1Settings settings,@Qualifier("stage1Clock") Clock clock){this.db=db;this.context=context;this.access=access;this.mutations=mutations;this.settings=settings;this.clock=clock;}
    public Object upload(String metadata,MultipartFile file){
        var actor=context.current();var f=new Fields(metadata).only("requestId","purpose");String purpose=f.choice("purpose",Set.of("GOODS","QUALIFICATION","AGREEMENT","CHANNEL"),true);
        requirePurpose(actor,purpose,true);
        int maximum=purpose.equals("GOODS")?5*1024*1024:10*1024*1024;
        Stage1Exception.require(file!=null&&!file.isEmpty()&&file.getSize()<=maximum,422,"FILE_SIZE_INVALID");
        byte[] bytes;
        try(InputStream input=file.getInputStream()){bytes=input.readNBytes(maximum+1);}catch(IOException e){throw new Stage1Exception(503,"FILE_UNAVAILABLE");}
        Stage1Exception.require(bytes.length>0&&bytes.length<=maximum,422,"FILE_SIZE_INVALID");
        String mime=detect(bytes,purpose.equals("GOODS"));String digest=digest(bytes);
        var command=map("requestId",f.requestId(),"purpose",purpose,"sha256",digest);
        return mutations.run("file.upload",command,actor.store(),()->{
            String id=db.id(),key=UUID.randomUUID().toString();boolean privateFile=!purpose.equals("GOODS");
            Path target=storage(privateFile,key);
            try{Files.write(target,bytes,StandardOpenOption.CREATE_NEW);}
            catch(IOException e){throw new Stage1Exception(503,"FILE_UNAVAILABLE");}
            // A failed database transaction cannot leave a successful unowned upload.
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization(){
                @Override public void afterCompletion(int status){if(status!=STATUS_COMMITTED)try{Files.deleteIfExists(target);}catch(IOException ignored){/* Unreferenced own-run file; no public route can read it. */}}
            });
            db.nativeInsert("li_file",map("id",id,"delete_flag",false,"create_by",actor.id(),"create_time",clock.instant(),"name",id,"file_type",mime,"url","stage1-file:"+id,"file_size",bytes.length,"file_category",purpose));
            db.update("INSERT INTO li_p0_file_control(file_id,actor_id,identity_domain,store_id,purpose,private_file,storage_key,mime_type,byte_size,sha256,status,created_at,expires_at) VALUES(?,?,?,?,?,?,?,?,?,?,'UPLOADED',?,?)",id,actor.id(),actor.domain().name(),actor.store(),purpose,privateFile,key,mime,bytes.length,digest,clock.instant(),clock.instant().plusSeconds(86400));
            return map("id",id,"purpose",purpose,"mimeType",mime,"size",bytes.length,"status","UPLOADED","private",privateFile);
        });
    }
    public void validate(List<String> files,String purpose,String sourceActor,String store){
        Stage1Exception.require(!files.isEmpty()&&files.size()<=10,422,"FILE_REQUIRED");
        for(String id:files){
            var row=db.required("SELECT * FROM li_p0_file_control WHERE file_id=?",id);
            Stage1Exception.require(purpose.equals(id(row,"purpose"))&&(sourceActor.equals(id(row,"actor_id"))||store!=null&&store.equals(id(row,"store_id"))),404,"FILE_UNAVAILABLE");
            Stage1Exception.require(row.get("store_id")==null||Objects.equals(store,id(row,"store_id")),404,"FILE_UNAVAILABLE");
            Stage1Exception.require(Set.of("UPLOADED","BOUND").contains(id(row,"status"))&&(!"UPLOADED".equals(id(row,"status"))||instant(row.get("expires_at")).isAfter(clock.instant())),422,"FILE_UNAVAILABLE");
            readBytes(row);
        }
    }
    public void bind(List<String> ids,String purpose,String sourceActor,String store,String object){
        validate(ids,purpose,sourceActor,store);
        for(String id:ids)db.update("UPDATE li_p0_file_control SET status='BOUND',store_id=COALESCE(store_id,?),bound_object_id=COALESCE(bound_object_id,?),expires_at=NULL WHERE file_id=?",store,object,id);
    }
    /** Current evidence check, without granting a download or changing file state. */
    public boolean boundEvidenceAvailable(Object references,String purpose,String store){
        var refs=array(references);if(refs.isEmpty())return false;
        for(var reference:refs){
            var row=db.one("SELECT * FROM li_p0_file_control WHERE file_id=? AND store_id=? AND purpose=? AND private_file=1 AND status='BOUND'",reference.getAsString(),store,purpose);
            if(row==null)return false;
            try{readBytes(row);}catch(Stage1Exception unavailable){return false;}
        }
        return true;
    }
    public record Download(String id,String mime,byte[] bytes,boolean privateFile){@Override public String toString(){return "Stage1File[REDACTED]";}}
    /** Internal dispatcher: only a committed application's bound, private attachments from its own Store. */
    public Download channelMaterial(String id,String store){
        var row=db.required("SELECT * FROM li_p0_file_control WHERE file_id=? AND store_id=? AND private_file=1 AND status='BOUND' AND purpose IN ('QUALIFICATION','CHANNEL')",id,store);
        return new Download(id,id(row,"mime_type"),readBytes(row),true);
    }
    public Download read(String id){
        var actor=context.current();var row=db.required("SELECT * FROM li_p0_file_control WHERE file_id=?",id);String purpose=id(row,"purpose");
        if(actor.domain()==SessionDomain.MANAGER){
            requirePurpose(actor,purpose,false);
            boolean own=actor.domain().name().equals(id(row,"identity_domain"))&&actor.id().equals(id(row,"actor_id"));
            if(!own){
                Stage1Exception.require("BOUND".equals(id(row,"status")),404,"RESOURCE_NOT_FOUND");
                if(purpose.equals("QUALIFICATION")){
                    var permissions=access.managerPermissions(actor.id());
                    boolean reviewable=db.count("SELECT COUNT(*) FROM li_p0_application WHERE id=? AND status IN ('SUBMITTED','APPROVED','REJECTED')",row.get("bound_object_id"))>0;
                    boolean approved=db.count("SELECT COUNT(*) FROM li_p0_qualification WHERE status='APPROVED' AND store_id=? AND JSON_CONTAINS(attachment_refs,JSON_QUOTE(?))",row.get("store_id"),id)>0;
                    Stage1Exception.require(approved||permissions.contains("admission.review")&&reviewable,404,"RESOURCE_NOT_FOUND");
                }
            }
        }
        else {
            boolean own=actor.id().equals(id(row,"actor_id")),store=actor.store()!=null&&actor.store().equals(id(row,"store_id"));
            Stage1Exception.require(own||store,404,"RESOURCE_NOT_FOUND");
            requirePurpose(actor,purpose,false);
        }
        mutations.audit("file.read",id(row,"store_id"),id,"read_"+UUID.randomUUID(),null,null,null);
        return new Download(id,id(row,"mime_type"),readBytes(row),flag(row,"private_file"));
    }
    /** Read-only product media. Private documents and images belonging to another product are never served here. */
    public Download goodsImage(String goodsId,String fileId){
        var actor=context.current();
        Stage1Exception.require(actor.domain()!=SessionDomain.APPLICANT,403,"FORBIDDEN");
        var goods=db.required("SELECT c.store_id,c.review_status,c.content_version,c.approved_version,c.forced_off,g.delete_flag,g.auth_flag,g.market_enable,d.file_refs FROM li_p0_goods_control c JOIN li_p0_goods_draft d ON d.goods_id=c.goods_id LEFT JOIN li_goods g ON g.id=c.goods_id WHERE c.goods_id=?",goodsId);
        if(actor.domain()==SessionDomain.STORE)Stage1Exception.require(actor.store().equals(id(goods,"store_id")),404,"RESOURCE_NOT_FOUND");
        if(actor.domain()==SessionDomain.RESIDENT)Stage1Exception.require("APPROVED".equals(id(goods,"review_status"))&&goods.get("approved_version")!=null&&number(goods,"approved_version")==number(goods,"content_version")&&!flag(goods,"delete_flag")&&"PASS".equals(id(goods,"auth_flag"))&&!flag(goods,"forced_off")&&"UPPER".equals(id(goods,"market_enable")),404,"RESOURCE_NOT_FOUND");
        Stage1Exception.require(array(goods.get("file_refs")).asList().stream().anyMatch(v->v.getAsString().equals(fileId)),404,"RESOURCE_NOT_FOUND");
        var row=db.required("SELECT * FROM li_p0_file_control WHERE file_id=? AND store_id=? AND purpose='GOODS' AND private_file=0 AND status='BOUND'",fileId,goods.get("store_id"));
        return new Download(fileId,id(row,"mime_type"),readBytes(row),false);
    }
    private byte[] readBytes(Map<String,Object> row){
        try{
            Path file=storage(flag(row,"private_file"),id(row,"storage_key"));
            if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)!=number(row,"byte_size"))throw new IOException();
            byte[] bytes=Files.readAllBytes(file);if(!digest(bytes).equals(id(row,"sha256")))throw new IOException();return bytes;
        }catch(IOException ex){throw new Stage1Exception(503,"FILE_UNAVAILABLE");}
    }
    private Path storage(boolean privateFile,String key){
        if(key==null||!key.matches("[a-f0-9-]{36}"))throw new Stage1Exception(503,"FILE_UNAVAILABLE");
        try{Path root=Path.of(settings.getFileRoot()).toRealPath(),directory=root.resolve(privateFile?"private":"public").toRealPath();if(!directory.startsWith(root))throw new IOException();return directory.resolve(key);}
        catch(IOException|RuntimeException ex){throw new Stage1Exception(503,"FILE_UNAVAILABLE");}
    }
    private void requirePurpose(Stage1Context.Actor actor,String purpose,boolean write){
        Set<String> grants;
        if(actor.domain()==SessionDomain.APPLICANT){Stage1Exception.require(purpose.equals("QUALIFICATION"),403,"FORBIDDEN");return;}
        if(actor.domain()==SessionDomain.RESIDENT)throw new Stage1Exception(403,"FORBIDDEN");
        grants=actor.domain()==SessionDomain.MANAGER?access.managerPermissions(actor.id()):access.storePermissions(actor.user());
        boolean permit=switch(purpose){
            case "QUALIFICATION"->actor.domain()==SessionDomain.MANAGER?grants.contains("admission.review")||!write&&grants.contains("store.read"):grants.contains(write?"profile.write":"profile.read");
            case "GOODS"->actor.domain()==SessionDomain.STORE&&grants.contains(write?"goods.write":"goods.read")||!write&&actor.domain()==SessionDomain.MANAGER&&(grants.contains("goods.review")||grants.contains("goods.supervise"));
            case "AGREEMENT"->actor.domain()==SessionDomain.MANAGER?grants.contains(write?"agreement.write":"agreement.read"):!write&&grants.contains("cooperation.read");
            case "CHANNEL"->actor.domain()==SessionDomain.MANAGER&&grants.contains("channel.onboard");
            default->false;
        };Stage1Exception.require(permit,403,"FORBIDDEN");
    }
    static String detect(byte[] b,boolean goods){
        boolean png=b.length>=24&&Arrays.equals(Arrays.copyOf(b,8),new byte[]{(byte)137,80,78,71,13,10,26,10});
        boolean jpeg=b.length>=4&&(b[0]&255)==255&&(b[1]&255)==216&&(b[2]&255)==255;
        if(png||jpeg){try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(b))){var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException();var reader=readers.next();try{reader.setInput(input);int w=reader.getWidth(0),h=reader.getHeight(0);if(w<=0||h<=0||(long)w*h>40_000_000)throw new IOException();if(reader.read(0)==null)throw new IOException();}finally{reader.dispose();}return png?"image/png":"image/jpeg";}catch(IOException|RuntimeException ex){throw Stage1Exception.field("file","IMAGE_INVALID");}}
        if(goods&&b.length>=30&&new String(b,0,4,StandardCharsets.US_ASCII).equals("RIFF")&&new String(b,8,4,StandardCharsets.US_ASCII).equals("WEBP")){
            long length=Integer.toUnsignedLong(ByteBuffer.wrap(b,4,4).order(ByteOrder.LITTLE_ENDIAN).getInt())+8;
            String chunk=new String(b,12,4,StandardCharsets.US_ASCII);long size=Integer.toUnsignedLong(ByteBuffer.wrap(b,16,4).order(ByteOrder.LITTLE_ENDIAN).getInt());
            Stage1Exception.require(length==b.length&&Set.of("VP8 ","VP8L","VP8X").contains(chunk)&&size>0&&size<=b.length-20,422,"IMAGE_INVALID");Stage1FileCodecs.webp(b);return "image/webp";
        }
        if(!goods&&b.length>32){String text=new String(b,StandardCharsets.ISO_8859_1);if((text.startsWith("%PDF-1.")||text.startsWith("%PDF-2."))&&text.contains("%%EOF")){Stage1FileCodecs.pdf(b);return "application/pdf";}}
        throw Stage1Exception.field("file","TYPE_NOT_ALLOWED");
    }
}
