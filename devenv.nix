{ pkgs, inputs, ... }:
let
  # seqlock/build.gradle.kts's multiRelease { targetVersions(8, 11) } (the
  # me.champeau.mrjar plugin) needs both a Java 8 and a Java 11 toolchain --
  # the base source set plus the java11 override/java11Test source sets.
  # gradle-wrapper.nix's isolatedHomeHook disables Gradle's own toolchain
  # auto-detect/auto-download, so all of these have to be registered
  # explicitly via extraJdkHomes below rather than relying on scanning.
  jdk8 = pkgs.jdk8;
  jdk11 = pkgs.jdk11;

  # One of the extra LTS runtimes build.gradle.kts's java17/21/25Test tasks
  # exercise the java11 override classes against (see there) -- otherwise
  # unrelated to running Gradle itself, unlike jdk25 below.
  jdk17 = pkgs.jdk17;
  jdk21 = pkgs.jdk21;

  # Runs the Gradle 9 wrapper/daemon itself (Java Compatibility table:
  # https://docs.gradle.org/current/userguide/compatibility.html -- Java 25
  # supported for running Gradle since 9.1.0, we're on 9.7.1), independent of
  # whatever JDK version(s) the project's own toolchains target. Also one of
  # the extra LTS runtimes build.gradle.kts's java17/21/25Test tasks exercise
  # the java11 override classes against.
  jdk25 = pkgs.jdk25;

  # On Linux a JDK package's .home is already the toolchain-detectable home;
  # on Darwin, nixpkgs' JDK outputs are a symlink farm and the real home is
  # nested at ${pkg.bundle}/Contents/Home (per nix-gradle-wrapper's own
  # README -- passing the top-level .home there lists the same JDK twice).
  jdkHome = pkg: if pkg ? bundle then "${pkg.bundle}/Contents/Home" else pkg.home;

  gradleWrapper = import "${inputs.nix-gradle-wrapper}/gradle-wrapper.nix" {
    inherit pkgs;
    wrapperPropertiesFile = ./gradle/wrapper/gradle-wrapper.properties;
    name = "seqlock";
    # This is a tiny single-module library (no per-task -Xmx override in
    # seqlock/build.gradle.kts), so the 4 GiB default per-worker assumption
    # is a big overestimate -- it collapses org.gradle.workers.max to 1 on
    # an 8 GiB machine. 512 MiB is generous for this project's tests/compile.
    perWorkerMemBytes = 512 * 1024 * 1024;
    extraJdkHomes = [ (jdkHome jdk8) (jdkHome jdk11) (jdkHome jdk17) (jdkHome jdk21) (jdkHome jdk25) ];
    # Pins the daemon JVM explicitly (org.gradle.java.home) instead of
    # relying on languages.java.jdk.package below having put jdk25 first on
    # JAVA_HOME/PATH by the time ./gradlew starts the daemon.
    javaHome = jdkHome jdk25;
  };
in
{
  languages.java.enable = true;
  languages.java.jdk.package = jdk25;

  packages = gradleWrapper.extraBuildInputs ++ [ jdk8 jdk11 jdk17 jdk21 ];

  enterShell = gradleWrapper.isolatedHomeHook + gradleWrapper.warmupHook + ''
    echo "seqlock dev shell ready"
  '';
}
