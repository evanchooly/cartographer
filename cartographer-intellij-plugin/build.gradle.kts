import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.intellij.platform") version "2.16.0"
    kotlin("jvm") version "2.3.20"
}

group = "com.antwerkz"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2025.3")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Bundled)
    }
    // Maven 4 model loading. maven-xml provides DefaultXmlService + its SPI registration,
    // which is loaded via the thread context classloader trick to bypass IntelliJ's classloader.
    implementation("org.apache.maven:maven-support:4.0.0-rc-5")
    implementation("org.apache.maven:maven-xml:4.0.0-rc-5")
    implementation("org.apache.maven:maven-api-xml:4.0.0-rc-5")
    implementation("org.apache.maven:maven-api-model:4.0.0-rc-5")
    testImplementation(kotlin("test-junit"))
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(17)
}

intellijPlatform {
    buildSearchableOptions = false
}
