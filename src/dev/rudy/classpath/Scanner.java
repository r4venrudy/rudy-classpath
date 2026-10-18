package dev.rudy.classpath;

import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** Offline model for an ordered list of ordinary classpath JARs. */
public final class Scanner {
  public static final int MAX_ARCHIVES = 32,
      MAX_ENTRIES = 50000,
      MAX_PROVIDERS = 10000,
      MAX_ENTRY = 4 << 20;
  public static final long MAX_ARCHIVE = 64L << 20,
      MAX_COMPRESSED = 256L << 20,
      MAX_EXPANDED = 128L << 20;

  public record Options(int targetJava, boolean preview) {
    public Options {
      if (targetJava < 8 || targetJava > 25)
        throw new IllegalArgumentException("Target Java must be from 8 through 25.");
    }
  }

  public record Archive(
      String id,
      String filename,
      String sha256,
      boolean multiRelease,
      String manifestClassPath,
      int entries,
      int ignoredVersionedEntries,
      boolean moduleDescriptor) {}

  public record Provider(
      String archive,
      String archiveSha256,
      int order,
      String entry,
      int release,
      String sha256,
      ClassFile.Info info) {}

  public record Resolution(String name, Provider firstProvided, List<Provider> providers) {}

  public record Service(String name, List<String> providers, List<String> descriptors) {}

  public record Finding(
      String rule, String level, String subject, List<String> archives, String message) {}

  public record Report(
      String schema,
      String version,
      int targetJava,
      boolean preview,
      List<Archive> archives,
      List<Resolution> classes,
      List<Service> services,
      List<Finding> findings,
      List<String> notices) {}

  public record Change(
      String name, boolean effectiveBytesChanged, List<Provider> before, List<Provider> after) {}

  public record ServiceChange(
      String name, List<String> beforeProviders, List<String> afterProviders) {}

  public record Comparison(
      String schema,
      int targetJava,
      boolean preview,
      List<String> added,
      List<String> removed,
      List<Change> changed,
      List<ServiceChange> serviceChanges,
      List<Finding> beforeFindings,
      List<Finding> afterFindings,
      List<String> notices) {}

  private record Entry(String physical, String logical, int release, byte[] data) {}

  private static final List<String> NOTICES =
      List.of(
          "Models only the supplied ordered JARs. Parent/platform loaders, directories, custom"
              + " loaders, modules and nested archives are not resolved.",
          "Manifest Class-Path URLs are reported but never followed. Such manifests make the"
              + " explicit-list prediction incomplete.",
          "No class is defined or executed. Structural parsing is not bytecode verification,"
              + " linkage analysis, signature verification or a vulnerability verdict.",
          "Service providers absent from this list may be supplied by an external loader."
              + " Constructor checks do not establish successful initialization.");

  private static final class Budget {
    long compressed, expanded;
    int entries, providers, serviceProviders;
  }

  private static IOException invalid(String message) {
    return new InputError(message);
  }

  public static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private static boolean binaryName(String name) {
    if (name.isEmpty()) return false;
    for (String part : name.split("\\.", -1)) {
      if (part.isEmpty() || !Character.isJavaIdentifierStart(part.codePointAt(0))) return false;
      for (int offset = Character.charCount(part.codePointAt(0)); offset < part.length(); ) {
        int c = part.codePointAt(offset);
        if (!Character.isJavaIdentifierPart(c)) return false;
        offset += Character.charCount(c);
      }
    }
    return true;
  }

  private static void entryName(String name) throws IOException {
    if (name.isEmpty()
        || name.startsWith("/")
        || name.indexOf('\\') >= 0
        || name.indexOf(':') >= 0
        || name.chars().anyMatch(Character::isISOControl))
      throw invalid("Unsafe or unsupported archive entry name.");
    String clean = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
    for (String part : clean.split("/", -1))
      if (part.isEmpty() || part.equals(".") || part.equals(".."))
        throw invalid("Noncanonical archive entry name.");
  }

  private static byte[] readEntry(ZipFile zip, ZipEntry entry, Budget budget) throws IOException {
    if (entry.getSize() > MAX_ENTRY) throw invalid("Archive entry exceeds 4 MiB.");
    byte[] data;
    try (InputStream in = zip.getInputStream(entry)) {
      data = in.readNBytes(MAX_ENTRY + 1);
    }
    if (data.length > MAX_ENTRY || (entry.getSize() >= 0 && data.length != entry.getSize()))
      throw invalid("Entry exceeds size limit or has inconsistent length.");
    budget.expanded += data.length;
    if (budget.expanded > MAX_EXPANDED) throw invalid("Expanded inventory exceeds 128 MiB.");
    CRC32 crc = new CRC32();
    crc.update(data);
    if (crc.getValue() != entry.getCrc()) throw invalid("Archive entry CRC does not match.");
    return data;
  }

