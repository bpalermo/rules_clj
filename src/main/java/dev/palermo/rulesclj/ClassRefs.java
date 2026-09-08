package dev.palermo.rulesclj;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;

/**
 * The class names a compiled class refers to, read from its constant pool.
 *
 * <p>Enough of the class-file format to walk the constant pool and no more: the CONSTANT_Class
 * entries are exactly the names the JVM will resolve when the class is linked, which is what
 * makes them the right thing to check a direct-linked call against. Hand-rolled rather than
 * pulled from ASM because this shim has no third-party dependencies, by design.
 */
final class ClassRefs {

    private static final int UTF8 = 1;
    private static final int INTEGER = 3;
    private static final int FLOAT = 4;
    private static final int LONG = 5;
    private static final int DOUBLE = 6;
    private static final int CLASS = 7;
    private static final int STRING = 8;
    private static final int FIELDREF = 9;
    private static final int METHODREF = 10;
    private static final int INTERFACE_METHODREF = 11;
    private static final int NAME_AND_TYPE = 12;
    private static final int METHOD_HANDLE = 15;
    private static final int METHOD_TYPE = 16;
    private static final int DYNAMIC = 17;
    private static final int INVOKE_DYNAMIC = 18;
    private static final int MODULE = 19;
    private static final int PACKAGE = 20;

    private ClassRefs() {}

    /** Internal names (`foo/bar$baz`) this class file refers to. */
    static Set<String> of(Path classFile) throws IOException {
        try (InputStream in = Files.newInputStream(classFile);
                DataInputStream data = new DataInputStream(new java.io.BufferedInputStream(in))) {
            if (data.readInt() != 0xCAFEBABE) {
                return Set.of();
            }
            data.readUnsignedShort(); // minor
            data.readUnsignedShort(); // major
            int count = data.readUnsignedShort();

            String[] utf8 = new String[count];
            int[] classNameIndex = new int[count];
            for (int i = 1; i < count; i++) {
                int tag = data.readUnsignedByte();
                switch (tag) {
                    case UTF8 -> utf8[i] = data.readUTF();
                    case CLASS -> classNameIndex[i] = data.readUnsignedShort();
                    case STRING, METHOD_TYPE, MODULE, PACKAGE -> data.readUnsignedShort();
                    case INTEGER, FLOAT, FIELDREF, METHODREF, INTERFACE_METHODREF, NAME_AND_TYPE,
                            DYNAMIC, INVOKE_DYNAMIC -> data.readInt();
                    case METHOD_HANDLE -> {
                        data.readUnsignedByte();
                        data.readUnsignedShort();
                    }
                    // A long or double takes two constant-pool slots. The unused one is
                    // never referenced, but skipping it is what keeps the walk aligned.
                    case LONG, DOUBLE -> {
                        data.readLong();
                        i++;
                    }
                    default -> throw new IOException(
                            "unknown constant pool tag " + tag + " in " + classFile);
                }
            }

            Set<String> names = new TreeSet<>();
            for (int i = 1; i < count; i++) {
                if (classNameIndex[i] != 0) {
                    String name = utf8[classNameIndex[i]];
                    if (name != null) {
                        names.add(name);
                    }
                }
            }
            return names;
        }
    }
}
