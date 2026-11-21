plugins {
    kotlin("jvm") version "2.2.20"
    id("com.vanniktech.maven.publish") version "0.34.0"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
}

// Built for Java 11 with whichever JDK (11 or newer) runs Gradle.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        freeCompilerArgs.add("-Xjdk-release=11")
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

tasks.test {
    useJUnitPlatform()
    testLogging { events("passed", "failed", "skipped") }
}

// Publishing to Maven Central needs the Central Portal token and a signing key in ~/.gradle/gradle.properties;
// see README. Nothing here holds a credential.
mavenPublishing {
    publishToMavenCentral()
    if (providers.gradleProperty("signingInMemoryKey").isPresent || providers.gradleProperty("signing.keyId").isPresent) signAllPublications()
}
