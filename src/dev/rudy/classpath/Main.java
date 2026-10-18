package dev.rudy.classpath;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Main {
  public static final String VERSION = "0.1.0";
  private static final String HELP =
      """
      RUDY / CLASSPATH — know which class definition wins
      scan [--target 8..25] [--enable-preview] [--format json|sarif|text]
           [--fail-on error|warning|none] first.jar second.jar ...
      explain [--target 8..25] [--enable-preview] binary.Name first.jar ...
      compare [--target 8..25] [--enable-preview] [--fail-on-change]
              before.classpath after.classpath
      --version
      Options precede file arguments. Classpath lists: UTF-8, one JAR path per line,
      relative to the list file; empty lines and lines beginning # are ignored.
      Exit: 0 completed, 1 selected policy/change threshold reached, 2 rejected input/usage/I/O.
      Explicit ordinary JAR model only. No target classes are executed or defined.
      """;

  public record Explanation(
      String schema,
      int targetJava,
      boolean preview,
      String name,
      boolean found,
      Scanner.Resolution resolution,
      List<Scanner.Finding> findings,
      List<String> notices) {}

  private Main() {}

  public static void main(String[] args) {
    System.exit(run(args, System.out, System.err));
  }

  private static List<Path> classpath(Path file) throws IOException {
    if (!Files.isRegularFile(file)) throw new InputError("Classpath list must be a regular file.");
    byte[] data;
    try (InputStream in = Files.newInputStream(file)) {
      data = in.readNBytes(65537);
    }
    if (data.length > 65536) throw new InputError("Classpath list exceeds 64 KiB.");
    List<Path> paths = new ArrayList<>();
    Path parent = file.toAbsolutePath().getParent();
    for (String line : ManifestData.utf8(data).split("\\r\\n|\\r|\\n", -1)) {
      String path = line.strip();
      if (path.isEmpty() || path.startsWith("#")) continue;
      paths.add(parent.resolve(path));
      if (paths.size() > Scanner.MAX_ARCHIVES)
        throw new InputError("Classpath list exceeds 32 JAR paths.");
    }
    return paths;
  }

  public static int run(String[] args, OutputStream out, PrintStream diagnostic) {
    try {
      if (args.length == 0 || args.length == 1 && Set.of("--help", "help").contains(args[0])) {
        out.write(HELP.getBytes(StandardCharsets.UTF_8));
        return 0;
      }
      if (args.length == 1 && args[0].equals("--version")) {
        out.write(("classpath " + VERSION + "\n").getBytes(StandardCharsets.UTF_8));
        return 0;
      }
      String command = args[0];
      if (!Set.of("scan", "explain", "compare").contains(command))
        throw new IllegalArgumentException("Unknown command. Use --help.");
      int target = 21;
      boolean preview = false, failChange = false;
      String format = "json", threshold = "error";
      Set<String> seen = new HashSet<>();
      int position = 1;
      while (position < args.length && args[position].startsWith("--")) {
        String flag = args[position++];
        if (!seen.add(flag)) throw new IllegalArgumentException("Duplicate command option.");
        if (flag.equals("--help")) {
          out.write(HELP.getBytes(StandardCharsets.UTF_8));
          return 0;
        }
        if (flag.equals("--enable-preview")) {
          preview = true;
          continue;
        }
        if (flag.equals("--fail-on-change") && command.equals("compare")) {
          failChange = true;
          continue;
        }
        if (!Set.of("--target", "--format", "--fail-on").contains(flag) || position >= args.length)
          throw new IllegalArgumentException("Invalid command option.");
        String value = args[position++];
        if (flag.equals("--target")) {
          if (!value.matches("[0-9]{1,2}"))
            throw new IllegalArgumentException("Target Java must be from 8 through 25.");
          target = Integer.parseInt(value);
        } else if (flag.equals("--format") && command.equals("scan")) format = value;
        else if (flag.equals("--fail-on") && command.equals("scan")) threshold = value;
        else throw new IllegalArgumentException("Option does not apply to this command.");
      }
      Scanner.Options options = new Scanner.Options(target, preview);
      Scanner.gate(List.of(), threshold);
      if (!Set.of("json", "sarif", "text").contains(format))
        throw new IllegalArgumentException("Unsupported report format.");
      List<String> remaining = Arrays.asList(args).subList(position, args.length);
      Object result;
      boolean failed = false;
      String text = null;
      if (command.equals("compare")) {
        if (remaining.size() != 2)
          throw new IllegalArgumentException("Compare requires two classpath list files.");
        var comparison =
            Scanner.compare(
                Scanner.scan(classpath(Path.of(remaining.get(0))), options),
                Scanner.scan(classpath(Path.of(remaining.get(1))), options));
        result = comparison;
        failed =
            failChange
                && (!comparison.added().isEmpty()
                    || !comparison.removed().isEmpty()
                    || !comparison.changed().isEmpty());
      } else {
        String query = null;
        if (command.equals("explain")) {
          if (remaining.size() < 2)
            throw new IllegalArgumentException(
                "Explain requires a binary class name and JAR paths.");
          query = remaining.getFirst();
          remaining = remaining.subList(1, remaining.size());
        }
        var report = Scanner.scan(remaining.stream().map(Path::of).toList(), options);
        if (query != null) {
          String name = query;
          var resolution =
              report.classes().stream().filter(c -> c.name().equals(name)).findFirst().orElse(null);
          result =
              new Explanation(
                  "rudy-classpath/explain/v1",
                  target,
                  preview,
                  name,
                  resolution != null,
                  resolution,
                  report.findings().stream().filter(f -> f.subject().equals(name)).toList(),
                  report.notices());
        } else {
          result = format.equals("sarif") ? Outputs.sarif(report) : report;
          if (format.equals("text")) text = Outputs.text(report);
          failed = Scanner.gate(report.findings(), threshold);
        }
      }
      out.write((text == null ? Json.encode(result) : text).getBytes(StandardCharsets.UTF_8));
      out.flush();
      return failed ? 1 : 0;
    } catch (InputError | IllegalArgumentException e) {
      diagnostic.println(e.getMessage());
      return 2;
    } catch (IOException e) {
      diagnostic.println("Unable to read a supported JAR inventory or write the report.");
      return 2;
    }
  }
}