  private static Entry effective(String name, byte[] data, boolean multi, int target)
      throws IOException {
    if (name.startsWith("META-INF/versions/")) {
      if (!multi) return null;
      String rest = name.substring(18);
      int slash = rest.indexOf('/');
      if (slash < 0) return null;
      String number = rest.substring(0, slash);
      if (!number.matches("[1-9][0-9]*")) return null;
      int version;
      try {
        version = Integer.parseInt(number);
      } catch (NumberFormatException e) {
        return null;
      }
      if (version < 9 || version > target) return null;
      String logical = rest.substring(slash + 1);
      if (logical.startsWith("META-INF/")) return null;
      return new Entry(name, logical, version, data);
    }
    return new Entry(name, name, 0, data);
  }

  public static Report scan(List<Path> files, Options options) throws IOException {
    if (files.isEmpty() || files.size() > MAX_ARCHIVES)
      throw invalid("Supply from 1 to 32 JAR files.");
    Budget budget = new Budget();
    Set<Path> canonical = new HashSet<>();
    List<Archive> archives = new ArrayList<>();
    Map<String, List<Provider>> definitions = new TreeMap<>();
    Map<String, LinkedHashSet<String>> services = new TreeMap<>();
    Map<String, List<String>> serviceOrigins = new TreeMap<>();
    Map<String, ManifestData> manifests = new HashMap<>();
    List<Finding> findings = new ArrayList<>();
    for (int index = 0; index < files.size(); index++) {
      Path source = files.get(index);
      if (!Files.isRegularFile(source)) throw invalid("JAR input must be a regular file.");
      if (!canonical.add(source.toRealPath()))
        throw invalid("Repeated canonical JAR path; remove duplicate input aliases.");
      String id = "jar-" + String.format(Locale.ROOT, "%03d", index);
      Path snapshot = Files.createTempFile("rudy-classpath-", ".jar");
      try {
        MessageDigest digest;
        try {
          digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
          throw new AssertionError(e);
        }
        long bytes = 0;
        try (InputStream in = Files.newInputStream(source);
            OutputStream out = Files.newOutputStream(snapshot)) {
          byte[] buffer = new byte[65536];
          int n;
          while ((n = in.read(buffer)) != -1) {
            bytes += n;
            budget.compressed += n;
            if (bytes > MAX_ARCHIVE || budget.compressed > MAX_COMPRESSED)
              throw invalid("Compressed archive inventory exceeds byte limits.");
            digest.update(buffer, 0, n);
            out.write(buffer, 0, n);
          }
        }
        String hash = HexFormat.of().formatHex(digest.digest());
        try (ZipFile zip = new ZipFile(snapshot.toFile())) {
          if (zip.size() > MAX_ENTRIES - budget.entries)
            throw invalid("Inventory exceeds 50000 entries.");
          List<? extends ZipEntry> entries = Collections.list(zip.entries());
          if (entries.size() > MAX_ENTRIES - budget.entries)
            throw invalid("Inventory exceeds 50000 entries.");
          budget.entries += entries.size();
          Set<String> names = new HashSet<>();
          ZipEntry manifestEntry = null;
          for (ZipEntry entry : entries) {
            entryName(entry.getName());
            if (!names.add(entry.getName())) throw invalid("Duplicate archive entry name.");
            if (entry.getName().equalsIgnoreCase("META-INF/MANIFEST.MF")) {
              if (manifestEntry != null) throw invalid("Ambiguous manifest names.");
              manifestEntry = entry;
            }
          }
          ManifestData manifest =
              manifestEntry == null
                  ? ManifestData.empty()
                  : ManifestData.parse(readEntry(zip, manifestEntry, budget));
          manifests.put(id, manifest);
          String declared = manifest.main().getOrDefault("class-path", "");
          if (!declared.isBlank())
            findings.add(
                new Finding(
                    "CP008",
                    "error",
                    id,
                    List.of(id),
                    "Manifest Class-Path is not expanded; explicit-list resolution is"
                        + " incomplete."));
          Map<String, Entry> selected = new TreeMap<>();
          int ignored = 0;
          // Reading every entry enforces expansion limits and CRCs, including unused resources.
          for (ZipEntry entry : entries) {
            if (entry == manifestEntry) continue;
            byte[] data = readEntry(zip, entry, budget);
            if (entry.isDirectory()) continue;
            Entry candidate =
                effective(entry.getName(), data, manifest.multiRelease(), options.targetJava());
            if (candidate == null) {
              if (entry.getName().startsWith("META-INF/versions/")) ignored++;
              continue;
            }
            boolean relevant =
                candidate.logical().endsWith(".class")
                        && !candidate.logical().startsWith("META-INF/")
                    || candidate.logical().startsWith("META-INF/services/");
            if (!relevant) continue;
            if (candidate.logical().startsWith("META-INF/services/") && data.length > 65536)
              throw invalid("Service descriptor exceeds 64 KiB.");
            Entry previous = selected.get(candidate.logical());
            if (previous == null || candidate.release() > previous.release())
              selected.put(candidate.logical(), candidate);
          }
          boolean module = selected.containsKey("module-info.class");
          for (Entry entry : selected.values()) {
            String logical = entry.logical();
            if (logical.equals("module-info.class")) continue;
            if (logical.startsWith("META-INF/services/")) {
              String service = logical.substring(18);
              if (!binaryName(service)) throw invalid("Invalid service descriptor name.");
              if (!services.containsKey(service) && services.size() >= 1000)
                throw invalid("Inventory exceeds 1000 service interfaces.");
              LinkedHashSet<String> providers =
                  services.computeIfAbsent(service, k -> new LinkedHashSet<>());
              serviceOrigins.computeIfAbsent(service, k -> new ArrayList<>()).add(id);
              for (String line : ManifestData.utf8(entry.data()).split("\\r\\n|\\r|\\n", -1)) {
                int comment = line.indexOf('#');
                String provider = (comment < 0 ? line : line.substring(0, comment)).trim();
                if (provider.isEmpty()) continue;
                if (!binaryName(provider)) throw invalid("Invalid service provider binary name.");
                if (providers.add(provider) && ++budget.serviceProviders > 10000)
                  throw invalid("Inventory exceeds 10000 service-provider declarations.");
              }
              continue;
            }
            if (++budget.providers > MAX_PROVIDERS)
              throw invalid("Inventory exceeds 10000 effective class providers.");
            String name = logical.substring(0, logical.length() - 6).replace('/', '.');
            ClassFile.Info info = ClassFile.parse(entry.data());
            Provider provider =
                new Provider(
                    id, hash, index, entry.physical(), entry.release(), sha(entry.data()), info);
            definitions.computeIfAbsent(name, k -> new ArrayList<>()).add(provider);
            if (!name.equals(info.name()))
              findings.add(
                  new Finding(
                      "CP005",
                      "error",
                      name,
                      List.of(id),
                      "Class declaration does not match its effective archive path."));
            if (info.major() > options.targetJava() + 44
                || info.major() >= 56
                    && info.minor() == 65535
                    && (!options.preview() || info.major() != options.targetJava() + 44))
              findings.add(
                  new Finding(
                      "CP003",
                      "error",
                      name,
                      List.of(id),
                      "Class-file version or preview status is incompatible with the selected"
                          + " target."));
            if (entry.release() > 0 && info.major() > entry.release() + 44)
              findings.add(
                  new Finding(
                      "CP010",
                      "error",
                      name,
                      List.of(id),
                      "Versioned class exceeds the Java version of its multi-release directory."));
            if (name.startsWith("java."))
              findings.add(
                  new Finding(
                      "CP009",
                      "error",
                      name,
                      List.of(id),
                      "Application class declares a reserved java.* package."));
          }
          archives.add(
              new Archive(
                  id,
                  source.getFileName().toString(),
                  hash,
                  manifest.multiRelease(),
                  declared,
                  entries.size(),
                  ignored,
                  module));
        }
      } finally {
        Files.deleteIfExists(snapshot);
      }
    }
    List<Resolution> resolutions = new ArrayList<>();
    Map<String, Set<String>> packages = new TreeMap<>();
    for (var entry : definitions.entrySet()) {
      String name = entry.getKey();
      List<Provider> providers = entry.getValue();
      resolutions.add(new Resolution(name, providers.getFirst(), List.copyOf(providers)));
      if (providers.size() > 1) {
        boolean same = providers.stream().map(Provider::sha256).distinct().count() == 1;
        findings.add(
            new Finding(
                same ? "CP002" : "CP001",
                same ? "note" : "error",
                name,
                providers.stream().map(Provider::archive).toList(),
                same
                    ? "Identical class bytes appear in multiple JARs."
                    : "Different class bytes compete for the same binary name; the first provided"
                        + " JAR wins in the explicit-list model."));
      }
      int dot = name.lastIndexOf('.');
      if (dot > 0)
        for (Provider p : providers)
          packages.computeIfAbsent(name.substring(0, dot), k -> new TreeSet<>()).add(p.archive());
    }
    for (var entry : packages.entrySet())
      if (entry.getValue().size() > 1
          && entry.getValue().stream().anyMatch(id -> manifests.get(id).sealed(entry.getKey())))
        findings.add(
            new Finding(
                "CP004",
                "error",
                entry.getKey(),
                List.copyOf(entry.getValue()),
                "Package spans multiple JARs and at least one declares it sealed; loading across"
                    + " origins can fail."));
    List<Service> serviceReports = new ArrayList<>();
    for (var entry : services.entrySet()) {
      String service = entry.getKey();
      List<String> origins = List.copyOf(serviceOrigins.get(service));
      serviceReports.add(new Service(service, List.copyOf(entry.getValue()), origins));
      for (String provider : entry.getValue()) {
        List<Provider> definitionsForProvider = definitions.get(provider);
        if (definitionsForProvider == null) {
          findings.add(
              new Finding(
                  "CP006",
                  "warning",
                  provider,
                  origins,
                  "Declared service provider is absent from the supplied class inventory; external"
                      + " loaders may supply it."));
          continue;
        }
        ClassFile.Info info = definitionsForProvider.getFirst().info();
        if ((info.access() & 1) == 0
            || (info.access() & 0x600) != 0
            || !info.publicNoArgConstructor())
          findings.add(
              new Finding(
                  "CP007",
                  "error",
                  provider,
                  origins,
                  "Classpath service provider lacks a public concrete class with a public"
                      + " no-argument constructor."));
      }
    }
    findings.sort(
        Comparator.comparing(Finding::rule)
            .thenComparing(Finding::subject)
            .thenComparing(f -> String.join(",", f.archives())));
    return new Report(
        "rudy-classpath/scan/v1",
        Main.VERSION,
        options.targetJava(),
        options.preview(),
        List.copyOf(archives),
        List.copyOf(resolutions),
        List.copyOf(serviceReports),
        List.copyOf(findings),
        NOTICES);
  }

