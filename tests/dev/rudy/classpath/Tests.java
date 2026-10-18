package dev.rudy.classpath;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import javax.tools.*;

/** Dependency-free regression runner; executes only its own generated fixtures. */
public final class Tests {
  @FunctionalInterface
  interface Work {
    void run() throws Exception;
  }

  private static Path temp;
  private static int sequence, passed, failed;

  private static void check(boolean condition, String message) {
    if (!condition) throw new AssertionError(message);
  }

  private static void test(String name, Work body) {
    try {
      body.run();
      passed++;
      System.out.println("PASS " + name);
    } catch (Exception | AssertionError e) {
      failed++;
      System.err.println("FAIL " + name + ": " + e);
    }
  }

  private static void reject(Work body) throws Exception {
    try {
      body.run();
    } catch (IOException | IllegalArgumentException e) {
      return;
    }
    throw new AssertionError("Input accepted");
  }

  private static byte[] text(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private static Map<String, byte[]> compile(Map<String, String> sources, int release)
      throws Exception {
    Path root = Files.createDirectory(temp.resolve("compile-" + (sequence++))),
        classes = Files.createDirectory(root.resolve("classes"));
    List<String> args =
        new ArrayList<>(
            List.of(
                "--release",
                Integer.toString(release),
                "-encoding",
                "UTF-8",
                "-d",
                classes.toString()));
    for (var source : sources.entrySet()) {
      Path file = root.resolve(source.getKey());
      Files.createDirectories(file.getParent());
      Files.writeString(file, source.getValue());
      args.add(file.toString());
    }
    ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
    int code =
        ToolProvider.getSystemJavaCompiler()
            .run(null, diagnostics, diagnostics, args.toArray(String[]::new));
    check(code == 0, "Fixture compile failed: " + diagnostics.toString(StandardCharsets.UTF_8));
    Map<String, byte[]> result = new TreeMap<>();
    try (var files = Files.walk(classes)) {
      for (Path file : files.filter(Files::isRegularFile).toList())
        result.put(
            classes.relativize(file).toString().replace(File.separatorChar, '/'),
            Files.readAllBytes(file));
    }
    return result;
  }

  private static Path jar(Map<String, byte[]> entries, String manifest) throws Exception {
    Path path = temp.resolve("sample-" + (sequence++) + ".jar");
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
      if (manifest != null) {
        zip.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
        zip.write(text(manifest));
        zip.closeEntry();
      }
      for (var entry : new TreeMap<>(entries).entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
    return path;
  }

  private static String manifest(String extra) {
    return "Manifest-Version: 1.0\r\n" + extra + "\r\n";
  }

  private static Scanner.Report scan(Path... paths) throws Exception {
    return Scanner.scan(List.of(paths), new Scanner.Options(21, false));
  }

  private static boolean rule(Scanner.Report report, String id) {
    return report.findings().stream().anyMatch(f -> f.rule().equals(id));
  }

  private static Scanner.Resolution definition(Scanner.Report report, String name) {
    return report.classes().stream().filter(c -> c.name().equals(name)).findFirst().orElseThrow();
  }

  private static int actualValue(List<Path> jars) throws Exception {
    URL[] urls = new URL[jars.size()];
    for (int n = 0; n < urls.length; n++) urls[n] = jars.get(n).toUri().toURL();
    try (URLClassLoader loader = new URLClassLoader(urls, null)) {
      return (Integer) loader.loadClass("demo.Version").getMethod("value").invoke(null);
    }
  }

  private static void examples(Map<String, byte[]> a, Map<String, byte[]> b, Map<String, byte[]> mr)
      throws Exception {
    Path dir = Path.of("examples");
    Files.createDirectory(dir);
    Path first = jar(a, null),
        second = jar(b, null),
        multi =
            jar(
                Map.of(
                    "demo/Version.class",
                    a.get("demo/Version.class"),
                    "META-INF/versions/21/demo/Version.class",
                    mr.get("demo/Version.class")),
                manifest("Multi-Release: true\r\n"));
    Files.copy(first, dir.resolve("library-a.jar"));
    Files.copy(second, dir.resolve("library-b.jar"));
    Files.copy(multi, dir.resolve("multi-release.jar"));
    Files.writeString(dir.resolve("before.classpath"), "library-a.jar\nlibrary-b.jar\n");
    Files.writeString(dir.resolve("after.classpath"), "library-b.jar\nlibrary-a.jar\n");
  }

  public static void main(String[] args) throws Exception {
    temp = Files.createTempDirectory("rudy-classpath-tests-");
    try {
      var a =
          compile(
              Map.of(
                  "demo/Version.java",
                  "package demo; public class Version { public static int value(){return 1;} }"),
              8);
      var b =
          compile(
              Map.of(
                  "demo/Version.java",
                  "package demo; public class Version { public static int value(){return 2;} }"),
              8);
      var mr =
          compile(
              Map.of(
                  "demo/Version.java",
                  "package demo; public class Version { public static int value(){return 21;} }"),
              21);
      if (args.length == 1 && args[0].equals("--examples")) {
        examples(a, b, mr);
        return;
      }
      Path first = jar(a, null), second = jar(b, null);
      test(
          "classpath winner agrees with real isolated URLClassLoader",
          () -> {
            for (List<Path> order : List.of(List.of(first, second), List.of(second, first))) {
              var report = Scanner.scan(order, new Scanner.Options(21, false));
              check(rule(report, "CP001"), "missing conflict");
              check(
                  definition(report, "demo.Version")
                      .firstProvided()
                      .archiveSha256()
                      .equals(report.archives().getFirst().sha256()),
                  "wrong provider");
              check(
                  actualValue(order) == (order.getFirst().equals(first) ? 1 : 2),
                  "runtime oracle differs");
            }
          });
      test(
          "identical definitions are notes, not differing-byte conflicts",
          () -> {
            var report = scan(first, jar(a, null));
            check(rule(report, "CP002") && !rule(report, "CP001"), "wrong duplicate level");
          });
      test(
          "multi-release selection agrees with JarFile effective bytes",
          () -> {
            Path file =
                jar(
                    Map.of(
                        "demo/Version.class",
                        a.get("demo/Version.class"),
                        "META-INF/versions/21/demo/Version.class",
                        mr.get("demo/Version.class")),
                    manifest("Multi-Release: true\r\n"));
            for (int version : List.of(8, 11, 17, 21, 25)) {
              var report = Scanner.scan(List.of(file), new Scanner.Options(version, false));
              var selected = definition(report, "demo.Version").firstProvided();
              try (JarFile oracle =
                  new JarFile(
                      file.toFile(),
                      false,
                      ZipFile.OPEN_READ,
                      Runtime.Version.parse(Integer.toString(version)))) {
                byte[] expected;
                try (InputStream in =
                    oracle.getInputStream(oracle.getJarEntry("demo/Version.class"))) {
                  expected = in.readAllBytes();
                }
                check(
                    selected.sha256().equals(Scanner.sha(expected)),
                    "JarFile disagrees at " + version);
              }
            }
          });
      test(
          "MR disabled without manifest and with false flag",
          () -> {
            for (String manifest : Arrays.asList(null, manifest("Multi-Release: false\r\n"))) {
              Path file =
                  jar(
                      Map.of(
                          "demo/Version.class",
                          a.get("demo/Version.class"),
                          "META-INF/versions/21/demo/Version.class",
                          mr.get("demo/Version.class")),
                      manifest);
              check(
                  definition(scan(file), "demo.Version").firstProvided().release() == 0,
                  "version enabled unexpectedly");
            }
          });
      test(
          "noncanonical MR version directories are ignored",
          () -> {
            for (String version : List.of("08", "8", "021", "99999999999999", "x")) {
              Path file =
                  jar(
                      Map.of(
                          "demo/Version.class",
                          a.get("demo/Version.class"),
                          "META-INF/versions/" + version + "/demo/Version.class",
                          mr.get("demo/Version.class")),
                      manifest("Multi-Release: true\r\n"));
              check(
                  definition(scan(file), "demo.Version").firstProvided().release() == 0,
                  "invalid directory selected");
            }
          });
      test(
          "too-new class-file version is gated",
          () -> {
            var report = Scanner.scan(List.of(jar(mr, null)), new Scanner.Options(17, false));
            check(
                rule(report, "CP003") && Scanner.gate(report.findings(), "error"),
                "version missed");
          });
      test(
          "preview classes require matching target and explicit opt-in",
          () -> {
            byte[] preview = mr.get("demo/Version.class").clone();
            preview[4] = (byte) 255;
            preview[5] = (byte) 255;
            Path file = jar(Map.of("demo/Version.class", preview), null);
            check(rule(scan(file), "CP003"), "preview accepted without opt-in");
            check(
                !rule(Scanner.scan(List.of(file), new Scanner.Options(21, true)), "CP003"),
                "preview opt-in failed");
            check(
                rule(Scanner.scan(List.of(file), new Scanner.Options(25, true)), "CP003"),
                "old preview accepted");
          });
      test(
          "legacy minor 65535 is not a modern preview marker",
          () -> {
            byte[] legacy = a.get("demo/Version.class").clone();
            legacy[4] = (byte) 255;
            legacy[5] = (byte) 255;
            Path file = jar(Map.of("demo/Version.class", legacy), null);
            check(!rule(scan(file), "CP003"), "legacy class treated as preview");
            check(actualValue(List.of(file)) == 1, "legacy minor oracle mismatch");
          });
      test(
          "versioned class cannot exceed its MR directory",
          () -> {
            Path file =
                jar(
                    Map.of(
                        "demo/Version.class",
                        a.get("demo/Version.class"),
                        "META-INF/versions/11/demo/Version.class",
                        mr.get("demo/Version.class")),
                    manifest("Multi-Release: true\r\n"));
            check(rule(scan(file), "CP010"), "invalid MR bytecode missed");
          });
      test(
          "binary declaration must match effective class path",
          () -> {
            check(
                rule(
                    scan(jar(Map.of("demo/Wrong.class", a.get("demo/Version.class")), null)),
                    "CP005"),
                "name mismatch missed");
          });
      var other = compile(Map.of("demo/Other.java", "package demo; public class Other {}"), 8);
      test(
          "sealed split packages are reported",
          () -> {
            check(
                rule(scan(jar(a, manifest("Sealed: true\r\n")), jar(other, null)), "CP004"),
                "sealing missed");
          });
      test(
          "sealing finding agrees with generated-class runtime behavior",
          () -> {
            Path sealed = jar(a, manifest("Sealed: true\r\n")), unsealed = jar(other, null);
            try (URLClassLoader loader =
                new URLClassLoader(
                    new URL[] {sealed.toUri().toURL(), unsealed.toUri().toURL()}, null)) {
              loader.loadClass("demo.Version");
              boolean rejected = false;
              try {
                loader.loadClass("demo.Other");
              } catch (SecurityException e) {
                rejected = true;
              }
              check(rejected && rule(scan(sealed, unsealed), "CP004"), "sealing oracle disagrees");
            }
          });
      test(
          "per-package sealing override is respected",
          () -> {
            String value = manifest("Sealed: true\r\n") + "Name: demo/\r\nSealed: false\r\n\r\n";
            check(!rule(scan(jar(a, value), jar(other, null)), "CP004"), "override ignored");
          });
      test(
          "manifest Class-Path stays explicit and unresolved",
          () -> {
            var report =
                scan(jar(a, manifest("Class-Path: https://example.test/never-fetch.jar\r\n")));
            check(
                rule(report, "CP008") && report.archives().size() == 1,
                "manifest followed or missed");
          });
      var serviceClasses =
          compile(
              Map.of(
                  "svc/API.java",
                  "package svc; public interface API {}",
                  "svc/Good.java",
                  "package svc; public class Good implements API {public Good(){}}",
                  "svc/PrivateCtor.java",
                  "package svc; public class PrivateCtor implements API {private PrivateCtor(){}}"),
              8);
      test(
          "services merge in classpath descriptor order and deduplicate",
          () -> {
            Map<String, byte[]> left = new TreeMap<>(serviceClasses);
            left.put("META-INF/services/svc.API", text("# comment\nsvc.Good\nsvc.Good\n"));
            var report =
                scan(
                    jar(left, null),
                    jar(
                        Map.of("META-INF/services/svc.API", text("svc.Missing\nsvc.Good\n")),
                        null));
            check(
                report.services().getFirst().providers().equals(List.of("svc.Good", "svc.Missing")),
                "service order changed");
            check(rule(report, "CP006") && !rule(report, "CP007"), "service diagnostics wrong");
          });
      test(
          "private provider constructor is flagged",
          () -> {
            Map<String, byte[]> entries = new TreeMap<>(serviceClasses);
            entries.put("META-INF/services/svc.API", text("svc.PrivateCtor\n"));
            check(rule(scan(jar(entries, null)), "CP007"), "constructor missed");
          });
      test(
          "ServiceLoader oracle instantiates only a generated valid provider",
          () -> {
            Map<String, byte[]> entries = new TreeMap<>(serviceClasses);
            entries.put("META-INF/services/svc.API", text("svc.Good\n"));
            Path file = jar(entries, null);
            try (URLClassLoader loader =
                new URLClassLoader(new URL[] {file.toUri().toURL()}, null)) {
              Class<?> api = loader.loadClass("svc.API");
              Object provider = ServiceLoader.load(api, loader).iterator().next();
              check(provider.getClass().getName().equals("svc.Good"), "oracle mismatch");
              check(!rule(scan(file), "CP007"), "valid provider rejected");
            }
          });
      test(
          "versioned META-INF service descriptors cannot override base metadata",
          () -> {
            Path file =
                jar(
                    Map.of(
                        "META-INF/services/svc.API",
                        text("svc.Good\n"),
                        "META-INF/versions/21/META-INF/services/svc.API",
                        text("svc.Evil\n")),
                    manifest("Multi-Release: true\r\n"));
            check(
                scan(file).services().getFirst().providers().equals(List.of("svc.Good")),
                "versioned metadata selected");
          });
      test(
          "invalid service names and UTF-8 are rejected",
          () -> {
            for (byte[] value : List.of(text("bad/name\n"), new byte[] {(byte) 255}))
              reject(() -> scan(jar(Map.of("META-INF/services/svc.API", value), null)));
          });
      test(
          "service metadata has independent text and declaration limits",
          () -> {
            reject(() -> scan(jar(Map.of("META-INF/services/svc.API", new byte[65537]), null)));
            // The two descriptors together must exceed the global declaration bound.
            StringBuilder firstHalf = new StringBuilder(), secondHalf = new StringBuilder();
            for (int n = 0; n < 12000; n++)
              (n < 6000 ? firstHalf : secondHalf).append("p.C").append(n).append('\n');
            reject(
                () ->
                    scan(
                        jar(
                            Map.of(
                                "META-INF/services/svc.One",
                                text(firstHalf.toString()),
                                "META-INF/services/svc.Two",
                                text(secondHalf.toString())),
                            null)));
          });
      test(
          "module descriptor does not shadow an application class",
          () -> {
            var report =
                scan(
                    jar(Map.of("module-info.class", new byte[] {1}), null),
                    jar(Map.of("module-info.class", new byte[] {2}), null));
            check(
                report.classes().isEmpty()
                    && report.archives().stream().allMatch(Scanner.Archive::moduleDescriptor),
                "module descriptor modeled as class");
          });
      test(
          "unsafe and noncanonical ZIP names are rejected",
          () -> {
            for (String name :
                List.of(
                    "../escape",
                    "/absolute",
                    "a\\b",
                    "C:/drive",
                    "a//b",
                    "a/./b",
                    "a/../b",
                    "bad\u001bname")) reject(() -> scan(jar(Map.of(name, text("x")), null)));
          });
      test("duplicate canonical paths are rejected", () -> reject(() -> scan(first, first)));
      test(
          "duplicate central-directory names are rejected",
          () -> {
            Path file = jar(Map.of("x.txt", text("a"), "y.txt", text("b")), null);
            byte[] data = Files.readAllBytes(file);
            byte[] from = text("y.txt"), to = text("x.txt");
            for (int n = 0; n <= data.length - from.length; n++)
              if (Arrays.equals(Arrays.copyOfRange(data, n, n + from.length), from))
                System.arraycopy(to, 0, data, n, to.length);
            Files.write(file, data);
            reject(() -> scan(file));
          });
      test(
          "compressed expansion entry limit is enforced",
          () -> reject(() -> scan(jar(Map.of("bomb.bin", new byte[Scanner.MAX_ENTRY + 1]), null))));
      test(
          "CRC is checked even for unused resources",
          () -> {
            Path file = temp.resolve("crc.jar");
            byte[] payload = text("test-payload-unique");
            CRC32 crc = new CRC32();
            crc.update(payload);
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
              ZipEntry e = new ZipEntry("resource.txt");
              e.setMethod(ZipEntry.STORED);
              e.setSize(payload.length);
              e.setCompressedSize(payload.length);
              e.setCrc(crc.getValue());
              zip.putNextEntry(e);
              zip.write(payload);
              zip.closeEntry();
            }
            byte[] bytes = Files.readAllBytes(file);
            boolean replaced = false;
            for (int n = 0; n <= bytes.length - payload.length; n++)
              if (Arrays.equals(Arrays.copyOfRange(bytes, n, n + payload.length), payload)) {
                bytes[n] = 'X';
                replaced = true;
                break;
              }
            check(replaced, "fixture payload not found");
            Files.write(file, bytes);
            reject(() -> scan(file));
          });
      test(
          "archive entry count is bounded before entry enumeration",
          () -> {
            Path file = temp.resolve("many-entries.jar");
            try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
              for (int n = 0; n <= Scanner.MAX_ENTRIES; n++) {
                zip.putNextEntry(new ZipEntry("e" + n));
                zip.closeEntry();
              }
            }
            reject(() -> scan(file));
          });
      test(
          "malformed class structures and trailing bytes are rejected",
          () -> {
            byte[] valid = a.get("demo/Version.class");
            for (byte[] value :
                List.of(
                    new byte[] {1},
                    Arrays.copyOf(valid, valid.length - 1),
                    Arrays.copyOf(valid, valid.length + 1))) reject(() -> ClassFile.parse(value));
          });
      test(
          "modified UTF constant-pool text stays bounded by class bytes",
          () -> {
            byte[] bad = a.get("demo/Version.class").clone();
            bad[10] = 99;
            reject(() -> ClassFile.parse(bad));
          });
      test(
          "manifest continuations preserve exact MR and classpath values",
          () -> {
            var m =
                ManifestData.parse(
                    text(
                        "Manifest-Version: 1.0\r\n"
                            + "Multi-Release: tr\r\n"
                            + " ue\r\n"
                            + "Class-Path: a.jar \r\n"
                            + " b.jar\r\n\r\n"));
            check(
                m.multiRelease() && m.main().get("class-path").equals("a.jar b.jar"),
                "continuation lost");
          });
      test(
          "ambiguous manifests and duplicate attributes are rejected",
          () -> {
            for (String value :
                List.of(
                    "\nManifest-Version: 1.0\n\n",
                    "Manifest-Version: 1.0\nMulti-Release: true\nmulti-release: false\n\n",
                    "Manifest-Version: 1.0\n\nSealed: true\nName: demo/\n\n",
                    " continuation\n")) reject(() -> ManifestData.parse(text(value)));
          });
      test(
          "comparison catches classpath winner changes",
          () -> {
            var comparison = Scanner.compare(scan(first, second), scan(second, first));
            check(
                comparison.changed().size() == 1
                    && comparison.changed().getFirst().effectiveBytesChanged(),
                "winner diff missing");
          });
      test(
          "unrelated classpath order changes do not change class origins",
          () -> {
            Path different = jar(other, null);
            check(
                Scanner.compare(scan(first, different), scan(different, first)).changed().isEmpty(),
                "order created false class-origin change");
          });
      test(
          "comparison identifies added and removed class names",
          () -> {
            var comparison = Scanner.compare(scan(first), scan(jar(other, null)));
            check(
                comparison.added().equals(List.of("demo.Other"))
                    && comparison.removed().equals(List.of("demo.Version")),
                "wrong class delta");
          });
      test(
          "comparison detects ServiceLoader provider changes and order",
          () -> {
            Path before =
                jar(
                    Map.of(
                        "META-INF/services/svc.API",
                        text("svc.Good\nsvc.Missing\nsvc.Removed\n")),
                    null);
            Path after =
                jar(
                    Map.of(
                        "META-INF/services/svc.API",
                        text("svc.Missing\nsvc.Good\nsvc.Added\n")),
                    null);
            var comparison = Scanner.compare(scan(before), scan(after));
            check(
                comparison.serviceChanges()
                    .equals(
                        List.of(
                            new Scanner.ServiceChange(
                                "svc.API",
                                List.of("svc.Good", "svc.Missing", "svc.Removed"),
                                List.of("svc.Missing", "svc.Good", "svc.Added")))),
                "service provider delta or order change missing");
          });
      test(
          "comparison policies must agree",
          () ->
              reject(
                  () ->
                      Scanner.compare(
                          scan(first),
                          Scanner.scan(List.of(first), new Scanner.Options(17, false)))));
      test(
          "JSON encoding escapes text and is deterministic",
          () -> {
            String output = Json.encode(Map.of("z", "x\n\"\\\u0000😀", "a", List.of(1, true)));
            check(output.startsWith("{\"a\":"), "map order unstable");
            check(
                output.contains("\\n") && output.contains("\\u0000") && !output.contains("\u0000"),
                "unsafe JSON string");
          });
      test(
          "SARIF retains findings and fingerprints",
          () -> {
            String output = Json.encode(Outputs.sarif(scan(first, second)));
            check(
                output.contains("\"version\":\"2.1.0\"")
                    && output.contains("classpath/v1")
                    && output.contains("CP001"),
                "SARIF missing fields");
          });
      test(
          "JSON output agrees across separate CLI JVM processes",
          () -> {
            List<String> command =
                List.of(
                    javaBinary(),
                    "-cp",
                    System.getProperty("java.class.path"),
                    "dev.rudy.classpath.Main",
                    "scan",
                    "--fail-on",
                    "none",
                    first.toString(),
                    second.toString());
            check(
                Arrays.equals(process(command), process(command)), "cross-process output differs");
          });
      test(
          "CLI gates retain complete reports",
          () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream(),
                err = new ByteArrayOutputStream();
            int code =
                Main.run(
                    new String[] {"scan", first.toString(), second.toString()},
                    out,
                    new PrintStream(err));
            check(
                code == 1
                    && err.size() == 0
                    && out.toString(StandardCharsets.UTF_8).contains("CP001"),
                "gate broke report");
          });
      test(
          "CLI explanation resolves a class without running it",
          () -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            int code =
                Main.run(
                    new String[] {"explain", "demo.Version", first.toString()}, out, System.err);
            check(
                code == 0 && out.toString(StandardCharsets.UTF_8).contains("\"found\":true"),
                "explain failed");
          });
      test(
          "CLI rejects bad options without stdout",
          () -> {
            for (String[] input :
                new String[][] {
                  {"scan", "--target", "26", first.toString()},
                  {"scan", "--format", "xml", first.toString()},
                  {"scan", "--target", "21", "--target", "21", first.toString()},
                  {"scan", "--fail-on", "typo", first.toString()},
                  {"unknown"},
                  {"scan", "missing.jar"}
                }) {
              ByteArrayOutputStream out = new ByteArrayOutputStream(),
                  err = new ByteArrayOutputStream();
              check(
                  Main.run(input, out, new PrintStream(err)) == 2
                      && out.size() == 0
                      && err.size() > 0,
                  "bad CLI rejection");
            }
          });
      test(
          "CLI compare lists are relative to their files",
          () -> {
            Path lists = Files.createDirectory(temp.resolve("lists"));
            Files.copy(first, lists.resolve("a.jar"));
            Files.copy(second, lists.resolve("b.jar"));
            Files.writeString(lists.resolve("before"), "# explicit list\na.jar\nb.jar\n");
            Files.writeString(lists.resolve("after"), "b.jar\na.jar\n");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            check(
                Main.run(
                            new String[] {
                              "compare",
                              "--fail-on-change",
                              lists.resolve("before").toString(),
                              lists.resolve("after").toString()
                            },
                            out,
                            System.err)
                        == 1
                    && out.toString(StandardCharsets.UTF_8)
                        .contains("effectiveBytesChanged\":true"),
                "relative compare failed");
          });
      test(
          "CLI compare change gate includes ServiceLoader provider changes",
          () -> {
            Path lists = Files.createDirectory(temp.resolve("service-lists"));
            Path before =
                jar(Map.of("META-INF/services/svc.API", text("svc.Good\n")), null);
            Path after =
                jar(Map.of("META-INF/services/svc.API", text("svc.Added\n")), null);
            Files.copy(before, lists.resolve("before.jar"));
            Files.copy(after, lists.resolve("after.jar"));
            Files.writeString(lists.resolve("before"), "before.jar\n");
            Files.writeString(lists.resolve("after"), "after.jar\n");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            check(
                Main.run(
                            new String[] {
                              "compare",
                              "--fail-on-change",
                              lists.resolve("before").toString(),
                              lists.resolve("after").toString()
                            },
                            out,
                            System.err)
                        == 1
                    && out.toString(StandardCharsets.UTF_8).contains("serviceChanges"),
                "service provider change did not trip the CLI gate");
          });
      test(
          "output failures return exit 2",
          () -> {
            OutputStream broken =
                new OutputStream() {
                  public void write(int value) throws IOException {
                    throw new IOException("closed");
                  }
                };
            check(
                Main.run(
                        new String[] {"scan", first.toString()},
                        broken,
                        new PrintStream(new ByteArrayOutputStream()))
                    == 2,
                "output failure lost");
          });
      test(
          "warning gates include missing provider observations",
          () -> {
            var report =
                scan(jar(Map.of("META-INF/services/svc.API", text("svc.Missing\n")), null));
            check(
                !Scanner.gate(report.findings(), "error")
                    && Scanner.gate(report.findings(), "warning")
                    && !Scanner.gate(report.findings(), "none"),
                "wrong threshold");
          });
      test(
          "random malformed class bytes never escape documented rejection",
          () -> {
            Random random = new Random(8852);
            for (int n = 0; n < 2000; n++) {
              byte[] bytes = new byte[random.nextInt(1024)];
              random.nextBytes(bytes);
              reject(() -> ClassFile.parse(bytes));
            }
          });
      test(
          "seeded mutations exercise the real class structure reader",
          () -> {
            Random random = new Random(9931);
            byte[] seed = a.get("demo/Version.class");
            for (int n = 0; n < 3000; n++) {
              byte[] mutated = seed.clone();
              mutated[random.nextInt(mutated.length)] ^= (byte) (1 + random.nextInt(255));
              try {
                ClassFile.parse(mutated);
              } catch (IOException expected) {
                /* documented structural rejection */
              }
            }
          });
      System.out.println(passed + "/" + (passed + failed) + " tests passed.");
      if (failed != 0) throw new AssertionError("Regression suite failed.");
    } finally {
      try (var paths = Files.walk(temp)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
          if (!path.toAbsolutePath().normalize().startsWith(temp))
            throw new IOException("Cleanup escaped test directory.");
          Files.delete(path);
        }
      }
    }
  }

  private static String javaBinary() {
    return Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
        .toString();
  }

  private static byte[] process(List<String> command) throws Exception {
    Process process =
        new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT).start();
    byte[] data = process.getInputStream().readAllBytes();
    check(process.waitFor() == 0, "CLI subprocess failed");
    return data;
  }
}
