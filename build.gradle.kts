import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val targetVersion = providers.gradleProperty("targetVersion")
    .getOrElse("2026.1")

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("org.jetbrains.intellij.platform") version "2.14.0"
    id("com.google.devtools.ksp") version "2.3.6"
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
        jetbrainsRuntime()
        marketplace()
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.vladsch.flexmark:flexmark-all:0.64.8")
    implementation("com.knuddels:jtokkit:1.1.0")
    intellijPlatform {
        intellijIdea(targetVersion)
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.intellij.plugins.markdown")
    }
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"      // с 2025.3.2 и новее
            untilBuild = "262.*"    // до 2026.2 включительно
        }
    }
}

kotlin {
    jvmToolchain(21)
}

tasks {
    buildPlugin {
        // build\distributions\local-ai-plugin.zip
    }
    runIde {
        // Internal Mode for sandbox
        systemProperty("idea.is.internal", "true")

        jvmArgs("-Xmx2G")
        jvmArgs("-Dide.browser.jcef.out-of-process.enabled=false")
        jvmArgs("-Dide.browser.jcef.gpu.disable=false")
        jvmArgs("-Dide.browser.jcef.enabled=true")
        jvmArgs("-Dide.browser.jcef.command.line.args=--ignore-gpu-blocklist --enable-gpu-rasterization --plugin-policy=everywhere")
        jvmArgs("-Didea.diagnostic.opentelemetry.metrics.file=")
        jvmArgs("-Didea.diagnostic.opentelemetry.meters.file.json=")
    }
}
val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.set(listOf("-Xannotation-default-target=param-property"))
}