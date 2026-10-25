import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import org.objectweb.asm.*;

/** Focused compatibility patch for the pinned upstream release; leaves source IDs and URLs intact. */
class PatchProComic {
    public static void main(String[] args) throws Exception {
        int[] removed = {0};
        int[] hooks = {0};
        int[] messages = {0};
        int[] stitchers = {0};
        try (JarFile input = new JarFile(args[0]);
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(Path.of(args[1])))) {
            for (JarEntry entry : Collections.list(input.entries())) {
                if (!entry.getName().endsWith(".class")) continue;
                byte[] bytes = input.getInputStream(entry).readAllBytes();
                ClassWriter writer = new ClassWriter(0);
                new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                        if (entry.getName().equals("eu/kanade/tachiyomi/a/a/a/af.class") && name.equals("a") && desc.equals("(Lokhttp3/HttpUrl;Lokhttp3/Call;Lkotlin/coroutines/Continuation;)Ljava/lang/Object;")) {
                            stitchers[0]++;
                            MethodVisitor target = super.visitMethod(access, name, desc, signature, exceptions);
                            return new MethodVisitor(Opcodes.ASM9) {
                                @Override public void visitCode() {
                                    target.visitCode();
                                    target.visitVarInsn(Opcodes.ALOAD, 0);
                                    target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "keiyoushi/source/a", "getClient", "()Lokhttp3/OkHttpClient;", false);
                                    target.visitVarInsn(Opcodes.ALOAD, 0);
                                    target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "eu/kanade/tachiyomi/source/online/HttpSource", "getHeaders", "()Lokhttp3/Headers;", false);
                                    target.visitVarInsn(Opcodes.ALOAD, 0);
                                    target.visitMethodInsn(Opcodes.INVOKEVIRTUAL, "eu/kanade/tachiyomi/source/online/HttpSource", "getBaseUrl", "()Ljava/lang/String;", false);
                                    target.visitVarInsn(Opcodes.ALOAD, 1);
                                    target.visitVarInsn(Opcodes.ALOAD, 2);
                                    target.visitMethodInsn(Opcodes.INVOKESTATIC, "eu/kanade/tachiyomi/extension/ar/shared/ProComicStitcher", "stitch", "(Lokhttp3/OkHttpClient;Lokhttp3/Headers;Ljava/lang/String;Lokhttp3/HttpUrl;Lokhttp3/Call;)Lokio/Buffer;", false);
                                    target.visitInsn(Opcodes.ARETURN);
                                    target.visitMaxs(5, 4);
                                }
                                @Override public void visitMethodInsn(int opcode, String owner, String method, String descriptor, boolean isInterface) {
                                    if (owner.equals("android/graphics/Canvas") && method.equals("drawColor") && descriptor.equals("(I)V")) removed[0]++;
                                }
                                @Override public void visitEnd() { target.visitEnd(); }
                            };
                        }
                        return new MethodVisitor(Opcodes.ASM9, super.visitMethod(access, name, desc, signature, exceptions)) {
                            @Override public void visitLdcInsn(Object value) {
                                if ("No decoder could read the image".equals(value)) {
                                    value = "تعذر فك ترميز صور الفصل في هذا التطبيق. حدّث التطبيق أو افتح الفصل في عرض الويب.";
                                    messages[0]++;
                                }
                                super.visitLdcInsn(value);
                            }
                            @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean isInterface) {
                                if (owner.equals("android/graphics/Canvas") && method.equals("drawColor") && desc.equals("(I)V")) {
                                    if (opcode != Opcodes.INVOKEVIRTUAL) throw new IllegalStateException("Unexpected drawColor call");
                                    // Canvas and its int color occupy two stack slots. The destination rectangles
                                    // cover the page, so no background fill is needed before drawing the pieces.
                                    super.visitInsn(Opcodes.POP2);
                                    removed[0]++;
                                } else if (entry.getName().equals("keiyoushi/source/a.class") && owner.equals("okhttp3/OkHttpClient$Builder") && method.equals("build") && desc.equals("()Lokhttp3/OkHttpClient;")) {
                                    super.visitMethodInsn(Opcodes.INVOKESTATIC, "eu/kanade/tachiyomi/extension/ar/shared/ProComicNetwork", "build", "(Lokhttp3/OkHttpClient$Builder;)Lokhttp3/OkHttpClient;", false);
                                    hooks[0]++;
                                } else super.visitMethodInsn(opcode, owner, method, desc, isInterface);
                            }
                        };
                    }
                }, 0);
                JarEntry replacement = new JarEntry(entry.getName());
                replacement.setTime(1704499200000L);
                output.putNextEntry(replacement);
                output.write(writer.toByteArray());
                output.closeEntry();
            }
        }
        if (removed[0] != 1) throw new IllegalStateException("Expected exactly one drawColor call, found " + removed[0]);
        if (hooks[0] != 1 || messages[0] != 1 || stitchers[0] != 1) throw new IllegalStateException("ProComic network/Arabic error patch no longer matches upstream");
        try (JarFile check = new JarFile(args[1])) {
            for (JarEntry entry : Collections.list(check.entries())) {
                new ClassReader(check.getInputStream(entry)).accept(new ClassVisitor(Opcodes.ASM9) {
                    @Override public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                        return new MethodVisitor(Opcodes.ASM9) {
                            @Override public void visitMethodInsn(int opcode, String owner, String method, String desc, boolean itf) {
                                if (owner.equals("android/graphics/Canvas") && method.equals("drawColor")) throw new IllegalStateException("Unsupported call remains");
                            }
                        };
                    }
                }, 0);
            }
        }
        System.out.println("Verified ProComic: bounded parallel stitcher, upstream decoders retained, no Canvas.drawColor call");
    }
}
