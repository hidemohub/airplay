import java.awt.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;
import org.freedesktop.gstreamer.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import wtf.nanoka.airplay.app.PlayerApp;
import wtf.nanoka.airplay.app.DesktopLifecycle;
import wtf.nanoka.airplay.app.config.UserSettingsPropertySource;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo.Codec;
import wtf.nanoka.airplay.player.gstreamer.GstPlayerSwing;
import wtf.nanoka.airplay.player.gstreamer.ui.StreamTelemetry;

/** Encodes real H.264/HEVC fixtures and decodes them through the actual receiver. */
public final class VerifyVideo {
    static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    static Object get(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    static Object method(Object object, String name, Class<?>[] types, Object... args) throws Exception {
        Method m = object.getClass().getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(object, args);
    }
    static void flush() throws Exception { SwingUtilities.invokeAndWait(() -> {}); }
    static List<byte[]> nals(byte[] bytes) {
        List<Integer> positions = new ArrayList<>(), sizes = new ArrayList<>();
        for (int i = 0; i + 3 < bytes.length; i++) {
            if (bytes[i] == 0 && bytes[i+1] == 0 && (bytes[i+2] == 1 || (bytes[i+2] == 0 && bytes[i+3] == 1))) {
                int size = bytes[i+2] == 1 ? 3 : 4; positions.add(i); sizes.add(size); i += size - 1;
            }
        }
        List<byte[]> out = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) out.add(Arrays.copyOfRange(bytes, positions.get(i)+sizes.get(i), i+1 < positions.size() ? positions.get(i+1) : bytes.length));
        return out;
    }
    static byte[] generate(Codec codec, int width, int height) throws Exception {
        Path path = Path.of("maintenance", "fixture-" + codec + "-" + width + "x" + height + ".bin").toAbsolutePath();
        String encoder = codec == Codec.HEVC ? "x265enc speed-preset=ultrafast tune=zerolatency key-int-max=1 option-string=aud=1" : "x264enc speed-preset=ultrafast tune=zerolatency key-int-max=1 aud=true";
        String type = codec == Codec.HEVC ? "h265" : "h264";
        Pipeline pipeline = (Pipeline) Gst.parseLaunch("videotestsrc num-buffers=6 ! video/x-raw,format=I420,width="+width+",height="+height+",framerate=30/1 ! " + encoder + " ! " + type + "parse ! video/x-" + type + ",stream-format=byte-stream,alignment=au ! filesink location=\"" + path.toString().replace('\\','/') + "\"");
        CountDownLatch done = new CountDownLatch(1); String[] error = {null};
        pipeline.getBus().connect((Bus.EOS) source -> done.countDown());
        pipeline.getBus().connect((Bus.ERROR) (source, code, message) -> { error[0] = message; done.countDown(); });
        try { pipeline.play(); check(done.await(15, TimeUnit.SECONDS), "Encoder timed out"); check(error[0] == null, "Encoder error: " + error[0]); }
        finally { pipeline.stop(); pipeline.dispose(); }
        return Files.readAllBytes(path);
    }
    static Object config(byte[] bytes) throws Exception {
        Class<?> type = Class.forName("wtf.nanoka.airplay.server.internal.handler.video.VideoCodecConfiguration");
        Method parse = type.getDeclaredMethod("parse", byte[].class); parse.setAccessible(true); return parse.invoke(null, bytes);
    }
    static void u16(ByteArrayOutputStream out, int size) { out.write(size >> 8); out.write(size); }
    static byte[] hvcc(List<byte[]> nals) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] header = new byte[23];
        header[0]=1; header[1]=1; header[12]=93; header[13]=(byte)0xf0; header[15]=(byte)0xfc;
        header[16]=(byte)0xfd; header[17]=(byte)0xf8; header[18]=(byte)0xf8; header[21]=3; header[22]=3; out.writeBytes(header);
        for (int type : new int[]{32,33,34}) {
            byte[] nal = nals.stream().filter(n -> ((n[0]&0x7e)>>1) == type).findFirst().orElseThrow();
            out.write(0x80|type); u16(out,1); u16(out,nal.length); out.writeBytes(nal);
        }
        return out.toByteArray();
    }
    static byte[] box(String type, byte[] bytes) {
        return ByteBuffer.allocate(bytes.length+8).putInt(bytes.length+8).put(type.getBytes(java.nio.charset.StandardCharsets.US_ASCII)).put(bytes).array();
    }
    static void verifyConfigurations(byte[] bytes, Codec codec, int width, int height) throws Exception {
        Object configuration = config(bytes);
        check(method(configuration,"codec",new Class<?>[0]) == codec,"Annex-B codec detection");
        byte[] sps = (byte[]) method(configuration,"sequenceParameterSet",new Class<?>[0]);
        Class<?> parser = Class.forName("wtf.nanoka.airplay.server.internal.handler.video."+(codec==Codec.HEVC?"H265":"H264")+"SpsParser");
        Method parse = parser.getDeclaredMethod("parse",byte[].class);parse.setAccessible(true);Object format=parse.invoke(null,sps);
        check(format != null,"SPS parse failed");
        check((int)method(format,"width",new Class<?>[0]) == width && (int)method(format,"height",new Class<?>[0]) == height,"SPS cropped dimensions incorrect");
        if (codec == Codec.HEVC) {
            byte[] record=hvcc(nals(bytes));byte[] hvcc=box("hvcC",record);byte[] sample=new byte[86+hvcc.length];
            ByteBuffer.wrap(sample).putInt(sample.length).put("hvc1".getBytes(java.nio.charset.StandardCharsets.US_ASCII));System.arraycopy(hvcc,0,sample,86,hvcc.length);
            for (byte[] variant : new byte[][]{record,hvcc,sample}) check(method(config(variant),"codec",new Class<?>[0])==Codec.HEVC,"HEVC configuration variant");
            try { config(Arrays.copyOf(hvcc,12));throw new AssertionError("Truncated HEVC accepted"); } catch (InvocationTargetException expected) {}
        } else {
            List<byte[]> all=nals(bytes);byte[] pps=all.stream().filter(n->(n[0]&31)==8).findFirst().orElseThrow();
            ByteArrayOutputStream out=new ByteArrayOutputStream();out.writeBytes(new byte[]{1,sps[1],sps[2],sps[3],(byte)0xff,(byte)0xe1});
            u16(out,sps.length);out.writeBytes(sps);out.write(1);u16(out,pps.length);out.writeBytes(pps);
            check(method(config(out.toByteArray()),"codec",new Class<?>[0])==Codec.H264,"Existing AVC configuration regressed");
        }
    }
    static List<byte[]> accessUnits(byte[] data, Codec codec) {
        List<byte[]> result=new ArrayList<>();ByteArrayOutputStream out=new ByteArrayOutputStream();boolean vcl=false;
        for(byte[] nal:nals(data)) {
            int type=codec==Codec.HEVC?(nal[0]&0x7e)>>1:nal[0]&31;
            if(type==(codec==Codec.HEVC?35:9) && vcl) { result.add(out.toByteArray());out.reset();vcl=false; }
            out.writeBytes(new byte[]{0,0,0,1});out.writeBytes(nal);if(codec==Codec.HEVC?type<32:type>=1&&type<=5)vcl=true;
        }
        if(vcl)result.add(out.toByteArray());return result;
    }
    static void receive(GstPlayerSwing player,Object window,byte[] bytes,Codec codec,int width,int height,String id) throws Exception {
        player.onVideoFormatDetected(new VideoStreamInfo(id));
        player.onVideoFormat(new VideoStreamInfo(id,0,0,0,codec));flush();
        JFrame main=(JFrame)get(window,"frame");Object detached=get(window,"detachedVideoWindow");
        String pending=(String)method(window,"detailsText",new Class<?>[]{VideoStreamInfo.class},new VideoStreamInfo(id));
        check(!pending.contains("1920")&&!pending.contains("60 FPS"),"Advertised capability presented as actual stream");
        player.showDetachedVideo();flush();
        for(byte[] unit:accessUnits(bytes,codec)) { player.onVideo(unit);Thread.sleep(35); }
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);boolean confirmed=false;
        while(System.nanoTime()<deadline) {
            flush();VideoStreamInfo info=(VideoStreamInfo)get(window,"currentVideoStream");
            if(info!=null&&info.getWidth()==width&&info.getHeight()==height&&main.getRootPane().getClientProperty("airplay.streamSample")!=null) {confirmed=true;break;}
            Thread.sleep(100);
        }
        check(confirmed,"Actual decoder caps were not reported: "+codec+" "+width+"x"+height);
        JLabel label=(JLabel)get(detached,"detailsLabel");
        check(label.getText().contains(width+" × "+height),"Detached resolution stale: "+label.getText());
        check(label.getText().contains(codec==Codec.HEVC?"H.265":"H.264"),"Displayed codec differs from actual stream");
        check(!main.isVisible(),"Format callback reopened main window during detached playback");
        player.onVideoFormatDetected(new VideoStreamInfo(id,width,height,29.9,codec));flush();
        check(label.getText().contains("29.9"),"Detached FPS update was lost");
        JFrame videoFrame=(JFrame)get(detached,"frame");
        check(videoFrame.getTitle().contains("29.9 FPS"), "Title FPS missing: "+videoFrame.getTitle());
        check(videoFrame.getTitle().startsWith("airplay"), "Old branding in title: "+videoFrame.getTitle());
        check(!label.isShowing(), "Detached status bar remains visible");
        if(codec==Codec.HEVC && width<height) SwingUtilities.invokeAndWait(() -> {
            try { VerifyWindows.preview((JFrame)uncheckedGet(detached,"frame"),"video-hevc-portrait"); }
            catch(Exception e) { throw new RuntimeException(e); }
        });
        System.out.println("VERIFY: decoded "+codec+" "+width+"x"+height+"; detached="+label.getText());
    }
    public static void main(String[] args) throws Exception {
        var context=new SpringApplicationBuilder(PlayerApp.class).web(WebApplicationType.NONE).headless(false).initializers(new UserSettingsPropertySource())
            .run("--java-airplay.validation=true","--airplay.hevc=true","--player.gstreamer.videoDecoder=avdec_h264");
        try {
            GstPlayerSwing player=context.getBean(GstPlayerSwing.class);Object window=get(player,"window");
            for(Object[] test:new Object[][]{{Codec.H264,1554,1080},{Codec.HEVC,320,240},{Codec.HEVC,240,320}}) {
                Codec codec=(Codec)test[0];int width=(int)test[1],height=(int)test[2];byte[] bytes=generate(codec,width,height);
                verifyConfigurations(bytes,codec,width,height);receive(player,window,bytes,codec,width,height,"fixture-"+codec);
                if(codec==Codec.H264 || height>width) { player.onVideoSrcDisconnect();flush(); }
            }
            System.out.println("VERIFY: real H.264/HEVC decode, cropped and portrait dimensions, configuration variants, unknown metadata and detached updates passed");
            DesktopLifecycle.requestExit(context);
        } catch(Throwable error) { error.printStackTrace(); context.close(); System.exit(1); }
    }
    static Object uncheckedGet(Object object,String name) { try { return get(object,name); } catch(Exception e) { throw new RuntimeException(e); } }
}
