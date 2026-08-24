plugins {
    `java-library`
    `maven-publish`
    signing
}

group = "com.tesseractsoftwares"
version = "1.0.0"

// Java 17, not 21. Paper 1.20.x runs on 17 and 1.21 on 21, so 17 reaches both - and Minecraft
// plugin developers are the audience that asked for a Java SDK rather than using the Kotlin or
// .NET one. Anything newer would exclude half of them for no gain.
java {
    withSourcesJar()
    withJavadocJar()
}

// `release` rather than a toolchain, deliberately. A toolchain makes Gradle hunt for or download a
// JDK 17, which fails on a machine that only has 21 and adds a download to CI. `--release 17`
// compiles on whatever JDK is present while checking against the Java 17 API surface, so a Java
// 21-only method is a compile error here rather than a NoSuchMethodError on a 1.20 Paper server.
tasks.withType<JavaCompile>().configureEach {
    options.release = 17
    // -serial: every exception inherits Serializable from Throwable, so the lint fires on
    // all of them. These are never serialised, and chasing it would mean pinning a
    // serialVersionUID on classes whose wire form nobody depends on.
    options.compilerArgs.addAll(listOf("-Xlint:all,-serial", "-Werror"))
}

repositories { mavenCentral() }

// Deliberately empty for `implementation` and `api`. Java has no JSON in the standard library, so
// this SDK bundles a minimal codec rather than depending on Gson or Jackson.
//
// That is not dogma. A Paper/Spigot plugin lives inside a server classloader that already contains
// its own Gson, and version-skewed copies of a shaded library are one of the classic ways a plugin
// breaks a server it did not ship with. Zero dependencies removes the question entirely, and it
// keeps the promise the other five SDKs already make.
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
    }
}

tasks.jar {
    manifest {
        attributes(
            "Implementation-Title" to "Praxsuite SDK for Java",
            "Implementation-Version" to project.version,
            "Automatic-Module-Name" to "com.tesseractsoftwares.praxsuite",
        )
    }
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            artifactId = "praxsuite-sdk"
            pom {
                name = "Praxsuite SDK for Java"
                description = "Auth, queries and server-authoritative endpoints for Praxsuite. " +
                    "Zero dependencies, Java 17+."
                url = "https://praxsuite.com"
                licenses {
                    license {
                        name = "Praxsuite Open SDK Licence v1.0"
                        url = "https://github.com/TesseractSoftwares/Praxsuite-SDK-Java/blob/master/LICENSE"
                        distribution = "repo"
                    }
                }
                developers {
                    developer {
                        name = "Tesseract Softwares SpA"
                        url = "https://praxsuite.com"
                    }
                }
                scm {
                    url = "https://github.com/TesseractSoftwares/Praxsuite-SDK-Java"
                    connection = "scm:git:https://github.com/TesseractSoftwares/Praxsuite-SDK-Java.git"
                }
            }
        }
    }

    // Both modules stage into one directory under the root build, so a single bundle carries both
    // artifacts. Central validates and releases a bundle as a unit, and that is what we want here:
    // praxsuite-sdk-kotlin depends on praxsuite-sdk, so releasing them separately would publish a
    // Kotlin artifact whose own dependency is not resolvable yet.
    //
    // Publishing to a `file://` repository is also what produces the layout Central expects -
    // `com/tesseractsoftwares/praxsuite-sdk/1.0.0/...` with checksums - so the bundle is a plain zip
    // of this directory rather than anything assembled by hand.
    repositories {
        maven {
            name = "centralStaging"
            url = uri(rootProject.layout.buildDirectory.dir("central-staging"))
        }
    }
}

// Maven Central rejects unsigned artifacts outright. There is no unsigned path, unlike npm, PyPI and
// NuGet, so a signing key has to exist in CI.
//
// The key arrives as an armoured string in the environment and is used in memory: the agent never
// writes a secret to disk and there is no keyring to clean up afterwards. Signing is required only
// when a key is actually present, so `./gradlew test` on a machine with no key still works rather
// than failing on a task nobody asked for.
signing {
    val signingKey: String? = System.getenv("PRAX_SIGNING_KEY")
    isRequired = !signingKey.isNullOrBlank()
    if (isRequired) {
        useInMemoryPgpKeys(signingKey, System.getenv("PRAX_SIGNING_PASSWORD"))
        sign(publishing.publications)
    }
}

// Zips the staged repository into the single bundle the Central Portal's Publisher API accepts.
// Depends on both modules' publish tasks, so `./gradlew centralBundle` from the root is the whole
// build-side of a release.
tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Packages the staged Maven repository into a Central Portal upload bundle."

    dependsOn(
        ":publishMavenJavaPublicationToCentralStagingRepository",
        ":praxsuite-sdk-kotlin:publishMavenKotlinPublicationToCentralStagingRepository",
    )

    from(layout.buildDirectory.dir("central-staging"))
    // Gradle writes maven-metadata.xml into a file:// repository. Central rejects a bundle
    // containing it, because metadata is the repository's to generate, not the publisher's.
    exclude("**/maven-metadata.xml*")

    destinationDirectory = layout.buildDirectory.dir("central-bundle")
    archiveFileName = "praxsuite-sdk-${project.version}-bundle.zip"
}
