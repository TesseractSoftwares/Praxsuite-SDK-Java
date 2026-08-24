plugins {
    kotlin("jvm") version "2.1.10"
    `java-library`
    `maven-publish`
    signing
}

group = "com.tesseractsoftwares"
version = rootProject.version

kotlin {
    // Matches the Java artifact's floor, for the same reason: Paper 1.20.x runs on 17.
    jvmToolchain {
        // No toolchain hunt - compile against 17's API on whatever JDK is present.
    }
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Warnings are errors here too, matching the Java module.
        allWarningsAsErrors.set(true)
    }
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 17
}

repositories { mavenCentral() }

// Unlike the Java artifact, this one HAS dependencies, and it has to: kotlin-stdlib is unavoidable
// for Kotlin code, and coroutines is what makes the suspend API worth having at all. That is the
// whole trade - the Java artifact stays dependency-free for Minecraft plugin authors who cannot
// afford a classloader argument, and Kotlin users who already ship a stdlib get the nicer face.
//
// api() rather than implementation() for the Java SDK: a consumer of this artifact needs
// Praxsuite, Filters and PraxError on their compile classpath, because the extensions return them.
dependencies {
    api(project(":"))
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.1")

    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.1")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "skipped", "failed") }
}

publishing {
    publications {
        create<MavenPublication>("mavenKotlin") {
            from(components["java"])
            artifactId = "praxsuite-sdk-kotlin"
            pom {
                name = "Praxsuite SDK for Kotlin"
                description = "Coroutine and DSL extensions for the Praxsuite Java SDK."
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

    // The same staging directory as the Java module, under the ROOT build dir - see the root
    // build file for why both artifacts have to ship in one bundle.
    repositories {
        maven {
            name = "centralStaging"
            url = uri(rootProject.layout.buildDirectory.dir("central-staging"))
        }
    }
}

signing {
    val signingKey: String? = System.getenv("PRAX_SIGNING_KEY")
    isRequired = !signingKey.isNullOrBlank()
    if (isRequired) {
        useInMemoryPgpKeys(signingKey, System.getenv("PRAX_SIGNING_PASSWORD"))
        sign(publishing.publications)
    }
}
