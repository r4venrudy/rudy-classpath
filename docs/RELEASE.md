Rudy Classpath v0.1.0 — developer preview

Java classpath conflict and compatibility review: ordered class-provider choices,
multi-release JAR selection, bytecode-version/preview checks, package sealing,
ServiceLoader metadata, before/after comparisons, JSON/SARIF and CI gates.

Requires Java 21 or newer. The executable JAR has no external runtime dependencies.
The ZIP includes documentation and synthetic example JARs. Verify both downloads
against SHA256SUMS.txt.

CI verifies JDK 21 and 25 on Windows, Linux and macOS before publishing. This is
an explicit ordinary-JAR model; it does not execute target classes, reconstruct
custom loaders/modules, verify signatures or establish production exploitability.
