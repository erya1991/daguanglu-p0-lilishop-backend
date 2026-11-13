import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import javax.imageio.ImageIO;

/** Creates ordinary local slider source images; the server still generates and verifies challenges. */
class GenerateCaptchaAssets {
    private static void write(BufferedImage image, Path target) throws Exception {
        try (var out=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)) {
            if (!ImageIO.write(image,"png",out)) throw new IllegalStateException("PNG writer unavailable");
        }
    }
    public static void main(String[] args) throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Expected private captcha directory");
        Path dir=Path.of(args[0]);
        Files.createDirectories(dir);
        var base=new BufferedImage(320,160,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=base.createGraphics();
        g.setPaint(new GradientPaint(0,0,new Color(32,91,95),320,160,new Color(240,193,120)));
        g.fillRect(0,0,320,160);
        for(int i=0;i<12;i++) {
            g.setColor(new Color((45+i*31)%200+30,(80+i*23)%170+45,(100+i*29)%150+55));
            g.fillRoundRect((i*47)%300,(i*71)%145,35+(i%3)*8,24+(i%4)*6,12,12);
        }
        g.dispose();
        write(base,dir.resolve("base.png"));
        for(int i=1;i<=2;i++) {
            var mask=new BufferedImage(48,48,BufferedImage.TYPE_INT_ARGB);
            Graphics2D m=mask.createGraphics();
            m.setColor(Color.BLACK);
            m.fillRoundRect(6,6,35,35,8,8);
            if(i==1)m.fillOval(17,0,14,14); else m.fillOval(34,17,14,14);
            m.dispose();
            write(mask,dir.resolve("mask-"+i+".png"));
        }
    }
}
