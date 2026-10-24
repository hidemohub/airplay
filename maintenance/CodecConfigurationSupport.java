package wtf.nanoka.airplay.server.internal.handler.video;

import java.io.ByteArrayOutputStream;
import java.lang.reflect.*;
import java.nio.ByteBuffer;
import java.util.*;
import wtf.nanoka.airplay.lib.VideoStreamInfo.Codec;

/** Accept standard hvcC records/boxes and Annex-B parameter sets in addition to sample entries. */
public final class CodecConfigurationSupport {
    private CodecConfigurationSupport() {}
    private static Object original(String method, Class<?>[] types, Object... arguments) {
        try {
            Method m = VideoCodecConfiguration.class.getDeclaredMethod(method, types); m.setAccessible(true); return m.invoke(null, arguments);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException error) throw error;
            throw new IllegalArgumentException("Invalid video configuration", e.getCause());
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    public static VideoCodecConfiguration parse(byte[] data) {
        if (data == null || data.length < 4 || data.length > 1024 * 1024) throw new IllegalArgumentException("Invalid video configuration length");
        if (startCode(data, 0) > 0) return annexB(data);
        if (data.length >= 8 && data[4] == 'h' && data[5] == 'v' && data[6] == 'c' && data[7] == 'C') {
            long size = Integer.toUnsignedLong(ByteBuffer.wrap(data).getInt());
            if (size == 0) size = data.length;
            if (size < 31 || size > data.length) throw new IllegalArgumentException("Truncated hvcC box");
            return hvcc(data, 8, (int) size);
        }
        if (rawHvcc(data)) return hvcc(data, 0, data.length);
        boolean sample = (boolean) original("isHevcSampleEntry", new Class<?>[]{byte[].class}, data);
        return (VideoCodecConfiguration) original(sample ? "parseHevc" : "parseAvc", new Class<?>[]{byte[].class}, data);
    }
    private static boolean rawHvcc(byte[] data) {
        return data.length >= 23 && data[0] == 1 && (data[13] & 0xf0) == 0xf0
            && (data[15] & 0xfc) == 0xfc && (data[16] & 0xfc) == 0xfc
            && (data[17] & 0xf8) == 0xf8 && (data[18] & 0xf8) == 0xf8;
    }
    private static VideoCodecConfiguration hvcc(byte[] data, int offset, int end) {
        return (VideoCodecConfiguration) original("parseHevcDecoderConfiguration", new Class<?>[]{byte[].class, int.class, int.class}, data, offset, end);
    }
    private static int startCode(byte[] bytes, int index) {
        if (index + 3 <= bytes.length && bytes[index] == 0 && bytes[index+1] == 0) {
            if (bytes[index+2] == 1) return 3;
            if (index + 4 <= bytes.length && bytes[index+2] == 0 && bytes[index+3] == 1) return 4;
        }
        return 0;
    }
    private static VideoCodecConfiguration annexB(byte[] data) {
        List<byte[]> nals = new ArrayList<>();
        for (int at = 0; at < data.length;) {
            int prefix = startCode(data, at);
            if (prefix == 0) throw new IllegalArgumentException("Malformed Annex-B configuration");
            int begin = at + prefix, end = begin;
            while (end < data.length && startCode(data, end) == 0) end++;
            if (end > begin) nals.add(Arrays.copyOfRange(data, begin, end));
            if (nals.size() > 256) throw new IllegalArgumentException("Too many parameter sets");
            at = end;
        }
        boolean hevc = nals.stream().anyMatch(nal -> nal.length >= 2 && ((nal[0] & 0x7e) >> 1) == 32 && (nal[1] & 7) != 0);
        byte[] sps = null; boolean vps = false, pps = false;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        for (byte[] nal : nals) {
            int type = hevc ? (nal[0] & 0x7e) >> 1 : nal[0] & 31;
            if (hevc && nal.length < 2) throw new IllegalArgumentException("Truncated HEVC NAL");
            boolean parameter = hevc ? type >= 32 && type <= 34 : type == 7 || type == 8;
            if (!parameter) continue;
            if (type == (hevc ? 33 : 7)) sps = nal;
            if (type == (hevc ? 34 : 8)) pps = true;
            if (hevc && type == 32) vps = true;
            output.writeBytes(new byte[]{0,0,0,1}); output.writeBytes(nal);
        }
        if (sps == null || !pps || (hevc && !vps)) throw new IllegalArgumentException("Video configuration is missing required parameter sets");
        return new VideoCodecConfiguration(hevc ? Codec.HEVC : Codec.H264, output.toByteArray(), sps);
    }
}
