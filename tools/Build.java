import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import javax.tools.*;

/** Standard-JDK build/test/package entry point; no external build plugins. */
class Build {
  static final Path ROOT = Path.of("").toAbsolutePath().normalize();

  static void compile(Path source, Path output, String classpath) throws Exception {
    Files.createDirectories(output);
    List<String> arguments =
        new ArrayList<>(
            List.of(
                "--release",
                "21",
                "-encoding",
                "UTF-8",
                "-Xlint:all",
                "-Werror",
                "-d",
                output.toString()));
    if (classpath != null) arguments.addAll(List.of("-classpath", classpath));
    try (var files = Files.walk(source)) {
      files
          .filter(p -> p.toString().endsWith(".java"))
          .sorted()
          .forEach(p -> arguments.add(p.toString()));
    }
    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    if (compiler == null) throw new IllegalStateException("A JDK is required to build.");
    if (compiler.run(null, System.out, System.err, arguments.toArray(String[]::new)) != 0)
      throw new IllegalStateException("Compilation failed.");
  }

  public static void main(String[] args) throws Exception {
    if (!Files.isRegularFile(ROOT.resolve("src/dev/rudy/classpath/Main.java")))
      throw new IllegalStateException("Run from the project root.");
    if (args.length != 1 || !Set.of("test", "package", "examples").contains(args[0]))
      throw new IllegalArgumentException("usage: java tools/Build.java test|package|examples");
    Path classes = ROOT.resolve("build/classes");
    compile(ROOT.resolve("src"), classes, null);
    if (args[0].equals("test") || args[0].equals("examples")) {
      Path tests = ROOT.resolve("build/test-classes");
      compile(ROOT.resolve("tests"), tests, classes.toString());
      String path = classes + File.pathSeparator + tests;
      String executable =
          Path.of(
                  System.getProperty("java.home"),
                  "bin",
                  System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
              .toString();
      List<String> command =
          new ArrayList<>(List.of(executable, "-ea", "-cp", path, "dev.rudy.classpath.Tests"));
      if (args[0].equals("examples")) command.add("--examples");
      int code = new ProcessBuilder(command).inheritIO().start().waitFor();
      if (code != 0) throw new IllegalStateException("Tests/examples failed.");
    } else {
      Path dist = ROOT.resolve("dist");
      Files.createDirectories(dist);
      Path destination = dist.resolve("rudy-classpath-0.1.0.jar");
      if (Files.exists(destination))
        throw new IllegalStateException(
            "Package already exists; use a clean checkout for release packaging.");
      Manifest manifest = new Manifest();
      manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
      manifest.getMainAttributes().putValue("Main-Class", "dev.rudy.classpath.Main");
      try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(destination), manifest);
          var files = Files.walk(classes)) {
        for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
          String name = classes.relativize(file).toString().replace(File.separatorChar, '/');
          JarEntry entry = new JarEntry(name);
          entry.setTime(0);
          jar.putNextEntry(entry);
          Files.copy(file, jar);
          jar.closeEntry();
        }
      }
      System.out.println("Built " + destination.getFileName());
    }
  }
}
