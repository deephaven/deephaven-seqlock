plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "deephaven-seqlock"
include("seqlock")
// Published artifactId, decoupled from the seqlock/ directory name above.
project(":seqlock").name = "deephaven-seqlock"
