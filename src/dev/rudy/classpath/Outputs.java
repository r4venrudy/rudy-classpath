package dev.rudy.classpath;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Outputs {
  public static final Map<String, String> RULES =
      Map.ofEntries(
          Map.entry("CP001", "Different class definitions"),
          Map.entry("CP002", "Identical duplicate class"),
          Map.entry("CP003", "Class version incompatible with target"),
          Map.entry("CP004", "Package sealing across origins"),
          Map.entry("CP005", "Class binary-name mismatch"),
          Map.entry("CP006", "Service provider absent from inventory"),
          Map.entry("CP007", "Service provider constructor flags incompatible"),
          Map.entry("CP008", "Unresolved manifest Class-Path"),
          Map.entry("CP009", "Reserved java package"),
          Map.entry("CP010", "Multi-release directory version exceeded"));

  private Outputs() {}

  public static Map<String, Object> sarif(Scanner.Report report) {
    List<Object> rules = new ArrayList<>();
    for (var entry : new TreeMap<>(RULES).entrySet())
      rules.add(Map.of("id", entry.getKey(), "shortDescription", Map.of("text", entry.getValue())));
    List<Object> results = new ArrayList<>();
    Map<String, String> hashes = new HashMap<>();
    for (var archive : report.archives()) hashes.put(archive.id(), archive.sha256());
    for (var finding : report.findings()) {
      String fingerprint =
          Scanner.sha(
              Json.encode(
                      List.of(
                          finding.rule(),
                          finding.subject(),
                          finding.archives().stream().map(hashes::get).toList()))
                  .getBytes(StandardCharsets.UTF_8));
      results.add(
          Map.of(
              "ruleId",
              finding.rule(),
              "level",
              finding.level(),
              "message",
              Map.of("text", finding.message()),
              "partialFingerprints",
              Map.of("classpath/v1", fingerprint),
              "properties",
              Map.of(
                  "subject",
                  finding.subject(),
                  "archives",
                  finding.archives(),
                  "targetJava",
                  report.targetJava(),
                  "preview",
                  report.preview())));
    }
    return Map.of(
        "$schema",
        "https://json.schemastore.org/sarif-2.1.0.json",
        "version",
        "2.1.0",
        "runs",
        List.of(
            Map.of(
                "tool",
                Map.of(
                    "driver",
                    Map.of(
                        "name",
                        "Rudy Classpath",
                        "version",
                        Main.VERSION,
                        "informationUri",
                        "https://github.com/r4venrudy/rudy-classpath",
                        "rules",
                        rules)),
                "results",
                results,
                "properties",
                Map.of("archives", report.archives(), "coverageNotices", report.notices()))));
  }

  public static String text(Scanner.Report report) {
    StringBuilder out =
        new StringBuilder(
            "Rudy Classpath "
                + Main.VERSION
                + "\nTarget Java: "
                + report.targetJava()
                + "; preview: "
                + report.preview()
                + "\n");
    out.append("JARs: ")
        .append(report.archives().size())
        .append("; effective class names: ")
        .append(report.classes().size())
        .append("; services: ")
        .append(report.services().size())
        .append('\n');
    for (var finding : report.findings())
      out.append(finding.level())
          .append(' ')
          .append(finding.rule())
          .append(' ')
          .append(terminal(finding.subject()))
          .append(" — ")
          .append(finding.message())
          .append('\n');
    out.append("Model: explicit JAR order only; no class execution or signature verification.\n");
    return out.toString();
  }

  private static String terminal(String value) {
    StringBuilder out = new StringBuilder();
    value
        .codePoints()
        .forEach(
            c -> {
              if (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)
                out.append('?');
              else out.appendCodePoint(c);
            });
    return out.toString();
  }
}
