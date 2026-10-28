plugins {
    kotlin("jvm") version "2.4.20"
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20"
    id("org.jetbrains.compose") version "1.10.0"
}

group = "dev.sakana.spw"
version = "0.6.12"

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



/**
 * Portable app image: `gradlew createDistributable` writes build/compose/binaries/main/app/.
 * jpackage needs no WiX for this format, so it works on a clean machine. The BASS natives are
 * copied next to the executable afterwards (the app looks in ./native first).
 */
compose.desktop {
    application {
        mainClass = "dev.sakana.spw.prism.app.StandaloneAppKt"
        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Msi,
            )
            packageName = "SakanaPrism"
            packageVersion = project.version.toString()
            description = "Sakana Prism - spectrum and sound field visualiser"
            vendor = "Sakana"
            // Keep the runtime lean; the analyser needs no crypto or JDBC modules.
            modules("java.instrument", "java.management", "jdk.unsupported")
            windows {
                iconFile.set(project.file("assets/icon.ico"))
                menu = true
                shortcut = true
            }
        }
    }
}

/** Copies the BASS natives beside the packaged executable. */
tasks.register<Copy>("copyNativeLibs") {
    from(layout.projectDirectory.dir("sdk/native"))
    into(layout.buildDirectory.dir("compose/binaries/main/app/SakanaPrism/native"))
}

// configureEach, because the Compose plugin registers its packaging tasks after this file
// is evaluated.
tasks.matching { it.name == "createDistributable" }.configureEach {
    finalizedBy("copyNativeLibs")
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

