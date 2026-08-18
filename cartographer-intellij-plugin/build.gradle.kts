import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("org.jetbrains.intellij.platform") version "2.16.0"
    kotlin("jvm") version "2.4.10"
    id("com.diffplug.spotless") version "6.25.0"
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

tasks.processResources {
    filesMatching("cartographer-plugin.properties") { expand("version" to project.version) }
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }

    signing {
        certificateChain = providers.environmentVariable("JETBRAINS_MARKETPLACE_CERTIFICATE_CHAIN")
        privateKey = providers.environmentVariable("JETBRAINS_MARKETPLACE_PRIVATE_KEY")
        password = providers.environmentVariable("JETBRAINS_MARKETPLACE_PRIVATE_KEY_PASSWORD")
    }

    publishing {
        token = providers.environmentVariable("JETBRAINS_MARKETPLACE_TOKEN")
    }
}

spotless {
    java {
        target("src/**/*.java")
        eclipse().configFile("config/eclipse-format.xml")
        importOrderFile("config/eclipse.importorder")
        removeUnusedImports()
    }
    kotlin {
        target("src/**/*.kt")
        ktfmt().kotlinlangStyle()
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    dependsOn("spotlessApply")
}