  public static boolean gate(List<Finding> findings, String threshold) {
    if (!Set.of("error", "warning", "none").contains(threshold))
      throw new IllegalArgumentException("Threshold must be error, warning or none.");
    return findings.stream()
        .anyMatch(
            f ->
                !threshold.equals("none")
                    && (f.level().equals("error")
                        || threshold.equals("warning") && f.level().equals("warning")));
  }

  private static List<String> identities(List<Provider> providers) {
    return providers.stream()
        .map(p -> p.archiveSha256() + "/" + p.entry() + "/" + p.sha256())
        .toList();
  }

  public static Comparison compare(Report before, Report after) {
    if (before.targetJava() != after.targetJava() || before.preview() != after.preview())
      throw new IllegalArgumentException("Comparison requires the same target policy.");
    Map<String, Resolution> a = new TreeMap<>(), b = new TreeMap<>();
    for (var r : before.classes()) a.put(r.name(), r);
    for (var r : after.classes()) b.put(r.name(), r);
    Set<String> names = new TreeSet<>(a.keySet());
    names.addAll(b.keySet());
    List<String> added = new ArrayList<>(), removed = new ArrayList<>();
    List<Change> changed = new ArrayList<>();
    for (String name : names) {
      if (!a.containsKey(name)) added.add(name);
      else if (!b.containsKey(name)) removed.add(name);
      else if (!identities(a.get(name).providers()).equals(identities(b.get(name).providers())))
        changed.add(
            new Change(
                name,
                !a.get(name).firstProvided().sha256().equals(b.get(name).firstProvided().sha256()),
                a.get(name).providers(),
                b.get(name).providers()));
    }
    Map<String, List<String>> beforeServices = serviceProviders(before.services());
    Map<String, List<String>> afterServices = serviceProviders(after.services());
    Set<String> serviceNames = new TreeSet<>(beforeServices.keySet());
    serviceNames.addAll(afterServices.keySet());
    List<ServiceChange> serviceChanges = new ArrayList<>();
    for (String name : serviceNames) {
      List<String> beforeProviders = beforeServices.getOrDefault(name, List.of());
      List<String> afterProviders = afterServices.getOrDefault(name, List.of());
      if (!beforeProviders.equals(afterProviders))
        serviceChanges.add(new ServiceChange(name, beforeProviders, afterProviders));
    }
    return new Comparison(
        "rudy-classpath/compare/v2",
        before.targetJava(),
        before.preview(),
        added,
        removed,
        changed,
        serviceChanges,
        before.findings(),
        after.findings(),
        List.of(
            "Compares effective class-provider inventories and merged ServiceLoader provider"
                + " order; ordinary resources and module graphs are not compared.",
            "Archive SHA-256 is origin identity: repacking a JAR can change provenance even if"
                + " class bytes are unchanged."));
  }

  private static Map<String, List<String>> serviceProviders(List<Service> services) {
    Map<String, List<String>> providers = new TreeMap<>();
    for (Service service : services) providers.put(service.name(), service.providers());
    return providers;
  }
}
