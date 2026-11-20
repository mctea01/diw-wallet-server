import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.graalvm.native)
    application
}

group = "diw.server"
/*
 * One version for every output, by the wallet's rule. version.properties holds the release line (major.minor); the
 * patch is the number of commits since the commit that last changed that line. A pull request build is marked
 * -pr<N>, a build outside CI -dev. tools/app-version.sh applies the same rule for the CI file names and checks a v*
 * tag against it. -Pdiw.version overrides (for a source tree without git history).
 */
val releaseLine: String = layout.projectDirectory.file("version.properties").asFile.readLines()
    .firstNotNullOfOrNull { Regex("""\s*version\s*=\s*(\d+\.\d+)\s*""").matchEntire(it)?.groupValues?.get(1) }
    ?: throw GradleException("version.properties needs a line version=<major>.<minor>")
fun git(vararg args: String): String? = try {
    val run = providers.exec {
        commandLine("git", *args)
        workingDir = rootDir
        isIgnoreExitValue = true
    }
    if (run.result.get().exitValue == 0) run.standardOutput.asText.get().trim().ifEmpty { null } else null
} catch (e: Exception) {
    null
}
val inCi: Boolean = providers.environmentVariable("GITHUB_ACTIONS").orNull == "true"
val gitRef: String? = providers.environmentVariable("GITHUB_REF").orNull
val pullRequest: String? = gitRef?.let { Regex("refs/pull/(\\d+)/.*").matchEntire(it)?.groupValues?.get(1) }
/** On a release tag v<line>.<patch> of this line, the tag names the patch (tools/app-version.sh checks it). */
val tagPatch: Int? = gitRef?.let { Regex("refs/tags/v${Regex.escape(releaseLine)}\\.(\\d+)").matchEntire(it) }
    ?.groupValues?.get(1)?.toIntOrNull()
val patch: Int by lazy {
    val counted = if (git("rev-parse", "--is-shallow-repository") != "false") null
    else git("log", "-G^version=", "-1", "--format=%H", "--", "version.properties")
        ?.let { base -> git("rev-list", "--count", "$base..HEAD") }?.toIntOrNull()
    if (counted != null && tagPatch != null && counted != tagPatch) {
        throw GradleException("Tag ${gitRef?.removePrefix("refs/tags/")} does not match the version $releaseLine.$counted of this commit")
    }
    // A shallow checkout cannot count commits: a release tag still names the right version, other builds use 0.
    if (counted == null && tagPatch == null) logger.warn("Version: no full git history, patch 0 (check out with fetch-depth: 0)")
    counted ?: tagPatch ?: 0
}
version = (findProperty("diw.version") as String?)
    ?: ("$releaseLine.$patch" + when { !inCi -> "-dev"; pullRequest != null -> "-pr$pullRequest"; else -> "" })

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
/**
 * The x86 ISA levels the GNU property note of an ELF64 executable asks the loader for (bit 0 baseline, then v2, v3,
 * v4); 0 when it has none. glibc refuses to start the file on a processor below that ("CPU ISA level is lower than
 * required").
 */
fun x86IsaNeeded(file: File): Int = RandomAccessFile(file, "r").use { f ->
    fun read(at: Long, size: Int) = ByteBuffer.wrap(ByteArray(size).also { f.seek(at); f.readFully(it) })
        .order(ByteOrder.LITTLE_ENDIAN)
    val header = read(0, 64)
    require(header.getInt(0) == 0x464c457f && header.get(4) == 2.toByte()) { "not an ELF64 file: $file" }
    val phoff = header.getLong(32)
    val phentsize = header.getShort(54).toInt()
    var needed = 0
    for (i in 0 until header.getShort(56).toInt()) {
        val segment = read(phoff + i.toLong() * phentsize, phentsize)
        if (segment.getInt(0) != 0x6474e553) continue // PT_GNU_PROPERTY
        val size = segment.getLong(32).toInt()
        val notes = read(segment.getLong(8), size)
        var at = 0
        while (at + 12 <= size) {
            val descSize = notes.getInt(at + 4)
            val desc = at + 12 + ((notes.getInt(at) + 3) and 3.inv())
            if (notes.getInt(at + 8) == 5) { // NT_GNU_PROPERTY_TYPE_0
                var property = desc
                while (property + 8 <= desc + descSize) {
                    val dataSize = notes.getInt(property + 4)
                    if (notes.getInt(property) == 0xc0008002.toInt()) needed = needed or notes.getInt(property + 8)
                    property += 8 + ((dataSize + 7) and 7.inv())
                }
            }
            at = desc + ((descSize + 7) and 7.inv())
        }
    }
    needed
}

// native-image targets "compatibility", but the C start files it links come from the build host, and those of
// Ubuntu's amd64v3 variant (GitHub's ubuntu-26.04 runners) mark the executable x86-64-v3, so older processors cannot
// start it. Build the Linux x86_64 executable on a baseline host (ubuntu-24.04); this check fails the build otherwise.
val checkNativeIsa by tasks.registering {
    description = "Fails when the Linux x86_64 native executable asks for more than the baseline x86-64 ISA level."
    val image = layout.buildDirectory.file("native/nativeCompile/diw-server")
    onlyIf { bundlesRuntime && image.get().asFile.isFile }
    doLast {
        val needed = x86IsaNeeded(image.get().asFile)
        val levels = listOf("x86-64-baseline", "x86-64-v2", "x86-64-v3", "x86-64-v4").filterIndexed { bit, _ ->
            needed and (1 shl bit) != 0
        }
        check(needed and 1.inv() == 0) {
            "native executable requires ${levels.joinToString(", ")}: build it on a host whose C runtime is baseline"
        }
        logger.lifecycle("native executable ISA level: ${levels.ifEmpty { listOf("unmarked") }.joinToString(", ")}")
    }
}
tasks.matching { it.name == "nativeCompile" }.configureEach { finalizedBy(checkNativeIsa) }

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
