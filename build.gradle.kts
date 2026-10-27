plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.10.0"
    application
}

group = "dev.sakana.spw"
version = "0.4.27"

kotlin {
    jvmToolchain(25)
}

dependencies {
    compileOnly(files("sdk/spw-api.jar"))
    compileOnly(compose.desktop.currentOs)
    compileOnly(compose.material3)
    runtimeOnly(files("sdk/spw-api.jar"))
    runtimeOnly(compose.desktop.currentOs)
    runtimeOnly(compose.material3)
}

application {
    mainClass.set("dev.sakana.spw.prism.preview.PreviewMainKt")
}

tasks.jar {
    manifest {
        attributes(
            "Plugin-Id" to "dev.sakana.spw.prism",
            "Plugin-Class" to "dev.sakana.spw.prism.MainPlugin",
            "Plugin-Version" to project.version,
            "Plugin-Provider" to "Sakana",
            "Plugin-Name" to "Sakana Prism",
            "Plugin-Description" to "Sakana Prism - realtime spectrum and sound field",
            "Plugin-Has-Config" to "true",
            "Plugin-Open-Source-Url" to "https://github.com/abbbaigei/sakana-prism",
            "Plugin-Permissions" to "library-read,class-transform,key-bindings"
        )
    }
}

/**
 * Diagnostic runner on the plugin runtime classpath, e.g.
 * `gradle probe -PprobeMain=dev.sakana.spw.prism.tools.ArtworkProbe -PprobeArgs="C:\list.txt"`.
 * Separate arguments are separated by `|`.
 */
tasks.register<JavaExec>("probe") {
    group = "verification"
    description = "Runs a diagnostic main class with the plugin runtime classpath."
    mainClass.set(providers.gradleProperty("probeMain").getOrElse("dev.sakana.spw.prism.tools.ArtworkProbe"))
    classpath = sourceSets["main"].runtimeClasspath
    val extra = providers.gradleProperty("probeArgs").getOrElse("")
    if (extra.isNotBlank()) args(extra.split("|"))
    jvmArgs("-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
}

