package cn.lili.modules.community.p0.support;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

public class Stage1FileCodecsTest {
    public static byte[] pdf(String catalogExtra){
        var bytes=new ByteArrayOutputStream();var offsets=new ArrayList<Integer>();write(bytes,"%PDF-1.7\n");
        var objects=List.of("<< /Type /Catalog /Pages 2 0 R "+catalogExtra+" >>","<< /Type /Pages /Kids [3 0 R] /Count 1 >>","<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] /Resources << >> /Contents 4 0 R >>","<< /Length 0 >>\nstream\n\nendstream");
        for(int i=0;i<objects.size();i++){offsets.add(bytes.size());write(bytes,(i+1)+" 0 obj\n"+objects.get(i)+"\nendobj\n");}
        int xref=bytes.size();write(bytes,"xref\n0 5\n0000000000 65535 f \n");for(int offset:offsets)write(bytes,String.format(Locale.ROOT,"%010d 00000 n \n",offset));write(bytes,"trailer\n<< /Size 5 /Root 1 0 R >>\nstartxref\n"+xref+"\n%%EOF\n");return bytes.toByteArray();
    }
    static void write(ByteArrayOutputStream out,String text){out.writeBytes(text.getBytes(StandardCharsets.US_ASCII));}
    @Test void completePdfParsesButHeaderOnlyForgeryAndActiveCatalogAreRejected(){
        assertEquals("application/pdf",Stage1Files.detect(pdf(""),false));
        assertThrows(Stage1Exception.class,()->Stage1Files.detect("%PDF-1.7\n/Type /Catalog /Type /Page\n%%EOF".getBytes(StandardCharsets.US_ASCII),false));
        assertThrows(Stage1Exception.class,()->Stage1Files.detect(pdf("/OpenAction << /S /JavaScript /JS (app.alert\\(1\\)) >>"),false));
        // PDF name escaping must not bypass the parsed dictionary check.
        assertThrows(Stage1Exception.class,()->Stage1Files.detect(pdf("/Open#41ction << /S /Java#53cript /J#53 (x) >>"),false));
        assertThrows(Stage1Exception.class,()->Stage1Files.detect(pdf(""),true));
    }
    @Test void actualWebpDecoderAcceptsPixelsAndRejectsForgedCompressedPayload(){
        byte[] good=Base64.getDecoder().decode("UklGRiIAAABXRUJQVlA4IBYAAAAwAQCdASoBAAEADsD+JaQAA3AAAAAA");
        assertEquals("image/webp",Stage1Files.detect(good,true));byte[] corrupt=good.clone();Arrays.fill(corrupt,20,corrupt.length,(byte)0);assertThrows(Stage1Exception.class,()->Stage1Files.detect(corrupt,true));
    }
}
