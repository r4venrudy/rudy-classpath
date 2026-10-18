package dev.rudy.classpath;

import java.lang.reflect.*;
import java.util.*;

/** Output-only JSON encoder for records and ordinary immutable report values. */
public final class Json {
  private Json() {}

  public static String encode(Object value) {
    StringBuilder out = new StringBuilder();
    write(value, out, 0);
    return out + "\n";
  }

  private static void string(String value, StringBuilder out) {
    out.append('"');
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\n' -> out.append("\\n");
        case '\r' -> out.append("\\r");
        case '\t' -> out.append("\\t");
        default -> {
          if (c < 32 || Character.isSurrogate(c))
            out.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
          else out.append(c);
        }
      }
    }
    out.append('"');
  }

  private static void write(Object value, StringBuilder out, int depth) {
    if (depth > 64) throw new IllegalArgumentException("Report nesting limit exceeded.");
    if (value == null) {
      out.append("null");
      return;
    }
    if (value instanceof String text) {
      string(text, out);
      return;
    }
    if (value instanceof Boolean
        || value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      out.append(value);
      return;
    }
    if (value instanceof Map<?, ?> map) {
      out.append('{');
      boolean first = true;
      Map<String, Object> ordered = new TreeMap<>();
      for (var entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key))
          throw new IllegalArgumentException("JSON keys must be strings.");
        ordered.put(key, entry.getValue());
      }
      for (var entry : ordered.entrySet()) {
        if (!first) out.append(',');
        first = false;
        string(entry.getKey(), out);
        out.append(':');
        write(entry.getValue(), out, depth + 1);
      }
      out.append('}');
      return;
    }
    if (value instanceof Iterable<?> items) {
      out.append('[');
      boolean first = true;
      for (Object item : items) {
        if (!first) out.append(',');
        first = false;
        write(item, out, depth + 1);
      }
      out.append(']');
      return;
    }
    if (value.getClass().isRecord()) {
      Map<String, Object> map = new LinkedHashMap<>();
      for (RecordComponent component : value.getClass().getRecordComponents())
        try {
          map.put(component.getName(), component.getAccessor().invoke(value));
        } catch (ReflectiveOperationException e) {
          throw new IllegalArgumentException("Unable to encode report record.", e);
        }
      write(map, out, depth + 1);
      return;
    }
    throw new IllegalArgumentException("Unsupported report value.");
  }
}
