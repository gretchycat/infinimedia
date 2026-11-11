import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.*;
import com.infinifolder.preview.*;
import com.infinifolder.theme.SkinImageCache;
import com.infinifolder.viewer.WinampVisualFrameLayout;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import kotlin.Pair;
import kotlin.Unit;

/** Real Android raster/lifecycle checks against the built Media APK, without Activity bootstrap. */
public class ThemeRuntimeCheck {
    static Path root, out;
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static void pass(String name) { System.out.println("PASS theme." + name); }
    static BitmapFrame decode(byte[] bytes, String name) {
        BitmapFrame frame = CanvasStreams.decodeBytes(bytes, name);
        check(frame != null, "Decoded " + name);
        return frame;
    }
    static byte[] bytes(String name) throws Exception { return Files.readAllBytes(root.resolve(name)); }
    static int[] pixels(Bitmap b) { int[] p = new int[b.getWidth()*b.getHeight()]; b.getPixels(p,0,b.getWidth(),0,0,b.getWidth(),b.getHeight()); return p; }
    static void png(Bitmap b, String name) throws Exception { try (OutputStream stream=Files.newOutputStream(out.resolve(name))) { check(b.compress(Bitmap.CompressFormat.PNG,100,stream),"PNG export"); } }
    static byte[] svg(boolean reverse, int size) {
        StringBuilder colors = new StringBuilder();
        for (int i=0;i<size;i++) colors.append("<i:color index='"+i+"' value='"+(i==0?"#f00":i==1?"#0f0":"#00f")+"'/>");
        return ("<svg xmlns='http://www.w3.org/2000/svg' xmlns:i='https://infinicanvas.org/svg' width='16' height='8' viewBox='0 0 16 8'><metadata>"+
            "<i:palette id='p' size='"+size+"'>"+colors+"</i:palette><i:cycle palette='p' start='0' end='"+(size-1)+"' rate='1' direction='"+(reverse?"reverse":"forward")+"'/></metadata>"+
            "<rect width='8' height='8' fill='#f00' i:palette-index='0'/><rect x='8' width='8' height='8' fill='#f00'/></svg>").getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    static void rasterChecks() throws Exception {
        for (boolean reverse : new boolean[]{false,true}) {
            BitmapFrame f=decode(svg(reverse,3),"cycling.svg");
            check(f.getBitmap().getWidth()==16 && f.getBitmap().getHeight()==8,"SVG intrinsic dimensions");
            check(f.getBitmap().getPixel(3,3)==Color.RED,"Indexed initial paint");
            BitmapAnimation animation=f.getAnimation();check(animation!=null,"SVG animation available");
            int[] p=new int[128];animation.render(1_000_000_000L,p,true);
            check(p[3+3*16]==(reverse?Color.GREEN:Color.BLUE),"SVG cycle direction");
            check(p[12+3*16]==Color.RED,"Untagged equal-RGB paint remains fixed");
            check(!animation.render(1_000_000_000L,p,false),"Unchanged SVG frame skipped");
        }
        pass("svg-indexed-paint-direction-and-static-paint");
        BitmapFrame large=decode(svg(false,300),"palette.svg");
        check(large.getAnimation()!=null,"Palette above 256 colors remains vector");
        pass("svg-palette-above-256");
        BitmapFrame supplied=decode(bytes("infini.svg"),"infini.svg");
        check(supplied.getAnimation()!=null,"Supplied SVG metadata animated");
        int[] before=pixels(supplied.getBitmap()),after=before.clone();
        supplied.getAnimation().render(150_000_000L,after,true);
        check(!Arrays.equals(before,after),"Supplied SVG pixels cycle");
        png(supplied.getBitmap(),"infini-svg-initial.png");
        pass("supplied-infini-svg-raster");
        BitmapFrame iff=decode(bytes("theme/main.iff"),"main.iff");
        check(iff.getBitmap().getWidth()==275 && iff.getBitmap().getHeight()==116,"Corrected IFF canvas");
        before=pixels(iff.getBitmap());for(int p:before)check(Color.alpha(p)==255,"Corrected full faceplate is opaque");
        check(iff.getColorCycling()!=null,"IFF cycling available");after=before.clone();
        iff.getColorCycling().render(200_000_000L,after,true);
        check(!Arrays.equals(before,after),"IFF pixels cycle");
        check(before[0]==after[0] && before[274]==after[274],"Faceplate background stays fixed");
        pass("corrected-iff-cycling-and-opaque-background");
        Bitmap input=Bitmap.createBitmap(4,3,Bitmap.Config.ARGB_8888);input.eraseColor(Color.MAGENTA);
        ByteArrayOutputStream stream=new ByteArrayOutputStream();input.compress(Bitmap.CompressFormat.PNG,100,stream);
        BitmapFrame png=decode(stream.toByteArray(),"not-a-bitmap.iff");
        check(png.getBitmap().getWidth()==4 && png.getBitmap().getPixel(1,1)==Color.MAGENTA,"PNG content signature beats filename");
        pass("format-discovery-ignores-misleading-extension");
        Bitmap overlay=Bitmap.createBitmap(4,3,Bitmap.Config.ARGB_8888);overlay.setPixel(1,1,Color.GREEN);
        ByteArrayOutputStream overlayStream=new ByteArrayOutputStream();overlay.compress(Bitmap.CompressFormat.PNG,100,overlayStream);
        SkinImageCache layers=new SkinImageCache(name->Arrays.asList(new Pair<>("base.png",stream.toByteArray()),new Pair<>("overlay.png",overlayStream.toByteArray())),()->Unit.INSTANCE);
        Bitmap composite=layers.bitmap("main.bmp");
        check(composite!=null && composite.getPixel(0,0)==Color.MAGENTA && composite.getPixel(1,1)==Color.GREEN,"Ordered transparent layers composite correctly");
        layers.clear();check(!composite.isRecycled(),"Published bitmap remains usable after cache clear");
        pass("ordered-layers-and-published-bitmap-lifetime");
        Bitmap video=decode(bytes("theme/video.bmp"),"video.bmp").getBitmap();
        Bitmap gen=decode(bytes("theme/gen.bmp"),"gen.bmp").getBitmap();
        for(int width:new int[]{275,550}) {
            int side=width*7/275,title=width*20/275,bottom=width*14/275;
            int height=title+(width-2*side)*9/16+bottom;
            Bitmap frame=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
            Paint paint=new Paint();paint.setFilterBitmap(false);
            WinampVisualFrameLayout.drawFrame(new Canvas(frame),width,height,false,video,gen,paint,Color.DKGRAY,Color.GRAY);
            check(frame.getPixel(width/2,title+10)==Color.BLACK,"Content remains unobscured");
            check(frame.getPixel(0,title+1)==gen.getPixel(127,42),"GEN left edge source");
            check(frame.getPixel(width-1,title+1)==gen.getPixel(147,42),"GEN right edge source width="+width+" actual="+Integer.toHexString(frame.getPixel(width-1,title+1))+" source="+Integer.toHexString(gen.getPixel(147,42)));

            png(frame,"visual-media-frame-"+width+".png");
        }
        pass("video-title-gen-complete-frame-and-resizing");
    }
    static void later(Handler handler,long delay,Runnable action) {
        handler.postDelayed(()->{try{action.run();}catch(Throwable e){e.printStackTrace();System.exit(1);}},delay);
    }
    static void lifecycleChecks() throws Exception {
        final byte[] iff=bytes("theme/main.iff");final int[] changed={0};
        SkinImageCache cache=new SkinImageCache(name->Collections.singletonList(new Pair<>("main.iff",iff)),()->{changed[0]++;return Unit.INSTANCE;});
        Bitmap image=cache.bitmap("main.bmp");check(image!=null,"Cache image decoded");
        check(image==cache.bitmap("main.iff"),"Logical role shares cache");
        cache.setPlaying(true);Handler main=new Handler(Looper.getMainLooper());
        later(main,450,()->{
            check(changed[0]>1,"Visible cache animates");cache.setPlaying(false);final int paused=changed[0];final int[] frozen=pixels(image);
            later(main,200,()->{
                check(changed[0]==paused && Arrays.equals(frozen,pixels(image)),"Paused cache stops publishing");
                cache.setPlaying(true);
                later(main,300,()->{
                    check(changed[0]>paused,"Cache resumes");cache.clear();final int closed=changed[0];
                    later(main,200,()->{
                        check(changed[0]==closed,"Clear discards queued frames");
                        pass("cache-play-pause-resume-clear");System.out.println("All theme runtime checks passed");System.exit(0);
                    });
                });
            });
        });
    }
    public static void main(String[] args) {
        try {
            root=Paths.get(args[0]);out=Paths.get(args[1]);Files.createDirectories(out);
            // app_process bypasses zygote font preloading; AndroidSVG initializes a Paint even without text.
            android.graphics.fonts.Font font=new android.graphics.fonts.Font.Builder(new File("/system/fonts/Roboto-Regular.ttf")).build();
            android.graphics.fonts.FontFamily family=new android.graphics.fonts.FontFamily.Builder(font).build();
            Map<String,android.graphics.fonts.FontFamily[]> fallback=new HashMap<>();
            for(String name:new String[]{"sans-serif","serif","monospace"})fallback.put(name,new android.graphics.fonts.FontFamily[]{family});
            Map<String,android.graphics.Typeface> fonts=new HashMap<>();
            Class<?> tf=Class.forName("android.graphics.Typeface");
            tf.getDeclaredMethod("initSystemDefaultTypefaces",Map.class,List.class,Map.class).invoke(null,fallback,Collections.emptyList(),fonts);
            try { tf.getDeclaredMethod("setSystemFontMap",Map.class,List.class).invoke(null,fonts,Collections.emptyList()); }
            catch(NoSuchMethodException older) { tf.getDeclaredMethod("setSystemFontMap",Map.class).invoke(null,fonts); }
            Looper.prepareMainLooper();rasterChecks();lifecycleChecks();Looper.loop();
        } catch(Throwable error) { error.printStackTrace();System.exit(1); }
    }
}
