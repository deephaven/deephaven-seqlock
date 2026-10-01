# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A single-module Gradle/Kotlin-DSL Java library, `io.deephaven.seqlock:deephaven-seqlock`,
implementing a writer-biased optimistic-concurrency `SeqLock` (single writer, many non-blocking
readers). The core type and its contract are documented in the class javadoc at
`seqlock/src/main/java/io/deephaven/seqlock/SeqLock.java` — read it before touching that file; the
memory-ordering comments (`(1)`, `(2)`, `(A)`, `(B)`, ...) tie specific fence placements to the
guarantees described in the javadoc, so any edit to `beginWrite`/`endWrite`/`beginRead`/`validate`
must preserve that fence structure. `README.md` restates the same writer/reader guarantees for library
users (shorter, example-driven, no implementation detail) — keep it in sync with `SeqLock.java`'s
javadoc if either changes.

The subproject's directory is still `seqlock/` (matching its Gradle project path, `:seqlock`) even
though the published artifactId is `deephaven-seqlock` — decoupled on purpose via
`project(":seqlock").name = "deephaven-seqlock"` in `settings.gradle.kts`, rather than renaming the
directory itself.

## Dev shell

This repo's dev environment is managed by `devenv.nix`/`devenv.yaml` (via `nix-gradle-wrapper`, a
non-flake input pinned in `devenv.lock`). Running Gradle inside it is recommended, not required:
`devenv shell -- ./gradlew build` (or `devenv shell` for an interactive shell first) supplies the
pinned Gradle distribution and every JDK toolchain the build needs — 8, 11, 17, 21, 25 (see
Architecture below) — with nothing else to install. A bare `./gradlew build` also works on a machine
that already has those JDKs, or can fetch them: outside the shell, Gradle's normal toolchain
auto-detect and the foojay resolver in `settings.gradle.kts` are active. They're disabled only
inside the isolated shell, in favor of the JDKs it registers explicitly.

## Commands

```bash
./gradlew build          # compile, run tests (JDK 8, 11, 17, 21, 25), apply spotless check
./gradlew test           # base (JDK 8) tests only (JUnit 5 via junit-platform)
./gradlew java11Test     # same test sources, run against the JDK 11 override classes
./gradlew java17Test     # \
./gradlew java21Test     #  } same java11 override classes/tests, run on that JDK's runtime
./gradlew java25Test     # /
./gradlew test --tests "io.deephaven.seqlock.SeqLockTest.beginRead"   # single test
./gradlew spotlessApply  # auto-format (googleJavaFormat for Java, ktlint for *.gradle.kts)
./gradlew spotlessCheck  # format check only
```

Spotless also enforces the Apache-2.0 header (`gradle/license-header.txt`) on every Java file;
`spotlessApply` adds it to new files, so run it before `build` rather than writing the header by
hand.

## Architecture

- This is a multi-release JAR (`me.champeau.mrjar`, `multiRelease { targetVersions(8, 11) }` in
  `seqlock/build.gradle.kts`): the base source set (`src/main/java`) targets Java 8, so
  `SeqLock.java` cannot use `VarHandle` or `Thread.onSpinWait()` directly there — both are Java 9+.
- Two small package-private shims abstract over that: `ThreadShim.onSpinWait()` and
  `VarHandleShim.{storeStoreFence,acquireFence}()`. There are **two implementations of each**:
  - `seqlock/src/main/java/...` — the Java 8 fallback (`sun.misc.Unsafe` reflection, `Thread.yield()`),
    compiled into the JAR's base (unversioned) entries.
  - `seqlock/src/main/java11/...` — the real Java 9+ APIs (`VarHandle`, `Thread.onSpinWait()`), compiled
    with a JDK 11 toolchain into `META-INF/versions/11/...` — a JVM 11+ picks these up automatically at
    runtime (`Multi-Release: true` in the manifest); JVM 8–10 fall back to the base entries. `SeqLock`
    itself lives only in the base source set and is shared by both.
  - `JavaVersionShim.sourceJavaVersion()` follows the same pattern (base returns `8`, the `java11`
    override returns `11`) purely to make the override mechanism directly testable — see
    `JavaVersionShimTest` below.
- The mrjar plugin generates `java11Test` from the above, which runs `src/test/java`'s shared tests
  *and* any `src/test/java11`-specific ones (there's one today: `JavaVersionShimTest`, overriding the
  shared version to assert `11` instead of `8`) against the JDK 11 override classes.
- `seqlock/build.gradle.kts` additionally registers `java17Test`/`java21Test`/`java25Test` by hand (not
  via `targetVersions`, which would create unwanted new override source sets) — they reuse
  `java11Test`'s already-compiled classes/classpath verbatim, just swapping the `JavaLauncher`. This
  works because a multi-release JAR resolves the highest `META-INF/versions/<N>` directory `<=` the
  running JVM's version, so JDK 17/21/25 already pick up the `java11` override with no new source set
  needed. `check`/`build` run `test` plus all four `javaNNTest` tasks.
- New Gradle plugins are declared in `gradle/libs.versions.toml` (`[versions]` + `[plugins]`) and
  referenced via `alias(libs.plugins.<name>)` in `build.gradle.kts` — not `id(...) version "..."`
  inline. Follow this for anything new.
- `devenv.nix` vendors the exact Gradle distribution `gradle/wrapper/gradle-wrapper.properties` pins
  (via `nix-gradle-wrapper`) and registers every JDK toolchain the build above needs — 8, 11, 17, 21,
  25 — via `extraJdkHomes`, since toolchain auto-detect/auto-download are disabled in that isolated
  shell (see "Dev shell"). The Gradle daemon itself runs on JDK 25 (`javaHome`), independent of which
  JDK(s) the project's own toolchains target.
- `SeqLock` state: a `volatile long sequence` (reader-visible) plus a writer-private plain `long
  writerSeq`. Even sequence = quiescent/readable; odd = write-in-progress. `assert` statements in
  `beginWrite`/`endWrite` enforce single-writer, non-reentrant use (run with `-ea` to have these
  checked; the test suite relies on them via `SeqLockTest.doubleBeginWrite`/`doubleEndWrite`).
- `RequestStatsExample`/`RequestStatsExampleTest` are a worked, concurrently-tested illustration of
  the "accumulate locally, publish as a batch" pattern for protecting several related fields at
  once (README's "Protecting a group of related fields") — test-scoped only, not part of the
  published API. If you change the pattern there, update the README section alongside it.
