import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.graalvm.native)
    application
}

group = "diw.server"
version = providers.fileContents(layout.projectDirectory.file("version.txt")).asText.get().trim()

kotlin {
    jvmToolchain(21)
    compilerOptions { optIn.add("kotlin.time.ExperimentalTime") }
}

application {
    mainClass.set("diw.server.MainKt")
    applicationName = "diw-server"
    // Sized for a 1 GB host; override with JAVA_OPTS.
    applicationDefaultJvmArgs = listOf("-Xmx256m", "-XX:+UseSerialGC", "-XX:MaxMetaspaceSize=128m", "-Dfile.encoding=UTF-8")
}

dependencies {
    // Substituted by the included wallet build (group "diw-wallet", project ":core").
    implementation("diw-wallet:core")
    implementation(libs.coroutines.core)
    implementation(libs.serialization.json)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.forwarded.header)
    implementation(libs.ktor.client.java)
    implementation(libs.zxing.core)
    runtimeOnly(libs.slf4j.simple)
    testImplementation(kotlin("test"))
    // The wallet's test fixtures: the local HTTPS target and the synthetic record page zkTLS proves.
    testImplementation("diw-wallet:test-servers")
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
    // A self-signed holder certificate for the mobile citizen certificate stand-in.
    testImplementation(libs.bcpkix)
}

// Modules from jdeps --print-module-deps over the distribution, plus what is loaded by name at runtime (EC and
// legacy charsets for TLS and documents, DNS for the JDK resolver, zipfs, locale data for zh and en).
val runtimeModules = listOf(
    "java.base", "java.compiler", "java.desktop", "java.instrument", "java.management", "java.naming", "java.net.http",
    "java.sql", "jdk.jfr", "jdk.unsupported", "jdk.crypto.ec", "jdk.charsets", "jdk.localedata", "jdk.naming.dns",
    "jdk.zipfs",
)

// The native proof library is linux-x86_64 only, so only that host bundles a runtime; elsewhere the distribution
// runs on the JAVA_HOME or PATH Java as before.
val bundlesRuntime = System.getProperty("os.name").lowercase().startsWith("linux") &&
    System.getProperty("os.arch") in setOf("amd64", "x86_64")

val runtimeImage by tasks.registering(Exec::class) {
    description = "Builds a trimmed Java runtime with jlink for the distribution's runtime/ directory."
    val output = layout.buildDirectory.dir("runtime")
    val jlink = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        .map { it.metadata.installationPath.file("bin/jlink").asFile.path }
    onlyIf { bundlesRuntime }
    inputs.property("modules", runtimeModules)
    outputs.dir(output)
    doFirst {
        delete(output)
        executable = jlink.get()
    }
    args(
        "--add-modules", runtimeModules.joinToString(","),
        "--include-locales=en,zh",
        "--strip-debug", "--no-header-files", "--no-man-pages", "--compress=zip-6", "--generate-cds-archive",
        "--output", output.get().asFile.path,
    )
}

distributions {
    main {
        contents {
            from("README.md", "LICENSE")
            from("deploy") {
                into("deploy")
                exclude("cloudflare/node_modules/**", "cloudflare/.wrangler/**", "cloudflare/package-lock.json")
            }
            // installDist, distTar and the image all carry it, so no build step can leave it out.
            from(runtimeImage) { into("runtime") }
        }
    }
}

// The start script uses APP_HOME/runtime when there is one, unless JAVA_HOME is set.
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        val script = unixScript
        val text = script.readText()
        val line = Regex("""(?m)^APP_HOME=\$\( cd -P .*\n""").find(text) ?: error("Start script layout changed: APP_HOME line not found")
        val bundled = "\n# The distribution carries its own runtime; an explicit JAVA_HOME wins.\n" +
            "if [ -z \"\${JAVA_HOME:-}\" ] && [ -x \"\${APP_HOME}/runtime/bin/java\" ]; then JAVA_HOME=\${APP_HOME}/runtime; fi\n"
        script.writeText(text.substring(0, line.range.last + 1) + bundled + text.substring(line.range.last + 1))
    }
}

