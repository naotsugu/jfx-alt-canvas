import org.gradle.nativeplatform.platform.internal.DefaultNativePlatform
val os   = DefaultNativePlatform.getCurrentOperatingSystem()
val arch = DefaultNativePlatform.getCurrentArchitecture()

// rust
val cargo = file(System.getProperty("user.home") + "/.cargo/bin/cargo")
val rustPrjDir = layout.projectDirectory.dir("src/main/rust").asFile
val rustTgtDir = rustPrjDir.resolve("target")
val rustSrcDir = rustPrjDir.resolve("src")
val cbindhFile = rustTgtDir.resolve("lib.h")

val nativeResDir = layout.buildDirectory.dir("nativeResources").get().asFile

// jextract
val jextractUrl = "https://download.java.net/java/early_access/jextract/25/2/"
val jextractOutDir = layout.buildDirectory.dir("generated/main/java").get().asFile

version = "0.1.0"
group = "com.mammb"

plugins {
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}
javafx {
    version = "25"
    modules("javafx.controls")
}

application {
    mainClass = "com.mammb.code.jfx.canvas.example.Main"
    applicationDefaultJvmArgs = listOf(
        "--enable-native-access=ALL-UNNAMED",
        "--enable-native-access=javafx.graphics")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.register<Exec>("cargoBuild") {
    description = "Builds the Rust library using Cargo"
    group = "rust"
    workingDir = rustPrjDir
    inputs.dir(rustSrcDir).withPropertyName("rustSrcDir")
    inputs.files(rustPrjDir.resolve("Cargo.toml")).withPropertyName("cargoToml")
    outputs.dir(rustTgtDir).withPropertyName("rustTargetDir")
    commandLine = listOf(cargo.absolutePath, "build", "--release")
}

tasks.register<Copy>("processNativeResources") {
    dependsOn("cargoBuild")
    description = "Copy native resources"
    from(when {
        os.isMacOsX  -> rustTgtDir.resolve("release/liblib.dylib")
        os.isLinux   -> rustTgtDir.resolve("release/liblib.so")
        os.isWindows -> rustTgtDir.resolve("release/lib.dll")
        else -> throw Error("Unsupported OS: $os")
    })
    into(nativeResDir)
    rename("(.+)\\.(.+)", "$1-$version.$2")
}

tasks.register<Exec>("cargoClean") {
    description = "Cleans the Rust build"
    group = "rust"
    workingDir = rustPrjDir
    commandLine = listOf(cargo.absolutePath, "clean")
}

tasks.register<Copy>("downloadJextract") {
    description = "Downloads the jextract binaries"
    group = "jextract"
    val url = when {
        os.isMacOsX  && arch.isArm64 -> "$jextractUrl/openjdk-25-jextract+2-4_macos-aarch64_bin.tar.gz"
        os.isMacOsX  && arch.isAmd64 -> "$jextractUrl/openjdk-25-jextract+2-4_macos-x64_bin.tar.gz"
        os.isLinux   && arch.isArm64 -> "$jextractUrl/openjdk-25-jextract+2-4_linux-aarch64_bin.tar.gz"
        os.isLinux   && arch.isAmd64 -> "$jextractUrl/openjdk-25-jextract+2-4_linux-x64_bin.tar.gz"
        os.isWindows && arch.isAmd64 -> "$jextractUrl/openjdk-25-jextract+2-4_windows-x64_bin.tar.gz"
        else -> throw Error("Unsupported OS: $os, ARCH: $arch")
    }
    val tgz = layout.buildDirectory.file("jextract.tar.gz")
    tgz.get().asFile.apply {
        if (!exists()) {
            parentFile.mkdirs()
            uri(url).toURL().openStream().use { input ->
                outputStream().use { out -> input.copyTo(out) }
            }
        }
    }
    from(tarTree(resources.gzip(tgz.get())))
    into(layout.buildDirectory.dir("jextract"))
}

tasks.register<Exec>("jextract") {
    dependsOn("downloadJextract", "cargoBuild")
    group = "jextract"
    description = "Generates Java bindings from C header using jextract"

    inputs.files(cbindhFile)
    outputs.dir(jextractOutDir)

    val jextract = layout.buildDirectory.dir("jextract/jextract-25/bin/jextract")
        .get().asFile.absolutePath + if (os.isWindows) ".bat" else ""

    commandLine(jextract,
        cbindhFile.absolutePath,
        "--output", jextractOutDir.absolutePath,
        "--target-package", "com.mammb.code.canvas.lib",
        "--library", "lib",
    )


    val outDir = jextractOutDir // capture the output dir at configuration time (configuration cache friendly)
    doLast {
        val original = "static final SymbolLookup SYMBOL_LOOKUP = " +
                "SymbolLookup.libraryLookup(System.mapLibraryName(\"lib\"), LIBRARY_ARENA)"

        val replacement = "static final SymbolLookup SYMBOL_LOOKUP = " +
                "SymbolLookup.libraryLookup(" +
                "java.nio.file.Path.of(System.getProperty(\"com.mammb.nativeLibraryPath\"))" +
                ".resolve(System.mapLibraryName(\"lib-$version\")), LIBRARY_ARENA)"

        val target = outDir.walkTopDown().firstOrNull { it.name == "lib_h_1.java" || it.name == "lib_h.java" }
            ?: throw GradleException("lib_h_1.java not found under $outDir")

        val text = target.readText()
        if (!text.contains(original)) {
            throw GradleException("SYMBOL_LOOKUP line not found in ${target.name}; jextract output may have changed")
        }
        target.writeText(text.replace(original, replacement))
    }

}

tasks.named("processResources") {
    dependsOn("processNativeResources")
}

tasks.named("compileJava") {
    dependsOn("jextract")
    dependsOn(updateVersion)
}

val updateVersion = tasks.register("updateVersion") {
    description = "Updates the version in Version.java"

    val target = layout.projectDirectory
        .file("src/main/java/com/mammb/code/jfx/canvas/Version.java")
    val versionString = providers.provider { project.version.toString() }
    inputs.property("version", versionString)
    outputs.file(target)

    doLast {
        val file = target.asFile
        if (!file.exists()) throw GradleException("${file.name} not found at ${file.absolutePath}")

        val content = file.readText()
        // Match only the version constant definition
        val regex = Regex("""(String\s+val\s*=\s*")[^"]*(")""")
        if (!regex.containsMatchIn(content)) {
            throw GradleException("Version val definition not found in ${file.name}")
        }

        val updated = content.replace(regex) {
            "${it.groupValues[1]}${versionString.get()}${it.groupValues[2]}"
        }
        if (content != updated) file.writeText(updated)
    }
}

sourceSets.main {
    java.srcDir(jextractOutDir)
    resources.srcDir(nativeResDir)
}

//tasks.named<JavaExec>("run") {
//    val rustLibDir = rustTgtDir.resolve("release")
//    if (os.isMacOsX) {
//        environment("DYLD_LIBRARY_PATH", rustLibDir)
//    } else if (os.isWindows) {
//        environment("PATH", rustLibDir)
//    } else {
//        environment("LD_LIBRARY_PATH", rustLibDir)
//    }
//}