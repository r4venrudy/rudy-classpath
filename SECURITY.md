# Security boundaries

Rudy Classpath reads local archives supplied for review. It never defines or
executes their classes, follows manifest URLs, performs network requests, loads
native libraries, or extracts entries into filesystem paths. Tests intentionally
load only their own generated synthetic classes for independent behavior checks.

The tool copies compressed inputs to temporary files before reading ZIP metadata.
These snapshots may contain private application code; OS temporary permissions
apply, and interrupted processes may leave copies behind. Do not use a shared or
untrusted temporary directory. Reports may include internal names and manifest
URLs. Review before publishing them. Never attach private/customer JARs or real
credentials to public issues.

Bounds, strict metadata parsing, CRC checks and output escaping are part of the
input-handling contract. Structural checks do not replace JVM bytecode/linkage
verification, code-signing validation, a dependency-vulnerability database, or a
complete production classloader model.

Use private GitHub vulnerability reporting when available, or contact the
maintainer through [Rudy Systems](https://rudy.systems). Provide a minimal synthetic
reproducer, Java version and exact options. Avoid public disclosure of private
archive contents or confidential class names.
