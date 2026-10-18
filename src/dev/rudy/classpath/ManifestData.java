package dev.rudy.classpath;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;

/** Strict manifest subset, including continuations and package sealing sections. */
public record ManifestData(Map<String, String> main, Map<String, Map<String, String>> sections) {
  public static ManifestData empty() {
    return new ManifestData(Map.of(), Map.of());
  }

  public boolean multiRelease() {
    return main.getOrDefault("multi-release", "").equalsIgnoreCase("true");
  }

  public boolean sealed(String packageName) {
    Map<String, String> section =
        sections.getOrDefault(packageName.replace('.', '/') + "/", Map.of());
    return section.getOrDefault("sealed", main.getOrDefault("sealed", "")).equalsIgnoreCase("true");
  }

  public static String utf8(byte[] data) throws IOException {
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(data))
          .toString();
    } catch (CharacterCodingException e) {
      throw new InputError("Invalid UTF-8 metadata.");
    }
  }

  public static ManifestData parse(byte[] data) throws IOException {
    if (data.length > 1 << 20) throw new InputError("Manifest exceeds 1 MiB.");
    String text = utf8(data);
    if (text.indexOf('\0') >= 0) throw new InputError("Invalid manifest text.");
    if (!text.isEmpty() && !text.endsWith("\n") && !text.endsWith("\r"))
      throw new InputError("Manifest must end with a newline.");
    if (text.startsWith("\n") || text.startsWith("\r"))
      throw new InputError("Manifest begins with an empty main section.");
    List<List<String>> groups = new ArrayList<>();
    List<String> current = new ArrayList<>();
    for (String line : text.split("\\r\\n|\\r|\\n", -1)) {
      if (line.isEmpty()) {
        if (!current.isEmpty()) {
          groups.add(current);
          current = new ArrayList<>();
        }
        continue;
      }
      if (line.startsWith(" ")) {
        if (current.isEmpty()) throw new InputError("Manifest continuation has no attribute.");
        int last = current.size() - 1;
        String combined = current.get(last) + line.substring(1);
        if (combined.length() > 65535)
          throw new InputError("Manifest attribute exceeds 65535 characters.");
        current.set(last, combined);
      } else current.add(line);
    }
    if (!current.isEmpty()) groups.add(current);
    if (groups.isEmpty()) return empty();
    if (!groups.getFirst().getFirst().toLowerCase(Locale.ROOT).startsWith("manifest-version: "))
      throw new InputError("Manifest-Version must begin the main section.");
    Map<String, String> main = parseGroup(groups.getFirst());
    if (main.containsKey("name")
        || !main.getOrDefault("manifest-version", "").matches("[0-9]+(?:\\.[0-9]+)*"))
      throw new InputError("Manifest requires a main Manifest-Version attribute.");
    Map<String, Map<String, String>> sections = new TreeMap<>();
    for (int n = 1; n < groups.size(); n++) {
      if (!groups.get(n).getFirst().toLowerCase(Locale.ROOT).startsWith("name: "))
        throw new InputError("Name must begin a manifest section.");
      Map<String, String> attributes = parseGroup(groups.get(n));
      String name = attributes.remove("name");
      if (name == null || name.isEmpty())
        throw new InputError("Manifest section requires a Name attribute.");
      // Repeated sections merge; the later section overrides earlier values.
      sections.computeIfAbsent(name, k -> new TreeMap<>()).putAll(attributes);
    }
    return new ManifestData(main, sections);
  }

  private static Map<String, String> parseGroup(List<String> lines) throws IOException {
    Map<String, String> result = new TreeMap<>();
    for (String line : lines) {
      int at = line.indexOf(": ");
      if (at < 1) throw new InputError("Malformed manifest attribute.");
      String name = line.substring(0, at).toLowerCase(Locale.ROOT);
      if (!name.matches("[a-z0-9][a-z0-9_-]{0,69}")
          || line.length() > 65535
          || result.putIfAbsent(name, line.substring(at + 2)) != null)
        throw new InputError("Duplicate or malformed manifest attribute.");
    }
    return result;
  }
}