tasks.jar {
    manifest { attributes("Implementation-Version" to project.version) }
}

// Service files of every dependency merged into one per service, so the single jar keeps Ktor's engines and the
// logger providers that a plain copy would drop.
val mergedServices by tasks.registering {
    val output = layout.buildDirectory.dir("uber-services")
    val jars = configurations.runtimeClasspath.map { c -> c.filter { it.name.endsWith(".jar") } }
    inputs.files(jars)
    outputs.dir(output)
    doLast {
        val dir = output.get().asFile.apply { deleteRecursively() }
        val merged = sortedMapOf<String, LinkedHashSet<String>>()
        jars.get().forEach { jar ->
            ZipFile(jar).use { zip ->
                zip.entries().asSequence().filter { !it.isDirectory && it.name.startsWith("META-INF/services/") }.forEach { entry ->
                    zip.getInputStream(entry).bufferedReader().readLines().map { it.substringBefore('#').trim() }
                        .filter { it.isNotEmpty() }.forEach { merged.getOrPut(entry.name) { linkedSetOf() }.add(it) }
                }
            }
        }
        merged.forEach { (name, lines) -> File(dir, name).apply { parentFile.mkdirs() }.writeText(lines.joinToString("\n", postfix = "\n")) }
    }
}

// One jar for every desktop system (java -jar diw-server-<version>-all.jar prover), Java 21 or later. It carries
// the native proof libraries the build finds under wallet/native/dist/resources; the prover workflow puts every
// platform there first.
val uberJar by tasks.registering(Jar::class) {
    description = "Builds a single runnable jar with every dependency."
    archiveBaseName.set("diw-server")
    archiveClassifier.set("all")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest {
        attributes("Main-Class" to "diw.server.MainKt", "Implementation-Version" to project.version, "Multi-Release" to "true")
    }
    // The merged service files come first, so they win over each jar's own copy.
    from(mergedServices)
    from(sourceSets.main.map { it.output })
    from(configurations.runtimeClasspath.map { c -> c.filter { it.name.endsWith(".jar") }.map { zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA", "META-INF/*.EC", "META-INF/INDEX.LIST", "module-info.class")
}

tasks.test {
    useJUnitPlatform()
    // -Pdiw.nativeServer=build/native/nativeCompile/diw-server runs the zkTLS end-to-end test against the native image.
    (findProperty("diw.nativeServer") as String?)?.let { systemProperty("diw.nativeServer", file(it).absolutePath) }
    jvmArgs("-Dfile.encoding=UTF-8")
    testLogging { events("passed", "failed", "skipped") }
}

// GraalVM native image (experimental; the JVM distribution stays the default). `./gradlew nativeCompile` with
// GRAALVM_HOME set writes build/native/nativeCompile/diw-server. The reachability metadata under
// src/main/resources/META-INF/native-image comes from the tracing agent over the tests (`./gradlew -Pagent test
// metadataCopy`); refresh it when dependencies change. The native proof library is loaded through JNA as on the JVM.
graalvmNative {
    toolchainDetection.set(false)
    metadataRepository { enabled.set(true) }
    binaries.named("main") {
        imageName.set("diw-server")
        mainClass.set("diw.server.MainKt")
        buildArgs.addAll(
            "--no-fallback",
            "-march=compatibility",
            "-H:+ReportExceptionStackTraces",
            "--enable-url-protocols=http,https",
            "-Dfile.encoding=UTF-8",
            "-H:IncludeLocales=en,zh",
        )
        resources.autodetect()
    }
    agent {
        defaultMode.set("standard")
        metadataCopy {
            inputTaskNames.add("test")
            outputDirectories.add("src/main/resources/META-INF/native-image/diw.server/diw-server")
            mergeWithExisting.set(true)
        }
    }
}
