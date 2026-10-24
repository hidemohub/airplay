import java.nio.file.*;
import org.springframework.asm.*;

public final class PatchWindow {
    private static final String HELPER = "wtf/nanoka/airplay/player/gstreamer/ui/WindowPinControls";
    private static final String OBJECT = "(Ljava/lang/Object;)V";
    public static void main(String[] args) throws Exception {
        patch(Path.of(args[0]), true); patch(Path.of(args[1]), false);
    }
    private static void patch(Path path, boolean main) throws Exception {
        ClassReader reader = new ClassReader(Files.readAllBytes(path));
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] changes = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
                if (main && name.equals("detailsText")) {
                    delegate.visitCode(); delegate.visitVarInsn(Opcodes.ALOAD, 0); delegate.visitVarInsn(Opcodes.ALOAD, 1);
                    delegate.visitMethodInsn(Opcodes.INVOKESTATIC, "wtf/nanoka/airplay/player/gstreamer/ui/StreamTelemetry", "details",
                        "(Ljava/lang/Object;Lwtf/nanoka/airplay/lib/VideoStreamInfo;)Ljava/lang/String;", false);
                    delegate.visitInsn(Opcodes.ARETURN); delegate.visitMaxs(0, 0); delegate.visitEnd(); changes[0]++; return null;
                }
                String replacement = null;
                if (main && (name.equals("toggleVideoFullscreen") || name.equals("lambda$buildVideoView$1"))) replacement = "toggleFullscreen";
                if (main && name.equals("lambda$setCloseToTray$0")) replacement = "lockCloseOperation";
                if (!main && name.equals("toggleFullscreen")) replacement = "toggleFullscreen";
                if (!main && name.equals("setFullscreen")) replacement = "setFullscreen";
                if (replacement != null) {
                    delegate.visitCode(); delegate.visitVarInsn(Opcodes.ALOAD, 0);
                    if (replacement.equals("setFullscreen")) delegate.visitVarInsn(Opcodes.ILOAD, 1);
                    delegate.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, replacement,
                        replacement.equals("setFullscreen") ? "(Ljava/lang/Object;Z)V" : OBJECT, false);
                    delegate.visitInsn(Opcodes.RETURN); delegate.visitMaxs(0, 0); delegate.visitEnd();
                    changes[0]++; return null;
                }
                return new MethodVisitor(Opcodes.ASM9, delegate) {
                    @Override public void visitInsn(int opcode) {
                        if (main && opcode == Opcodes.ARETURN && (name.equals("buildHeader") || name.equals("buildSettingsView"))) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, name.equals("buildHeader") ? "decorateHeader" : "decorateSettings",
                                "(Ljavax/swing/JComponent;Ljava/lang/Object;)Ljavax/swing/JComponent;", false); changes[0]++;
                        }
                        if (opcode == Opcodes.RETURN) {
                            if (main && (name.equals("lambda$showVideo$0") || name.equals("lambda$showVideoFormatDetected$0"))) {
                                super.visitVarInsn(Opcodes.ALOAD, 0); super.visitVarInsn(Opcodes.ALOAD, 1);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "wtf/nanoka/airplay/player/gstreamer/ui/StreamTelemetry", "afterUi",
                                    "(Ljava/lang/Object;Lwtf/nanoka/airplay/lib/VideoStreamInfo;)V", false);
                            }
                            if (main && (name.equals("detachCanvas") || name.equals("lambda$refreshLanguage$0") || name.equals("lambda$showIdle$0"))) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, "wtf/nanoka/airplay/player/gstreamer/ui/StreamTelemetry",
                                    name.equals("lambda$showIdle$0") ? "idle" : "syncDetached", "(Ljava/lang/Object;)V", false);
                            }
                            String hook = null;
                            if (main && name.equals("detachCanvas")) hook = "afterDetach";
                            if (main && name.equals("attachCanvasToMain")) hook = "afterAttach";
                            if (!main && name.equals("lambda$initialize$0")) hook = "installDetached";
                            if (!main && name.equals("showFrame")) hook = "detachedShown";
                            if (!main && name.equals("detachCanvas")) hook = "detachedHidden";
                            if (hook != null) {
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, hook, OBJECT, false); changes[0]++;
                            }
                        }
                        super.visitInsn(opcode);
                    }
                    @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) {
                        if (main && name.equals("buildSettingsView") && owner.equals("java/util/prefs/Preferences") && method.equals("getBoolean")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "initialPreference", "(Ljava/util/prefs/Preferences;Ljava/lang/String;Z)Z", false); changes[0]++;
                        } else if (main && (name.equals("lambda$showVideo$0") || name.equals("lambda$showVideoFormatDetected$0")) && method.equals("showFrame")
                                && owner.equals("wtf/nanoka/airplay/player/gstreamer/ui/VisionPlayerWindow")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "wtf/nanoka/airplay/player/gstreamer/ui/StreamTelemetry", "showUnlessDetached", "(Ljava/lang/Object;)V", false);
                        } else if (main && name.equals("initialize") && owner.equals("javax/swing/JFrame") && method.equals("setVisible")) {
                            super.visitVarInsn(Opcodes.ALOAD, 0);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "showMainFrame", "(Ljavax/swing/JFrame;ZLjava/lang/Object;)V", false); changes[0]++;
                        } else if (!main && owner.equals("javax/swing/JFrame") && method.equals("getContentPane")) {
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, HELPER, "body", "(Ljavax/swing/JFrame;)Ljava/awt/Container;", false); changes[0]++;
                        } else super.visitMethodInsn(opcode, owner, method, descriptor, isInterface);
                    }
                };
            }
        }, 0);
        if (changes[0] < (main ? 9 : 10)) throw new IllegalStateException("Unsupported window version: " + changes[0]);
        Files.write(path, writer.toByteArray());
    }
}
