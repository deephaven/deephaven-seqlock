plugins {
    `java-library`
    alias(libs.plugins.spotless)
    alias(libs.plugins.mrjar)
    alias(libs.plugins.vanniktech.publish)
}

repositories {
    mavenCentral()
}

group = "io.deephaven.seqlock"
version = "0.1.0-SNAPSHOT"
description =
    "A writer-biased optimistic concurrency primitive for Java: a single writer thread mutates " +
    "shared state without ever blocking, and any number of reader threads read that state " +
    "without acquiring a lock, retrying only if a write happened to overlap their read."

dependencies {
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

multiRelease {
    targetVersions(8, 11)
}

// Beyond the java11 compile target above, also exercise the resulting
// META-INF/versions/11/... classes on newer LTS runtimes (17, 21, 25).
// There's no source-level reason to add these as further mrjar
// targetVersions -- a multi-release JAR resolves the highest available
// META-INF/versions/<N> directory that's <= the running JVM's version, so a
// JDK 17/21/25 runtime already picks up the existing java11 override with
// no new override source set needed. These tasks just clone java11Test's
// test classes/classpath onto additional JavaLaunchers to confirm that.
run {
    val java11TestSourceSet = sourceSets.getByName("java11Test")
    val sharedTestSourceSet = sourceSets.getByName("test")

    // Identical for all three tasks below -- only the JavaLauncher differs --
    // so computed once rather than per task.
    val extraTestClassesDirs =
        objects.fileCollection().from(java11TestSourceSet.output, sharedTestSourceSet.output)
    val extraTestClasspath =
        objects.fileCollection().from(
            // must put the MRJar first on classpath, same as java11Test
            tasks.named("jar"),
            java11TestSourceSet.output,
            sharedTestSourceSet.runtimeClasspath,
        )

    val checkTask = tasks.named("check")

    listOf(17, 21, 25).forEach { jdkVersion ->
        val javaVersionTestTask =
            tasks.register<Test>("java${jdkVersion}Test") {
                group = "verification"
                description = "Runs the java11 source set's tests on a Java $jdkVersion runtime."
                javaLauncher.convention(
                    javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(jdkVersion)) },
                )
                testClassesDirs = extraTestClassesDirs
                classpath = extraTestClasspath
            }
        checkTask.configure { dependsOn(javaVersionTestTask) }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// Ship the license text inside every jar (main, sources, javadoc) as META-INF/LICENSE.
tasks.withType<Jar>().configureEach {
    from(rootDir.resolve("LICENSE")) {
        into("META-INF")
    }
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to project.name,
            "Implementation-Version" to archiveVersion,
            // Reverse-DNS module name for the Java Platform Module System, used when this jar
            // sits on the module path without its own module-info.java -- avoids an unstable,
            // filename-derived automatic module name. Matches project.group, which already
            // matches the sole package (io.deephaven.seqlock) here.
            "Automatic-Module-Name" to "${project.group}",
        )
    }
}

spotless {
    java {
        googleJavaFormat()
        // Apache-2.0 appendix header; $YEAR is filled in when a file first gets the header.
        licenseHeaderFile(rootDir.resolve("gradle/license-header.txt"))
    }
    kotlinGradle {
        ktlint()
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    pom {
        licenses {
            license {
                // SPDX identifier, as the Maven POM reference recommends.
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
    }
}
