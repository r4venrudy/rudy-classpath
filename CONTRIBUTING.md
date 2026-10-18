# Contributing

Build with JDK 21 or newer from the project root:

```sh
java tools/Build.java test
java tools/Build.java package
```

The compiler runs with all warnings as errors. Keep the analysis core free of
external dependencies and target-class loading. New behavior should include
synthetic regressions and, where feasible, an independent JDK oracle. Preserve
the explicit-list coverage notices and bounds; update the contract for schema,
policy or resource-limit changes. Never commit private application archives,
credentials or real customer metadata. Generated public examples are acceptable.
