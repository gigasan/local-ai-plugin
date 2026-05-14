import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val targetVersion = providers.gradleProperty("targetVersion")
    .getOrElse("2026.1")   // версия по умолчанию

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
        marketplace()        // разрешает поиск сторонних плагинов
    }
}

dependencies {
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("org.jsoup:jsoup:1.18.1")
    implementation("io.ktor:ktor-client-core:3.0.1")
    implementation("io.ktor:ktor-client-cio:3.0.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.0.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.1")
    implementation("com.aallam.openai:openai-client:4.1.0")
    implementation("com.anthropic:anthropic-java-client-okhttp:2.27.0") // Verify latest version
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3") // Kotlin Coroutines for async API calls
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("com.vladsch.flexmark:flexmark-all:0.64.8")
    implementation("org.xerial:sqlite-jdbc:3.45.2.0")
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.2.1.202505142326-r")
    implementation("com.knuddels:jtokkit:1.1.0")
    intellijPlatform {
        intellijIdea(targetVersion)
        bundledPlugin("com.intellij.java")
        bundledPlugin("org.intellij.plugins.markdown")
        bundledPlugin("org.jetbrains.kotlin")
        plugin("com.jetbrains.rust", "261.22158.214")
    }
}

intellijPlatform {
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "253"      // с 2025.3.2 и новее
            untilBuild = "261.*"    // до 2026.1 включительно
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// Убираем ненужные предупреждения при сборке
tasks.buildSearchableOptions {
    enabled = false
}

// Задача сборки
tasks {
    buildPlugin {
        // архив будет build\distributions\local-ai-plugin.zip
    }
    runIde {
        // Internal Mode для песочницы
        systemProperty("idea.is.internal", "true")

        // Можно выделить больше памяти для тестов
        jvmArgs("-Xmx2G")

        // Это поможет скрыть часть логов про Jakarta/Spring
        jvmArgs("-Didea.load.plugins.category=false")
    }
}
val compileKotlin: KotlinCompile by tasks
compileKotlin.compilerOptions {
    freeCompilerArgs.set(listOf("-Xannotation-default-target=param-property"))
}