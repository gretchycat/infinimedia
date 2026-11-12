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
    static void svgStillChecks() throws Exception {
        for(boolean reverse:new boolean[]{false,true}) {
            BitmapFrame frame=decode(svg(reverse,300),"metadata.svg");
            check(frame.getAnimation()==null && frame.getColorCycling()==null,"SVG cycling metadata ignored");
            check(frame.getBitmap().getWidth()==16 && frame.getBitmap().getHeight()==8,"SVG intrinsic dimensions");
            check(frame.getBitmap().getPixel(3,3)==Color.RED,"Explicit static SVG paint retained");
            ByteArrayOutputStream compressed=new ByteArrayOutputStream();
            try(java.util.zip.GZIPOutputStream stream=new java.util.zip.GZIPOutputStream(compressed)){stream.write(svg(reverse,300));}
            BitmapFrame zipped=decode(compressed.toByteArray(),"still.svgz");
            check(zipped.getAnimation()==null && Arrays.equals(pixels(frame.getBitmap()),pixels(zipped.getBitmap())),"SVGZ remains static with identical pixels");
        }
        pass("svg-and-svgz-ignore-cycling-metadata");
        String source="<svg xmlns='http://www.w3.org/2000/svg' width='40' height='20' viewBox='0 0 10 10'><rect width='10' height='10' fill='#0f0'/></svg>";
        BitmapFrame nativeSize=decode(source.getBytes(java.nio.charset.StandardCharsets.UTF_8),"dimensions.svg");
        check(nativeSize.getBitmap().getWidth()==40 && nativeSize.getBitmap().getHeight()==20,"Document dimensions take precedence over viewBox for sprites");
        check(Color.alpha(nativeSize.getBitmap().getPixel(0,10))==0 && nativeSize.getBitmap().getPixel(20,10)==Color.GREEN,"Aspect ratio retained with letterboxing");
        pass("static-svg-document-dimensions-and-aspect-ratio");
        BitmapFrame supplied=decode(bytes("infini.svg"),"infini.svg");
        check(supplied.getAnimation()==null && supplied.getColorCycling()==null,"User SVG remains a still image");
        check(supplied.getBitmap().getWidth()==1800 && supplied.getBitmap().getHeight()==900,"User SVG canvas dimensions retained");
        png(supplied.getBitmap(),"infini-svg-static.png");pass("supplied-svg-static-render");
        for(String name:new String[]{"theme/main.iff","theme/video.bmp"}) {
            byte[] encoded=bytes(name);
            BitmapFrame animated=CanvasStreams.decodeBytes(encoded,name,256,true);
            BitmapFrame preview=CanvasStreams.decodeBytes(encoded,name,256,false);
            check(animated!=null && preview!=null,"Still/animated format supported: "+name);
            check(preview.getAnimation()==null && preview.getColorCycling()==null,"Still preview has no animation state: "+name);
            check(Arrays.equals(pixels(animated.getBitmap()),pixels(preview.getBitmap())),"Still preview preserves initial pixels: "+name);
        }
        pass("still-preview-preserves-iff-and-bmp-pixels");
    }
    static void rasterChecks() throws Exception {
        int[] before,after;
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
    static class SlowFrame implements BitmapAnimation {
        final List<Long> starts=Collections.synchronizedList(new ArrayList<>());
        public int getWidth(){return 1;} public int getHeight(){return 1;}
        public long getDurationNanos(){return 0;} public boolean getNeedsMoreRendering(){return false;}
        public boolean getNeedsContinuousFrames(){return true;} public String getDescription(){return "pacing-test";}
        public boolean render(long time,int[] output,boolean force){starts.add(SystemClock.uptimeMillis());SystemClock.sleep(20);output[0]=Color.RED;return true;}
        void verify(String name){
            List<Long> times; synchronized(starts){times=new ArrayList<>(starts);}
            check(times.size()>=5,"Pacing probe received frames: "+name);
            long[] gaps=new long[times.size()-1];for(int i=1;i<times.size();i++)gaps[i-1]=times.get(i)-times.get(i-1);
            Arrays.sort(gaps);System.out.println("PACING "+name+" median_interval_ms="+gaps[gaps.length/2]);
            check(gaps[gaps.length/2]<48,"Renderer time must count toward the frame deadline: "+name);pass(name);
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
    static byte[] project(String mime, byte[] preview) throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();
        try(java.util.zip.ZipOutputStream zip=new java.util.zip.ZipOutputStream(bytes)) {
            zip.putNextEntry(new java.util.zip.ZipEntry("mimetype"));zip.write(mime.getBytes(java.nio.charset.StandardCharsets.US_ASCII));zip.closeEntry();
            zip.putNextEntry(new java.util.zip.ZipEntry("preview.png"));zip.write(preview);zip.closeEntry();
            // Trailing project data is intentionally not needed to extract the preview.
            zip.putNextEntry(new java.util.zip.ZipEntry("layers/data"));zip.write(new byte[1024]);zip.closeEntry();
        }
        return bytes.toByteArray();
    }
    static void thumbnailChecks() throws Exception {
        Bitmap input=Bitmap.createBitmap(12,6,Bitmap.Config.ARGB_8888);input.eraseColor(Color.CYAN);
        ByteArrayOutputStream png=new ByteArrayOutputStream();input.compress(Bitmap.CompressFormat.PNG,100,png);
        for(String ext:new String[]{"ora","kra"}) {
            byte[] bytes=project(ext.equals("ora")?"image/openraster":"application/x-krita",png.toByteArray());
            String mime=ext.equals("ora")?"image/openraster":"image/x-krita";
            check(com.infinifolder.storage.FileMagicDetector.INSTANCE.detectFromStream("project."+ext,mime,(long)bytes.length,()->new ByteArrayInputStream(bytes)).getMimeType().equals(mime),"Project identity survives ZIP wrapper "+ext);
            ThumbnailDecoder decoder=new ThumbnailDecoder(null,uri->new ByteArrayInputStream(bytes));
            Bitmap bitmap=decoder.decode(android.net.Uri.parse("content://provider/opaque-id"),8,ext,mime);
            check(bitmap!=null && bitmap.getWidth()==8 && bitmap.getHeight()==4 && bitmap.getPixel(2,2)==Color.CYAN,"Project thumbnail through opaque content URI "+ext);
        }
        pass("ora-kra-identity-and-streamed-content-thumbnails");
        String svg="<svg xmlns='http://www.w3.org/2000/svg' width='12' height='6'><rect width='12' height='6' fill='#0ff'/>"+"<!-- filler -->".repeat(12000)+"</svg>";
        ByteArrayOutputStream encoded=new ByteArrayOutputStream();try(java.util.zip.GZIPOutputStream zip=new java.util.zip.GZIPOutputStream(encoded)){zip.write(svg.getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        byte[] bytes=encoded.toByteArray();
        check(com.infinifolder.storage.FileMagicDetector.INSTANCE.detectFromStream("large.svgz","image/svg+xml",(long)bytes.length,()->new ByteArrayInputStream(bytes)).getMimeType().equals("image/svg+xml"),"Large SVGZ identity survives gzip wrapper");
        Bitmap svgz=new ThumbnailDecoder(null,uri->new ByteArrayInputStream(bytes)).decode(android.net.Uri.parse("content://provider/opaque"),8,"svgz","image/svg+xml");
        check(svgz!=null && svgz.getWidth()==8 && svgz.getHeight()==4 && svgz.getPixel(2,2)==Color.CYAN,"Large SVGZ thumbnails without cycling");
        pass("large-svgz-identity-and-static-thumbnail");
        String fixtures=System.getenv("INFINI_IMAGE_FIXTURES");
        if(fixtures!=null) {
            byte[] original=Files.readAllBytes(Paths.get(fixtures).resolve("icon.svgz"));
            check(com.infinifolder.storage.FileMagicDetector.INSTANCE.detectFromStream("icon.svgz","image/svg+xml",(long)original.length,()->new ByteArrayInputStream(original)).getMimeType().equals("image/svg+xml"),"Original test SVGZ retains image identity");
            Bitmap actual=new ThumbnailDecoder(null,uri->new ByteArrayInputStream(original)).decode(android.net.Uri.parse("content://provider/test-svgz"),96,"svgz","image/svg+xml");
            check(actual!=null && actual.getWidth()==96 && actual.getHeight()==96,"Original test SVGZ produces a thumbnail");
            png(actual,"test-icon-svgz-thumbnail.png");pass("original-test-directory-svgz-thumbnail");
        }

        byte[] eps=("%!PS-Adobe-3.0 EPSF-3.0\n%%BoundingBox: 0 0 8 2\n%%BeginPreview: 8 2 1 2\n% ff\n% 00\n%%EndPreview\n%%EOF\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        com.infinifolder.preview.IconRegistry registry=com.infinifolder.preview.IconRegistry.Companion.at(out.resolve("eps-registry").toFile());
        check(registry.mime("application/postscript").isImage(),"Application PostScript MIME requests a thumbnail");
        String extension=registry.rendererExtension("application/postscript","image.eps");check("eps".equals(extension),"EPS renderer hint retained");
        Bitmap bitmap=new ThumbnailDecoder(null,uri->new ByteArrayInputStream(eps)).decode(android.net.Uri.parse("content://provider/opaque"),8,extension,"application/postscript");
        check(bitmap!=null && bitmap.getWidth()==8 && bitmap.getHeight()==2,"EPSI preview rendered");
        byte[] real=bytes("libraries/libinfinicanvas/canvas-android/src/test/resources/images/icon.eps");
        Bitmap fixture=new ThumbnailDecoder(null,uri->new ByteArrayInputStream(real)).decode(android.net.Uri.parse("content://provider/test-icon"),96,"eps","application/postscript");
        check(fixture!=null && fixture.getWidth()==96 && fixture.getHeight()==96,"Actual test-directory EPS renders through PostScript MIME");
        png(fixture,"test-icon-eps-thumbnail.png");
        for(String ext:new String[]{"ora","kra"}) {
            byte[] realProject=bytes("libraries/libinfinicanvas/canvas-android/src/test/resources/images/icon."+ext);
            String mime=ext.equals("ora")?"image/openraster":"image/x-krita";
            Bitmap actual=new ThumbnailDecoder(null,uri->new ByteArrayInputStream(realProject)).decode(android.net.Uri.parse("content://provider/test-icon"),96,ext,mime);
            check(actual!=null && actual.getWidth()==96 && actual.getHeight()==96,"Actual project thumbnail "+ext);
            check(com.infinifolder.storage.FileMagicDetector.INSTANCE.detectFromStream("icon."+ext,mime,(long)realProject.length,()->new ByteArrayInputStream(realProject)).getMimeType().equals(mime),"Actual project image identity "+ext);
            png(actual,"test-icon-"+ext+"-thumbnail.png");
        }
        pass("postscript-mime-routes-to-eps-preview");
    }
    static void audioIntentChecks() throws Exception {
        android.content.res.AssetManager assets=android.content.res.AssetManager.class.getDeclaredConstructor().newInstance();
        int cookie=(Integer)assets.getClass().getDeclaredMethod("addAssetPath",String.class).invoke(assets,System.getenv("INFINI_TEST_APK"));
        check(cookie!=0,"APK assets loaded");
        List<android.content.IntentFilter> filters=new ArrayList<>();
        try(android.content.res.XmlResourceParser parser=assets.openXmlResourceParser(cookie,"AndroidManifest.xml")) {
            boolean mediaActivity=false;
            android.content.IntentFilter current=null;
            String ns="http://schemas.android.com/apk/res/android";
            for(int event=parser.getEventType();event!=org.xmlpull.v1.XmlPullParser.END_DOCUMENT;event=parser.next()) {
                String tag=parser.getName();
                if(event==org.xmlpull.v1.XmlPullParser.START_TAG && "activity".equals(tag))
                    mediaActivity="com.infinimedia.InfiniMediaActivity".equals(parser.getAttributeValue(ns,"name"));
                else if(event==org.xmlpull.v1.XmlPullParser.END_TAG && "activity".equals(tag)) mediaActivity=false;
                else if(mediaActivity && event==org.xmlpull.v1.XmlPullParser.START_TAG && "intent-filter".equals(tag)) current=new android.content.IntentFilter();
                else if(current!=null && event==org.xmlpull.v1.XmlPullParser.START_TAG) {
                    String name=parser.getAttributeValue(ns,"name");
                    if("action".equals(tag)) current.addAction(name);
                    else if("category".equals(tag)) current.addCategory(name);
                    else if("data".equals(tag)) {
                        String type=parser.getAttributeValue(ns,"mimeType"),scheme=parser.getAttributeValue(ns,"scheme"),host=parser.getAttributeValue(ns,"host");
                        if(type!=null)current.addDataType(type);
                        if(scheme!=null)current.addDataScheme(scheme);
                        if(host!=null)current.addDataAuthority(host,parser.getAttributeValue(ns,"port"));
                        for(String attr:new String[]{"path","pathPrefix","pathPattern","pathSuffix"}) {
                            String path=parser.getAttributeValue(ns,attr);
                            if(path!=null)current.addDataPath(path,attr.equals("path")?android.os.PatternMatcher.PATTERN_LITERAL:attr.equals("pathPrefix")?android.os.PatternMatcher.PATTERN_PREFIX:attr.equals("pathSuffix")?android.os.PatternMatcher.PATTERN_SUFFIX:android.os.PatternMatcher.PATTERN_SIMPLE_GLOB);
                        }
                    }
                } else if(current!=null && event==org.xmlpull.v1.XmlPullParser.END_TAG && "intent-filter".equals(tag)) {filters.add(current);current=null;}
            }
        }
        for(String scheme:new String[]{"content","file"})for(String type:new String[]{"audio/mpeg","audio/flac","audio/ogg","audio/x-wav","audio/vendor-unknown","video/mp4","application/jspf+json"}) {
            android.net.Uri uri=android.net.Uri.parse(scheme+"://provider/document/opaque-id");
            boolean matched=false;
            for(android.content.IntentFilter filter:filters) {
                if(filter.match("android.intent.action.VIEW",type,scheme,uri,Collections.singleton("android.intent.category.DEFAULT"),"InfiniTest")>=0) matched=true;
            }
            check(matched,"Default-capable playback intent matches "+scheme+" "+type);
        }
        pass("packaged-audio-video-playlist-default-intent-matching");
        check(!filters.isEmpty(),"Playback filters are present");assets.close();
    }
    /** Exercise real SharedPreferences JSON and ComponentName logic without altering app data. */
    static void defaultPreferenceChecks() {
        Map<String,String> stored=new HashMap<>();
        final android.content.SharedPreferences.Editor[] editor={null};
        editor[0]=(android.content.SharedPreferences.Editor)java.lang.reflect.Proxy.newProxyInstance(
            ThemeRuntimeCheck.class.getClassLoader(),new Class<?>[]{android.content.SharedPreferences.Editor.class},(proxy,method,args)->{
                switch(method.getName()) {
                    case "putString": stored.put((String)args[0],(String)args[1]);return editor[0];
                    case "remove": stored.remove((String)args[0]);return editor[0];
                    case "clear": stored.clear();return editor[0];
                    case "commit": return true;
                    case "apply": return null;
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        android.content.SharedPreferences prefs=(android.content.SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(
            ThemeRuntimeCheck.class.getClassLoader(),new Class<?>[]{android.content.SharedPreferences.class},(proxy,method,args)->{
                switch(method.getName()) {
                    case "getString": return stored.getOrDefault((String)args[0],(String)args[1]);
                    case "edit": return editor[0];
                    default: throw new UnsupportedOperationException(method.getName());
                }
            });
        android.content.Context context=new android.content.ContextWrapper(null) {
            public android.content.Context getApplicationContext(){return this;}
            public String getPackageName(){return "com.infinifolder";}
            public android.content.pm.PackageManager getPackageManager(){return null;}
            public android.content.SharedPreferences getSharedPreferences(String name,int mode){return prefs;}
        };
        com.infinifolder.viewer.ExternalAppDefaults defaults=new com.infinifolder.viewer.ExternalAppDefaults(context);
        android.content.ComponentName old=new android.content.ComponentName("com.infinifolder","com.infinifolder.viewer.AudioPlayerActivity");
        android.content.ComponentName media=new android.content.ComponentName("com.infinimedia","com.infinimedia.InfiniMediaActivity");
        String view="android.intent.action.VIEW";
        defaults.remember(view,"extension",null,"audio/mpeg","song.mp3",old);
        defaults.remember(view,"extension",null,"audio/mpeg","song.mpga",old);
        defaults.remember(view,"extension",null,"audio/flac","song.flac",old);
        defaults.remember(view,"file","another-file","audio/mpeg","song.mp3",old);
        defaults.remember(view,"type",null,"audio/mpeg","song.mp3",media);
        for(String name:new String[]{"song.mp3","song.mpga"})
            check(defaults.candidates(view,null,"audio/mpeg",name).get(0).equals(media),"New type-wide audio default wins over seeded extensions");
        check(defaults.candidates(view,null,"audio/flac","song.flac").get(0).equals(old),"Other MIME defaults preserved");
        check(defaults.candidates(view,"another-file","audio/mpeg","song.mp3").get(0).equals(old),"Other file-specific defaults preserved");
        defaults.remember(view,"type","another-file","audio/mpeg","song.mp3",media);
        check(defaults.candidates(view,"another-file","audio/mpeg","song.mp3").get(0).equals(media),"Broad choice replaces current file override");
        pass("saved-audio-default-roundtrip-and-type-choice-precedence");
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
            rasterChecks();svgStillChecks();thumbnailChecks();audioIntentChecks();defaultPreferenceChecks();
            if (args.length>2 && args[2].equals("decoder")){System.out.println("All decoder checks passed");System.exit(0);}
            pacingChecks(()->{try{lifecycleChecks();}catch(Exception error){throw new RuntimeException(error);}});Looper.loop();
        } catch(Throwable error) { error.printStackTrace();System.exit(1); }
    }
}
