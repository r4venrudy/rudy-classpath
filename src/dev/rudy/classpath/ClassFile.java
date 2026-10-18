package dev.rudy.classpath;

import java.io.*;
import java.util.*;

/** Bounded structural reader. It never defines a class or verifies bytecode. */
public final class ClassFile {
  public record Info(
      String name,
      int major,
      int minor,
      int access,
      String parent,
      List<String> interfaces,
      boolean publicNoArgConstructor) {}

  private final DataInputStream in;
  private final Object[] pool;
  private final int[] tags;

  private ClassFile(byte[] data) throws IOException {
    in = new DataInputStream(new ByteArrayInputStream(data));
    if (in.readInt() != 0xCAFEBABE) throw invalid();
    minor = in.readUnsignedShort();
    major = in.readUnsignedShort();
    if (major < 45 || (major >= 56 && minor != 0 && minor != 65535)) throw invalid();
    int count = in.readUnsignedShort();
    if (count < 2) throw invalid();
    pool = new Object[count];
    tags = new int[count];
    for (int i = 1; i < count; i++) {
      int tag = in.readUnsignedByte();
      tags[i] = tag;
      switch (tag) {
        case 1 -> pool[i] = in.readUTF();
        case 3, 4 -> in.skipNBytes(4);
        case 5, 6 -> {
          in.skipNBytes(8);
          if (++i >= count) throw invalid();
        }
        case 7, 8, 16, 19, 20 -> pool[i] = in.readUnsignedShort();
        case 9, 10, 11, 12, 17, 18 -> in.skipNBytes(4);
        case 15 -> in.skipNBytes(3);
        default -> throw invalid();
      }
    }
  }

  private final int major, minor;

  private static IOException invalid() {
    return new InputError("Malformed or unsupported class-file structure.");
  }

  private String utf(int index) throws IOException {
    if (index <= 0 || index >= pool.length || tags[index] != 1) throw invalid();
    return (String) pool[index];
  }

  private String className(int index) throws IOException {
    if (index <= 0 || index >= pool.length || tags[index] != 7) throw invalid();
    String name = utf((Integer) pool[index]);
    if (name.isEmpty()
        || name.startsWith("/")
        || name.endsWith("/")
        || name.contains("//")
        || name.chars().anyMatch(c -> c == '.' || c == ';' || c == '[' || c == 0)) throw invalid();
    return name.replace('/', '.');
  }

  private void attributes() throws IOException {
    int count = in.readUnsignedShort();
    for (int n = 0; n < count; n++) {
      utf(in.readUnsignedShort());
      long length = Integer.toUnsignedLong(in.readInt());
      in.skipNBytes(length);
    }
  }

  private boolean members(boolean methods) throws IOException {
    boolean constructor = false;
    int count = in.readUnsignedShort();
    for (int n = 0; n < count; n++) {
      int access = in.readUnsignedShort();
      String name = utf(in.readUnsignedShort());
      String type = utf(in.readUnsignedShort());
      if (methods && name.equals("<init>") && type.equals("()V") && (access & 1) != 0)
        constructor = true;
      attributes();
    }
    return constructor;
  }

  public static Info parse(byte[] data) throws IOException {
    if (data.length > Scanner.MAX_ENTRY) throw invalid();
    try {
      ClassFile f = new ClassFile(data);
      int access = f.in.readUnsignedShort();
      String name = f.className(f.in.readUnsignedShort());
      int parentIndex = f.in.readUnsignedShort();
      String parent = parentIndex == 0 ? "" : f.className(parentIndex);
      int count = f.in.readUnsignedShort();
      List<String> interfaces = new ArrayList<>();
      for (int n = 0; n < count; n++) interfaces.add(f.className(f.in.readUnsignedShort()));
      f.members(false);
      boolean constructor = f.members(true);
      f.attributes();
      if (f.in.read() != -1) throw invalid();
      return new Info(name, f.major, f.minor, access, parent, List.copyOf(interfaces), constructor);
    } catch (EOFException | UTFDataFormatException e) {
      throw invalid();
    }
  }
}
