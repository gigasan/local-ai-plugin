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
    intellijPlatform {
        rustRover("2025.3.2")
        bundledPlugin("org.intellij.plugins.markdown")
    }
}

intellijPlatform {
    pluginConfiguration {
        name = "Local AI Plugin"
        version = "0.1.0"
    }
}

kotlin {
    jvmToolchain(21)
}

// Убираем ненужные предупреждения при сборке
tasks.buildSearchableOptions {
    enabled = false
}

