import java.nio.file.*;
import org.springframework.asm.*;

/** Migrate the former default device name when loading saved settings. */
public final class PatchBranding {
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        ClassReader reader = new ClassReader(Files.readAllBytes(path));
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, signature, exceptions)) {
                    @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean itf) {
                        if (owner.equals("java/util/Properties") && method.equals("load")) {
                            // Preserve the Properties receiver while its Reader is consumed.
                            super.visitInsn(Opcodes.DUP2);
                            super.visitMethodInsn(opcode, owner, method, descriptor, itf);
                            super.visitInsn(Opcodes.POP);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, "wtf/nanoka/airplay/app/DesktopLifecycle", "normalizeSettings", "(Ljava/util/Properties;)V", false);
                        } else super.visitMethodInsn(opcode, owner, method, descriptor, itf);
                    }
                };
            }
        }, 0);
        Files.write(path, writer.toByteArray());
    }
}
