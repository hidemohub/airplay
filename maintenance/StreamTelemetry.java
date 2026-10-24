package wtf.nanoka.airplay.player.gstreamer.ui;

import java.awt.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;
import org.freedesktop.gstreamer.*;
import wtf.nanoka.airplay.lib.VideoStreamInfo;
import wtf.nanoka.airplay.lib.VideoStreamInfo.Codec;

/** Reports stream parameters and independently checks negotiated decoder output. */
public final class StreamTelemetry {
    private static final String SAMPLE = "airplay.streamSample";
    private static final Map<Object, Monitor> MONITORS = new ConcurrentHashMap<>();
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(StreamTelemetry.class);
    private StreamTelemetry() {}
    static Object get(Object object, String name) {
        try {
            for (Class<?> type = object.getClass(); type != null; type = type.getSuperclass()) {
                try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(object); }
                catch (NoSuchFieldException ignored) {}
            }
            throw new NoSuchFieldException(name);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(name, error); }
    }
    private static void set(Object object, String name, Object value) {
        try { Field field = object.getClass().getDeclaredField(name); field.setAccessible(true); field.set(object, value); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(name, error); }
    }
    private static void call(Object object, String method, Class<?> type, Object value) {
        try { Method m = object.getClass().getDeclaredMethod(method, type); m.setAccessible(true); m.invoke(object, value); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(method, error); }
    }
    private static JFrame frame(Object window) { return (JFrame) get(window, "frame"); }
    private static void edt(Runnable task) { if (SwingUtilities.isEventDispatchThread()) task.run(); else SwingUtilities.invokeLater(task); }
    private static boolean sameStream(VideoStreamInfo left, VideoStreamInfo right) {
        return left != null && right != null && Objects.equals(left.getStreamConnectionId(), right.getStreamConnectionId())
            && (left.getCodec() == Codec.UNKNOWN || right.getCodec() == Codec.UNKNOWN || left.getCodec() == right.getCodec());
    }
    public static void install(Object player) { MONITORS.computeIfAbsent(player, Monitor::new); }
    public static void stop(Object player) {
        Monitor monitor = MONITORS.remove(player);
        if (monitor != null) {
            monitor.closed = true; monitor.worker.shutdownNow();
            synchronized (monitor.nativeLock) { monitor.reported = null; }
        }
    }
    public static void report(Object player, VideoStreamInfo info) {
        Monitor monitor = MONITORS.get(player);
        if (monitor != null) {
            if (!sameStream(monitor.reported, info)) monitor.decoded = null;
            monitor.reported = info;
        }
    }
    public static void disconnected(Object player) {
        Monitor monitor = MONITORS.get(player);
        if (monitor != null) synchronized (monitor.nativeLock) { monitor.reported = null; monitor.decoded = null; }
    }

    public record Sample(VideoStreamInfo info, boolean decoded, String decoder, int aspectWidth, int aspectHeight) {}

    /** Read a decoder's raw output pad, never the advertised receiver capability. */
    public static Sample inspect(Pipeline pipeline, VideoStreamInfo reported) {
        if (pipeline == null || reported == null) return null;
        for (Element element : pipeline.getElementsRecursive()) {
            ElementFactory factory = element.getFactory();
            if (factory == null || !factory.getKlass().contains("Decoder/Video")) continue;
            Pad pad = element.getStaticPad("src");
            if (pad == null) continue;
            Caps caps = pad.getCurrentCaps();
            if (caps == null) continue;
            try {
                if (caps.isEmpty() || caps.isAny()) continue;
                Structure structure = caps.getStructure(0);
                if (!structure.getName().equals("video/x-raw") || !structure.hasField("width") || !structure.hasField("height")) continue;
                int width = structure.getInteger("width"), height = structure.getInteger("height");
                if (width <= 0 || height <= 0 || width > 32768 || height > 32768) continue;
                double fps = 0;
                if (structure.hasField("framerate")) {
                    Fraction rate = structure.getFraction("framerate");
                    if (rate.getDenominator() > 0) fps = (double) rate.getNumerator() / rate.getDenominator();
                }
                int aspectWidth = width, aspectHeight = height;
                if (structure.hasField("pixel-aspect-ratio")) {
                    Fraction ratio = structure.getFraction("pixel-aspect-ratio");
                    if (ratio.getNumerator() > 0 && ratio.getDenominator() > 0) {
                        long scaledWidth = (long) width * ratio.getNumerator(), scaledHeight = (long) height * ratio.getDenominator();
                        if (scaledWidth <= Integer.MAX_VALUE && scaledHeight <= Integer.MAX_VALUE) {
                            aspectWidth = (int) scaledWidth; aspectHeight = (int) scaledHeight;
                        }
                    }
                }
                Codec codec = reported.getCodec();
                Pad input = element.getStaticPad("sink");
                Caps encoded = input == null ? null : input.getCurrentCaps();
                if (encoded != null) try {
                    if (!encoded.isEmpty() && !encoded.isAny()) {
                        String type = encoded.getStructure(0).getName();
                        if (type.equals("video/x-h265")) codec = Codec.HEVC;
                        else if (type.equals("video/x-h264")) codec = Codec.H264;
                    }
                } finally { encoded.dispose(); }
                if (codec == Codec.UNKNOWN) continue;
                return new Sample(new VideoStreamInfo(reported.getStreamConnectionId(), width, height, fps, codec),
                    true, factory.getName(), aspectWidth, aspectHeight);
            } finally { caps.dispose(); }
        }
        return null;
    }
    private static Sample merge(Object window, VideoStreamInfo info) {
        Sample sample = (Sample) frame(window).getRootPane().getClientProperty(SAMPLE);
        if (sample == null || !sameStream(sample.info(), info)) return new Sample(info, false, "", info.getWidth(), info.getHeight());
        VideoStreamInfo measured = sample.info();
        return new Sample(new VideoStreamInfo(info.getStreamConnectionId(), measured.getWidth(), measured.getHeight(),
            info.getFps() > 0 ? info.getFps() : measured.getFps(), measured.getCodec()), sample.decoded(), sample.decoder(), sample.aspectWidth(), sample.aspectHeight());
    }
    public static String details(Object window, VideoStreamInfo info) {
        Sample sample = merge(window, info);
        VideoStreamInfo actual = sample.info();
        I18n i18n = (I18n) get(window, "i18n");
        String size = actual.getWidth() > 0 && actual.getHeight() > 0
            ? actual.getWidth() + " × " + actual.getHeight() : i18n.tr("stream.resolution.pending");
        String codec = actual.getCodec() == Codec.HEVC ? "H.265 (HEVC)" : actual.getCodec() == Codec.H264 ? "H.264" : i18n.tr("stream.codec.pending");
        String fps = actual.getFps() > 0 ? String.format(Locale.ROOT, "%.1f FPS", actual.getFps()) : i18n.tr("stream.fps.pending");
        String source = sample.decoded() ? i18n.tr("stream.decoded") : i18n.tr("stream.parameters");
        String message = size + "  |  " + fps + "  |  " + codec + "  |  " + source;
        ReceiverSettings settings = (ReceiverSettings) get(window, "savedSettings");
        if (actual.getCodec() == Codec.H264 && settings != null && settings.hevcEnabled()) message += "  |  " + i18n.tr("stream.hevc.notSelected");
        return message;
    }
    public static void afterUi(Object window, VideoStreamInfo info) {
        Sample sample = merge(window, info);
        VideoStreamInfo actual = sample.info();
        set(window, "currentVideoStream", actual);
        call(window, "updateVideoDetails", VideoStreamInfo.class, actual);
        if (sample.aspectWidth() > 0 && sample.aspectHeight() > 0) {
            Object surface = get(window, "videoSurface");
            try {
                Method aspect = surface.getClass().getDeclaredMethod("setAspectRatio", int.class, int.class);
                aspect.setAccessible(true); aspect.invoke(surface, sample.aspectWidth(), sample.aspectHeight());
            } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        }
        Object detached = get(window, "detachedVideoWindow");
        String text = details(window, actual);
        boolean connected = (boolean) get(window, "connected");
        if (connected) call(detached, "setStatusConnected", String.class, text);
        Object label = get(detached, "detailsLabel");
        if (label != null) { ((JLabel) label).setText(text); ((JLabel) label).setToolTipText(text); }
        ((JLabel) get(window, "videoDetails")).setToolTipText(text);
        I18n i18n = (I18n) get(window, "i18n");
        String title = i18n.tr("frame.videoWindow");
        if (actual.getWidth() > 0 && actual.getHeight() > 0) title += " | " + actual.getWidth() + " × " + actual.getHeight();
        title += " | " + (actual.getFps() > 0 ? String.format(Locale.ROOT, "%.1f FPS", actual.getFps()) : i18n.tr("stream.fps.pending"));
        if (actual.getCodec() != Codec.UNKNOWN) title += " | " + (actual.getCodec() == Codec.HEVC ? "H.265 (HEVC)" : "H.264");
        frame(detached).setTitle(title);
        // Format/FPS callbacks must not reopen the main window while using detached playback.
        if (frame(detached).isVisible() && get(detached, "canvas") != null) frame(window).setVisible(false);
    }
    public static void syncDetached(Object window) {
        VideoStreamInfo info = (VideoStreamInfo) get(window, "currentVideoStream");
        if (info != null) afterUi(window, info);
    }
    public static void showUnlessDetached(Object window) {
        Object detached = get(window, "detachedVideoWindow");
        if (frame(detached).isVisible() && get(detached, "canvas") != null) return;
        try { Method show = window.getClass().getDeclaredMethod("showFrame"); show.setAccessible(true); show.invoke(window); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    public static void idle(Object window) {
        frame(window).getRootPane().putClientProperty(SAMPLE, null);
        Object detached = get(window, "detachedVideoWindow");
        frame(detached).setTitle(((I18n) get(window, "i18n")).tr("frame.videoWindow"));
    }
    private static final class Monitor {
        final Object player, window;
        final ScheduledExecutorService worker;
        volatile VideoStreamInfo reported;
        volatile Sample decoded;
        volatile boolean closed;
        final Object nativeLock = new Object();
        Monitor(Object player) {
            this.player = player; window = get(player, "window");
            worker = Executors.newSingleThreadScheduledExecutor(task -> Thread.ofPlatform().daemon(true).name("airplay-stream-telemetry").unstarted(task));
            worker.scheduleWithFixedDelay(this::poll, 250, 500, TimeUnit.MILLISECONDS);
        }
        void poll() {
            if (closed || reported == null) return;
            try {
                VideoStreamInfo request = reported;
                Pipeline pipeline;
                Sample sample;
                synchronized (nativeLock) {
                    if (closed || reported == null) return;
                    pipeline = (Pipeline) get(player, "activeVideoPipeline");
                    sample = inspect(pipeline, request);
                }
                if (sample == null || closed || !sameStream(request, reported) || get(player, "activeVideoPipeline") != pipeline) return;
                Sample previous = decoded;
                boolean changed = previous == null || sample.info().getWidth() != previous.info().getWidth()
                    || sample.info().getHeight() != previous.info().getHeight() || sample.info().getCodec() != previous.info().getCodec();
                decoded = sample;
                if (changed) LOG.info("Confirmed decoded video: {} {}x{}; decoder={}", sample.info().getCodec(), sample.info().getWidth(), sample.info().getHeight(), sample.decoder());
                if (changed) edt(() -> {
                    if (!closed && sameStream(request, reported)) {
                        frame(window).getRootPane().putClientProperty(SAMPLE, sample);
                        VideoStreamInfo current = (VideoStreamInfo) get(window, "currentVideoStream");
                        if (sameStream(request, current)) afterUi(window, current);
                    }
                });
            } catch (RuntimeException error) {
                // A pipeline may be disposed or renegotiated during disconnect/rotation.
                if (!closed) LOG.debug("Decoder caps not ready: {}", error.toString());
            }
        }
    }
}
