plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("io.ktor:ktor-client-core:2.3.7")
    implementation("io.ktor:ktor-client-cio:2.3.7")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.7")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.7")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.vladsch.flexmark:flexmark-all:0.64.8")
    intellijPlatform {
        //rustRover("2025.3.2")
        intellijIdea("2026.1")
        bundledPlugin("org.intellij.plugins.markdown")
        bundledPlugin("JavaScript")
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Local AI Plugin"
        version = "0.2.0"
    }
}

kotlin {
    jvmToolchain(21)
}

// Убираем ненужные предупреждения при сборке
tasks.buildSearchableOptions {
    enabled = false
}

