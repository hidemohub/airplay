import java.nio.file.*;
import org.springframework.asm.*;

/** Route the release's tray Exit action through the same asynchronous cleanup. */
public class PatchTray {
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        ClassReader reader = new ClassReader(Files.readAllBytes(path));
        ClassWriter writer = new ClassWriter(reader, 0);
        int[] replaced = {0};
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                MethodVisitor method = super.visitMethod(access, name, desc, signature, exceptions);
                if (name.equals("lambda$initializePopupMenu$4") && desc.equals("(Lorg/springframework/context/ApplicationContext;Ljava/awt/event/ActionEvent;)V")) {
                    method.visitCode();
                    method.visitVarInsn(Opcodes.ALOAD, 1);
                    method.visitMethodInsn(Opcodes.INVOKESTATIC, "wtf/nanoka/airplay/app/DesktopLifecycle", "requestExit", "(Lorg/springframework/context/ApplicationContext;)V", false);
                    method.visitInsn(Opcodes.RETURN);
                    method.visitMaxs(1, 3);
                    method.visitEnd();
                    replaced[0]++;
                    return null;
                }
                return method;
            }
        }, 0);
        if (replaced[0] != 1) throw new IllegalStateException("Unexpected tray class version; refusing to patch");
        Files.write(path, writer.toByteArray());
    }
}
