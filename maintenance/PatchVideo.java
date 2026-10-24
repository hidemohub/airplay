import java.nio.file.*;
import org.springframework.asm.*;

public final class PatchVideo {
    static final String HELPER = "wtf/nanoka/airplay/player/gstreamer/ui/StreamTelemetry";
    public static void main(String[] args) throws Exception {
        patchPlayer(Path.of(args[0])); patchConfiguration(Path.of(args[1]));
    }
    static void patchPlayer(Path path) throws Exception {
        ClassReader reader = new ClassReader(Files.readAllBytes(path));
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] count = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, signature, exceptions)) {
                    @Override public void visitCode() {
                        super.visitCode();
                        if (name.equals("close") || name.equals("onVideoSrcDisconnect")) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, name.equals("close") ? "stop" : "disconnected", "(Ljava/lang/Object;)V", false); count[0]++;
                        }
                    }
                    @Override public void visitInsn(int opcode) {
                        if (opcode == Opcodes.RETURN) {
                            String method = name.equals("<init>") ? "install"
                                : name.equals("onVideoFormatDetected") || name.equals("onVideoFormat") ? "report" : null;
                            if (method != null) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                if (method.equals("report")) super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, method, method.equals("report")
                                    ? "(Ljava/lang/Object;Lwtf/nanoka/airplay/lib/VideoStreamInfo;)V" : "(Ljava/lang/Object;)V", false);
                                count[0]++;
                            }
                        }
                        super.visitInsn(opcode);
                    }
                };
            }
        }, 0);
        if (count[0] != 5) throw new IllegalStateException("Unexpected GstPlayerSwing version: " + count[0]);
        Files.write(path, writer.toByteArray());
    }
    static void patchConfiguration(Path path) throws Exception {
        ClassReader reader = new ClassReader(Files.readAllBytes(path));
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] count = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, desc, signature, exceptions);
                if (name.equals("parse") && desc.startsWith("([B)")) {
                    method.visitCode(); method.visitVarInsn(Opcodes.ALOAD, 0);
                    method.visitMethodInsn(Opcodes.INVOKESTATIC, "wtf/nanoka/airplay/server/internal/handler/video/CodecConfigurationSupport", "parse", desc, false);
                    method.visitInsn(Opcodes.ARETURN); method.visitMaxs(0, 0); method.visitEnd(); count[0]++; return null;
                }
                return method;
            }
        }, 0);
        if (count[0] != 1) throw new IllegalStateException("Unexpected codec configuration version");
        Files.write(path, writer.toByteArray());
    }
}
