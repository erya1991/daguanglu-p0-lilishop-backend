package cn.lili.modules.community.p0.support;

import javax.imageio.ImageReader;
import javax.imageio.ImageIO;
import java.io.*;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static cn.lili.modules.community.p0.support.Stage1Json.*;

/** Optional, checksum-pinned file codecs; no downloads, shell commands or global ImageIO registration. */
final class Stage1FileCodecs {
    private static final Set<String> LIBRARIES=Set.of("pdfbox-3.0.8.jar","pdfbox-io-3.0.8.jar","fontbox-3.0.8.jar","imageio-webp-3.15.3.jar","imageio-core-3.15.3.jar","imageio-metadata-3.15.3.jar","common-lang-3.15.3.jar","common-io-3.15.3.jar","common-image-3.15.3.jar");
    private static volatile ClassLoader loaded;
    private static synchronized ClassLoader loader(){
        if(loaded!=null)return loaded;
        try{
            String configured=System.getProperty("p0.s1.codecRoot");if(configured==null)throw new IOException();
            Path directory=Path.of(configured).toRealPath();if(!directory.getFileName().toString().equals("file-codecs")||!directory.getParent().getFileName().toString().equals("runtime"))throw new IOException();
            var manifest=object(Files.readString(directory.resolve("manifest.json")));if(!directory.getParent().getParent().getFileName().toString().equals(manifest.get("runId").getAsString()))throw new IOException();
            var urls=new ArrayList<java.net.URL>();var seen=new HashSet<String>();
            for(var item:manifest.getAsJsonArray("artifacts")){var row=item.getAsJsonObject();String name=row.get("file").getAsString();if(!LIBRARIES.contains(name)||!seen.add(name))throw new IOException();Path file=directory.resolve(name);if(Files.isSymbolicLink(file)||!Files.isRegularFile(file)||!file.toRealPath().getParent().equals(directory))throw new IOException();String actual=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));if(!actual.equals(row.get("sha256").getAsString()))throw new IOException();urls.add(file.toUri().toURL());}
            if(!seen.equals(LIBRARIES))throw new IOException();loaded=new URLClassLoader(urls.toArray(java.net.URL[]::new),Stage1FileCodecs.class.getClassLoader());return loaded;
        }catch(Exception missing){throw new Stage1Exception(503,"FILE_CODEC_PENDING");}
    }
    static void webp(byte[] bytes){
        ClassLoader codecs=loader();ImageReader reader=null;
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))){
            Object spi=codecs.loadClass("com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi").getConstructor().newInstance();reader=(ImageReader)spi.getClass().getMethod("createReaderInstance",Object.class).invoke(spi,new Object[]{null});reader.setInput(input);int width=reader.getWidth(0),height=reader.getHeight(0);if(width<=0||height<=0||(long)width*height>40_000_000||reader.read(0)==null)throw new IOException();
        }catch(Exception invalid){throw Stage1Exception.field("file","IMAGE_INVALID");}finally{if(reader!=null)reader.dispose();}
    }
    static void pdf(byte[] bytes){
        ClassLoader codecs=loader();Object document=null;
        try{
            document=codecs.loadClass("org.apache.pdfbox.Loader").getMethod("loadPDF",byte[].class).invoke(null,(Object)bytes);
            if(Boolean.TRUE.equals(invoke(document,"isEncrypted"))||(Integer)invoke(document,"getNumberOfPages")<=0)throw new IOException();
            var visited=Collections.newSetFromMap(new IdentityHashMap<Object,Boolean>());long[] budget={0,0};walk(invoke(invoke(document,"getDocumentCatalog"),"getCOSObject"),visited,budget,0);
            // Force page structure resolution. No JavaScript, actions, URL fetches or external resource rendering.
            for(Object page:(Iterable<?>)invoke(document,"getPages")){Object box=invoke(page,"getMediaBox");float width=((Number)invoke(box,"getWidth")).floatValue(),height=((Number)invoke(box,"getHeight")).floatValue();if(!Float.isFinite(width)||!Float.isFinite(height)||width<=0||height<=0)throw new IOException();}
        }catch(Exception invalid){throw Stage1Exception.field("file","PDF_INVALID_OR_ACTIVE");}
        finally{if(document instanceof AutoCloseable closeable)try{closeable.close();}catch(Exception ignored){}}
    }
    private static final Set<String> ACTIVE=Set.of("JavaScript","JS","AA","OpenAction","Launch","EmbeddedFiles","EmbeddedFile","RichMedia","XFA","GoToR","GoToE","SubmitForm","ImportData","Sound","Movie");
    private static void walk(Object value,Set<Object> visited,long[] budget,int depth)throws Exception{
        if(value==null||!visited.add(value))return;if(depth>128||++budget[0]>100000)throw new IOException();String type=value.getClass().getSimpleName();
        if(type.equals("COSObject")){walk(invoke(value,"getObject"),visited,budget,depth+1);return;}
        if(type.equals("COSDictionary")||type.equals("COSStream")){
            for(Object item:(Set<?>)invoke(value,"entrySet")){var entry=(Map.Entry<?,?>)item;String name=(String)invoke(entry.getKey(),"getName");if(ACTIVE.contains(name))throw new IOException();Object child=entry.getValue();if(name.equals("S")&&child!=null&&child.getClass().getSimpleName().equals("COSName")&&ACTIVE.contains((String)invoke(child,"getName")))throw new IOException();walk(child,visited,budget,depth+1);}
            if(type.equals("COSStream"))try(InputStream input=(InputStream)invoke(value,"createInputStream")){byte[] buffer=new byte[8192];for(int size;(size=input.read(buffer))!=-1;){budget[1]+=size;if(budget[1]>64L*1024*1024)throw new IOException();}}
        }else if(type.equals("COSArray"))for(Object child:(Iterable<?>)value)walk(child,visited,budget,depth+1);
    }
    private static Object invoke(Object target,String method)throws Exception{return target.getClass().getMethod(method).invoke(target);}
}
