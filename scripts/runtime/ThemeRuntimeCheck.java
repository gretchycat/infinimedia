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
    static void svgRegressionChecks() throws Exception {
        StringBuilder colors=new StringBuilder();int count=300;
        for(int i=0;i<count;i++)colors.append("<i:color index='"+i+"' value='"+String.format(java.util.Locale.ROOT,"#%06x",i*0x010203&0xffffff)+"'/>");
        String source="<svg xmlns='http://www.w3.org/2000/svg' xmlns:i='https://infinicanvas.org/svg' width='4' height='4'><metadata><i:palette id='p' size='300'>"+colors+"</i:palette><i:cycle start='0' end='299' rate='24'/></metadata><rect width='4' height='4' i:palette-index='0'/></svg>";
        BitmapFrame frame=decode(source.getBytes(java.nio.charset.StandardCharsets.UTF_8),"complete-cycle.svg");
        check(frame.getAnimation().getDescription().startsWith("Rasterized"),"Flat indexed SVG uses raster playback");
        int[] output=new int[16];
        for(int step=0;step<=600;step++){
            frame.getAnimation().render((long)Math.ceil(step*1e9/24.0),output,true);
            check(output[5]==(0xff000000 | Math.floorMod(-step,count)*0x010203&0xffffff),"Complete forward cycle step "+step);
        }
        pass("svg-raster-complete-300-color-rotations");
        BitmapFrame still=CanvasStreams.decodeBytes(source.getBytes(java.nio.charset.StandardCharsets.UTF_8),"still.svg",2048,false);
        check(still.getAnimation()==null && still.getBitmap().getPixel(1,1)==Color.BLACK,"Still-only decode skips playback state");
        pass("svg-still-preview-skips-raster-animation");
        source="<svg xmlns='http://www.w3.org/2000/svg' xmlns:i='https://infinicanvas.org/svg' width='16' height='8'><metadata><i:palette size='4'><i:color index='0' value='#f00'/><i:color index='1' value='#0f0'/><i:color index='2' value='#f00'/><i:color index='3' value='#00f'/></i:palette><i:cycle start='0' end='3' rate='1'/></metadata><rect width='16' height='8' fill='#202020'/><rect width='8' height='8' i:palette-index='0'/><rect x='8' width='8' height='8' opacity='.5' i:palette-index='2'/></svg>";
        frame=decode(source.getBytes(java.nio.charset.StandardCharsets.UTF_8),"duplicate-indices.svg");output=new int[128];
        frame.getAnimation().render(1_000_000_000L,output,true);
        check(output[3+3*16]==Color.BLUE,"First red index rotates to blue");
        int mixed=output[12+3*16];check(Color.green(mixed)>140 && Color.red(mixed)<20 && Color.blue(mixed)<20,"Second red index rotates to green with opacity");
        pass("svg-raster-duplicate-indices-and-opacity");
        source="<svg xmlns='http://www.w3.org/2000/svg' xmlns:i='https://infinicanvas.org/svg' width='16' height='8'><metadata><i:palette size='2'><i:color index='0' value='#f00'/><i:color index='1' value='#00f'/></i:palette><i:cycle start='0' end='1' rate='1'/></metadata><defs><linearGradient id='g'><stop offset='0' i:palette-index='0'/><stop offset='1' i:palette-index='1'/></linearGradient></defs><rect width='16' height='8' fill='url(#g)'/></svg>";
        frame=decode(source.getBytes(java.nio.charset.StandardCharsets.UTF_8),"gradient.svg");
        check(!frame.getAnimation().getDescription().startsWith("Rasterized"),"Animated gradient retains vector fallback");
        int initial=frame.getBitmap().getPixel(2,3);output=new int[128];frame.getAnimation().render(1_000_000_000L,output,true);
        check(output[2+3*16]!=initial,"Animated gradient still changes");pass("svg-animated-gradient-fallback");
        frame=decode(bytes("infini.svg"),"infini.svg");
        check(frame.getAnimation().getDescription().startsWith("Rasterized"),"Supplied complex SVG uses raster playback");
        int[] initialPixels=pixels(frame.getBitmap());output=initialPixels.clone();
        SvgPaletteCycling.Definition d=SvgPaletteCycling.INSTANCE.parse(bytes("infini.svg"));
        frame.getAnimation().render((long)Math.ceil(1e9/d.getCycles().get(0).getRate()),output,true);
        int sample=-1;
        for(int i=0;i<output.length;i++)if(initialPixels[i]==d.getPalette()[64] && output[i]==d.getPalette()[255]){sample=i;break;}
        check(sample>=0,"Pure indexed source pixel located");
        for(int step=0;step<192;step++){
            frame.getAnimation().render((long)Math.ceil(step*1e9/d.getCycles().get(0).getRate()),output,true);
            check(output[sample]==d.getPalette()[64+Math.floorMod(-step,192)],"Supplied SVG phase "+step);
        }
        pass("supplied-svg-all-192-palette-phases");
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
        check(large.getAnimation()!=null,"Palette above 256 colors is retained");
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
    static class SlowFrame implements FramePacedBitmapAnimation {
        final List<Long> starts=Collections.synchronizedList(new ArrayList<>());
        public int getWidth(){return 1;} public int getHeight(){return 1;}
        public long getDurationNanos(){return 0;} public boolean getNeedsMoreRendering(){return false;}
        public boolean getNeedsContinuousFrames(){return true;} public String getDescription(){return "pacing-test";}
        public long getFrameIntervalMillis(){return 20;}
        public boolean render(long time,int[] output,boolean force){starts.add(SystemClock.uptimeMillis());SystemClock.sleep(12);output[0]=Color.RED;return true;}
        void verify(String name){
            List<Long> times; synchronized(starts){times=new ArrayList<>(starts);}
            check(times.size()>=5,"Pacing probe received frames: "+name);
            long[] gaps=new long[times.size()-1];for(int i=1;i<times.size();i++)gaps[i-1]=times.get(i)-times.get(i-1);
            Arrays.sort(gaps);System.out.println("PACING "+name+" median_interval_ms="+gaps[gaps.length/2]);
            check(gaps[gaps.length/2]<40,"Renderer time must count toward the frame deadline: "+name);pass(name);
        }
    }
    static void pacingChecks(Runnable done) {
        Handler main=new Handler(Looper.getMainLooper());SlowFrame first=new SlowFrame();
        com.infinifolder.viewer.ImageAnimationPlayback playback=new com.infinifolder.viewer.ImageAnimationPlayback(first,Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888),0,()->Unit.INSTANCE,error->{throw new RuntimeException(error);},null);
        playback.play();later(main,450,()->{
            playback.close();first.verify("shared-image-frame-deadline");SlowFrame second=new SlowFrame();
            com.infinifolder.viewer.PlaylistImageAnimationPlayback media=new com.infinifolder.viewer.PlaylistImageAnimationPlayback(second,Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888),()->Unit.INSTANCE,error->{throw new RuntimeException(error);});
            media.play();later(main,450,()->{media.close();second.verify("media-image-frame-deadline");done.run();});
        });
    }
    static void lifecycleChecks() throws Exception {
        final byte[] svg=bytes("infini.svg");final int[] changed={0};
        SkinImageCache cache=new SkinImageCache(name->Collections.singletonList(new Pair<>("main.svg",svg)),()->{changed[0]++;return Unit.INSTANCE;});
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
            Looper.prepareMainLooper();
            if (args.length>2 && args[2].equals("benchmark")) {
                long start=System.nanoTime();BitmapFrame frame=decode(bytes("infini.svg"),"infini.svg");
                double decodeMs=(System.nanoTime()-start)/1e6;
                int[] output=new int[frame.getBitmap().getWidth()*frame.getBitmap().getHeight()];
                for(int i=0;i<8;i++)frame.getAnimation().render((i+1)*50_000_000L,output,true);
                long[] times=new long[48];
                for(int i=0;i<times.length;i++){start=System.nanoTime();frame.getAnimation().render((i+9)*50_000_000L,output,true);times[i]=System.nanoTime()-start;}
                SvgPaletteCycling.Definition definition=SvgPaletteCycling.INSTANCE.parse(bytes("infini.svg"));
                com.caverock.androidsvg.SVG reference=com.caverock.androidsvg.SVG.getFromString(definition.getSvg());
                reference.setDocumentWidth("100%");reference.setDocumentHeight("100%");
                Bitmap ref=Bitmap.createBitmap(frame.getBitmap().getWidth(),frame.getBitmap().getHeight(),Bitmap.Config.ARGB_8888);
                int[] exact=new int[output.length];
                for(long time:new long[]{0L,150_000_000L,1_700_000_000L,4_000_000_000L}) {
                    frame.getAnimation().render(time,output,true);ref.eraseColor(0);
                    reference.renderToCanvas(new Canvas(ref),com.caverock.androidsvg.RenderOptions.create().preserveAspectRatio(com.caverock.androidsvg.PreserveAspectRatio.LETTERBOX).css(definition.css(time)));
                    ref.getPixels(exact,0,ref.getWidth(),0,0,ref.getWidth(),ref.getHeight());
                    long error=0;int maximum=0,changed=0;
                    for(int i=0;i<output.length;i++){if(output[i]!=exact[i])changed++;for(int shift=0;shift<=16;shift+=8){int diff=Math.abs((output[i]>>>shift&255)-(exact[i]>>>shift&255));error+=diff;maximum=Math.max(maximum,diff);}}
                    check(error/(3.0*output.length)<1.0 && maximum<64,"Raster approximation stays within the supplied fixture tolerance");
                    System.out.println("ACCURACY time_ns="+time+" mean_rgb_error="+(error/(3.0*output.length))+" max_rgb_error="+maximum+" changed_pixels="+changed);
                    if(time==1_700_000_000L){Bitmap actual=Bitmap.createBitmap(output,ref.getWidth(),ref.getHeight(),Bitmap.Config.ARGB_8888);png(actual,"raster-phase.png");png(ref,"reference-phase.png");}
                }
                Arrays.sort(times);System.out.println("BENCH decode_ms="+decodeMs+" frame_median_ms="+times[times.length/2]/1e6+" frame_p95_ms="+times[(times.length*95/100)]/1e6+" frame_max_ms="+times[times.length-1]/1e6+" renderer="+frame.getAnimation().getDescription());System.exit(0);
            }
            rasterChecks();svgRegressionChecks();
            if (args.length>2 && args[2].equals("decoder")){System.out.println("All decoder checks passed");System.exit(0);}
            pacingChecks(()->{try{lifecycleChecks();}catch(Exception error){throw new RuntimeException(error);}});Looper.loop();
        } catch(Throwable error) { error.printStackTrace();System.exit(1); }
    }
}
