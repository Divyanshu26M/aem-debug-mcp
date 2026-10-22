package dev.aemdebug.project;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeSet;

/**
 * Minimal class file reader: method line ranges from LineNumberTable and the SourceFile attribute. Used to compare
 * the class loaded in AEM (via JDI) with the local build (stale deployment detection).
 */
public final class ClassFileInfo {

    /** Lines of one method. */
    public static final class MethodLines {
        public final String name;
        public final String descriptor;
        public final TreeSet<Integer> lines = new TreeSet<>();

        MethodLines(String name, String descriptor) {
            this.name = name;
            this.descriptor = descriptor;
        }

        public int first() {
            return lines.isEmpty() ? -1 : lines.first();
        }

        public int last() {
            return lines.isEmpty() ? -1 : lines.last();
        }
    }

    public String className;
    public String sourceFile;
    public int majorVersion;
    public final Map<String, MethodLines> methods = new LinkedHashMap<>();
    public final TreeSet<Integer> allLines = new TreeSet<>();

    public static ClassFileInfo read(Path classFile) throws IOException {
        return parse(Files.readAllBytes(classFile));
    }

    public static ClassFileInfo parse(byte[] bytes) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        if (in.readInt() != 0xCAFEBABE) {
            throw new IOException("Not a class file");
        }
        ClassFileInfo info = new ClassFileInfo();
        in.readUnsignedShort();
        info.majorVersion = in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] classNameIndex = new int[count];
        for (int i = 1; i < count; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1:
                    utf8[i] = in.readUTF();
                    break;
                case 3:
                case 4:
                    in.readInt();
                    break;
                case 5:
                case 6:
                    in.readLong();
                    i++;
                    break;
                case 7:
                    classNameIndex[i] = in.readUnsignedShort();
                    break;
                case 8:
                case 16:
                case 19:
                case 20:
                    in.readUnsignedShort();
                    break;
                case 9:
                case 10:
                case 11:
                case 12:
                case 17:
                case 18:
                    in.readInt();
                    break;
                case 15:
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                    break;
                default:
                    throw new IOException("Unknown constant pool tag " + tag);
            }
        }
        in.readUnsignedShort();
        int thisClass = in.readUnsignedShort();
        String internal = utf8[classNameIndex[thisClass]];
        info.className = internal == null ? null : internal.replace('/', '.');
        in.readUnsignedShort();
        int interfaces = in.readUnsignedShort();
        for (int i = 0; i < interfaces; i++) {
            in.readUnsignedShort();
        }
        int fields = in.readUnsignedShort();
        for (int i = 0; i < fields; i++) {
            in.readUnsignedShort();
            in.readUnsignedShort();
            in.readUnsignedShort();
            skipAttributes(in);
        }
        int methods = in.readUnsignedShort();
        for (int i = 0; i < methods; i++) {
            in.readUnsignedShort();
            String name = utf8[in.readUnsignedShort()];
            String descriptor = utf8[in.readUnsignedShort()];
            MethodLines lines = new MethodLines(name, descriptor);
            int attributes = in.readUnsignedShort();
            for (int a = 0; a < attributes; a++) {
                String attributeName = utf8[in.readUnsignedShort()];
                int length = in.readInt();
                if ("Code".equals(attributeName)) {
                    readCode(in, utf8, lines);
                } else {
                    in.skipBytes(length);
                }
            }
            info.methods.put(name + descriptor, lines);
            info.allLines.addAll(lines.lines);
        }
        int attributes = in.readUnsignedShort();
        for (int a = 0; a < attributes; a++) {
            String attributeName = utf8[in.readUnsignedShort()];
            int length = in.readInt();
            if ("SourceFile".equals(attributeName)) {
                info.sourceFile = utf8[in.readUnsignedShort()];
            } else {
                in.skipBytes(length);
            }
        }
        return info;
    }

    private static void readCode(DataInputStream in, String[] utf8, MethodLines lines) throws IOException {
        in.readUnsignedShort();
        in.readUnsignedShort();
        int codeLength = in.readInt();
        in.skipBytes(codeLength);
        int exceptions = in.readUnsignedShort();
        in.skipBytes(exceptions * 8);
        int attributes = in.readUnsignedShort();
        for (int a = 0; a < attributes; a++) {
            String attributeName = utf8[in.readUnsignedShort()];
            int length = in.readInt();
            if ("LineNumberTable".equals(attributeName)) {
                int entries = in.readUnsignedShort();
                for (int e = 0; e < entries; e++) {
                    in.readUnsignedShort();
                    lines.lines.add(in.readUnsignedShort());
                }
            } else {
                in.skipBytes(length);
            }
        }
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int attributes = in.readUnsignedShort();
        for (int a = 0; a < attributes; a++) {
            in.readUnsignedShort();
            in.skipBytes(in.readInt());
        }
    }
}
