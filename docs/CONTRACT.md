# Classpath contract · v1

## Model

Inputs are a stable ordered list of ordinary JARs, a target Java version from 8
through 25, and explicit preview opt-in. Repeat canonical paths are rejected;
identical bytes stored at distinct paths remain distinct provided origins.

The tool snapshots each compressed input into a private temporary file before
opening its ZIP central directory. It deletes that temporary copy on completion
or ordinary failure. It never extracts archive entries to paths or defines target
classes. Source files should not change during the bounded snapshot copy: this
is not a filesystem-wide atomic snapshot. Interrupted processes can leave their
temporary compressed copies behind; operating-system temporary storage policy
and permissions apply.

Every entry, including unused resources, is streamed within bounds and checked
against central-directory size/CRC metadata. Entry names with absolute paths,
backslashes, colons, control characters, dot segments or empty path segments are
rejected. Duplicate names and ambiguous case variants of MANIFEST.MF are rejected.
ZIP CRCs detect inconsistent container data; they do not authenticate an archive.

Only effective class entries and base service metadata participate in analysis.
Multi-release selection requires an exact case-insensitive manifest value `true`;
the highest canonical decimal directory N ≥9 and N ≤ target wins. Directories
with leading zeroes, invalid/overflowing versions and versioned META-INF metadata
are ignored. Root `module-info.class` or a selected versioned descriptor is noted
but excluded from class conflicts and bytecode checks because the model is an
ordinary classpath, not a module path. Its internal structure is not analyzed.

Class headers, constant-pool lengths/tags, declared names, super/interface names,
member headers and attribute lengths are structurally read. References within all
constant-pool entries, method bytecodes, stack maps, access correctness and linkage
are not fully validated. A parsed class is not certified as JVM-loadable. Invalid
class bytes in non-selected version directories are not structurally inspected.
Legacy major versions 45–55 follow modern JVMS minor-version rules; additional
minor-version restrictions in historical JVM builds are outside this model.

## Bounds

| Resource | Limit |
| --- | ---: |
| Explicit JAR paths | 32 |
| Compressed bytes per JAR | 64 MiB |
| Combined compressed input | 256 MiB |
| Combined expanded entries | 128 MiB |
| Each expanded entry/class file | 4 MiB |
| Combined ZIP entry count | 50000 |
| Effective class providers | 10000 |
| Manifest bytes | 1 MiB |
| Manifest logical attribute length | 65535 Java characters |
| Service descriptor bytes | 64 KiB |
| Service interfaces | 1000 |
| Distinct service/provider declarations | 10000 |
| Each classpath-list file | 64 KiB |

Limit violations reject the entire report with exit 2. Analysis retains selected
class metadata and entry bytes while reviewing each archive; allow a few hundred
MiB of heap for inventories near the limits. These limits do not establish a hard
wall-clock guarantee. Snapshots are processed one archive at a time.

## Manifest and services

Manifest attributes support UTF-8 and continuation lines. A nonempty manifest
must start with Manifest-Version and end in a newline. Sections must begin with
Name. Duplicate attribute names within a section, malformed UTF-8 and ambiguous
headers are rejected. Repeated named sections merge with later values overriding
earlier ones. Package-specific Sealed overrides the main value, including false.

Nonempty manifest Class-Path is an error-level coverage finding. Its URLs are
reported and never expanded. Parent/platform loaders, directories, Java agents,
custom loaders, module resolution, nested/fat JARs and executable framework boot
layouts are not reconstructed. Ordinary resources are integrity-checked but their
runtime resolution is not modeled.

Service descriptors use UTF-8, CR/LF line endings, `#` comments and ordered unique
binary names. Multiple base descriptors merge in supplied JAR order. Missing
provider definitions are warnings because another loader may supply them. The
first provided provider class is checked for public/concrete flags and a public
no-argument constructor. Assignability, accessibility through enclosing types,
transitive dependencies, initialization and module-based provider factories are
not established. An apparently compatible provider can still fail at runtime.

## Rules

| ID | Level | Observation |
| --- | --- | --- |
| CP001 | error | Different effective class bytes compete for one name |
| CP002 | note | Identical effective class bytes occur in multiple JARs |
| CP003 | error | Class major version/preview status exceeds target policy |
| CP004 | error | A package spans origins and one participating JAR seals it |
| CP005 | error | Declared class name differs from its effective entry path |
| CP006 | warning | Service provider absent from the explicit class inventory |
| CP007 | error | First provider lacks inspected public/concrete/no-arg flags |
| CP008 | error | Manifest Class-Path remains unresolved |
| CP009 | error | Application class uses a reserved java.* name |
| CP010 | error | Selected versioned class exceeds its MR directory version |

All effective provided class definitions are checked, including shadowed copies.
Findings indicate policy/compatibility observations; they are not proof that a
particular class is demanded by an application, that an exception occurred in
production, or that a vulnerability is exploitable. Levels are not CVSS severity.

## Outputs and comparison

`rudy-classpath/scan/v1` carries the policy, ordered input digests and basenames,
effective class providers, first-provided choices, merged service metadata,
findings and coverage notices. Full input paths and temporary snapshot paths are
not included. SHA-256 identifies original archive bytes and effective class bytes.
Archives receive positional IDs `jar-000` through `jar-031`.

`rudy-classpath/explain/v1` returns the requested binary name, its providers and
class-specific findings. An absent name returns found=false with exit 0.

`rudy-classpath/compare/v1` reports added/removed names and changed ordered provider
identities. Identity includes archive SHA-256, selected physical entry and class
SHA-256. Filenames/positional IDs alone do not determine comparison equality.
Repacking a JAR changes origin identity even when class bytes remain unchanged;
effectiveBytesChanged distinguishes such provenance changes. Service metadata,
ordinary resources and module graphs are not separately compared. Both scan
finding arrays remain available; the optional change gate applies only to the
class-provider delta, not those scan findings.

JSON map keys are sorted, records are output-only, and reports have no timestamps.
SARIF 2.1.0 has rule/subject/origin fingerprints and coverage notices, with no
invented source locations. Fingerprints are scoped to archive byte identities,
not a universal issue identity across repacked libraries. Text summaries replace
terminal control/format characters in untrusted subjects. Reports can contain
internal class names and manifest URLs; review them before sharing.

The public `Scanner.scan`, `Scanner.compare`, `ClassFile.parse`, and `Main.run`
surfaces operate on this bounded model. No signing/trust claim follows from a JAR
or service descriptor being structurally readable.
