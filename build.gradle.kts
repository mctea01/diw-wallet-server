plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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
    implementation(libs.ktor.server.netty)
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
}

distributions {
    main {
        contents {
            from("README.md", "LICENSE")
            from("deploy") {
                into("deploy")
                exclude("cloudflare/node_modules/**", "cloudflare/.wrangler/**", "cloudflare/package-lock.json")
            }
        }
    }
}

// A release bundle carries a jlink runtime in APP_HOME/runtime; the start script uses it unless JAVA_HOME is set.
tasks.named<CreateStartScripts>("startScripts") {
    doLast {
        val script = unixScript
        val text = script.readText()
        val line = Regex("""(?m)^APP_HOME=\$\( cd -P .*\n""").find(text) ?: error("Start script layout changed: APP_HOME line not found")
        val bundled = "\n# A release bundle carries its own runtime; an explicit JAVA_HOME wins.\n" +
            "if [ -z \"\${JAVA_HOME:-}\" ] && [ -x \"\${APP_HOME}/runtime/bin/java\" ]; then JAVA_HOME=\${APP_HOME}/runtime; fi\n"
        script.writeText(text.substring(0, line.range.last + 1) + bundled + text.substring(line.range.last + 1))
    }
}

// Modules from jdeps --print-module-deps over the distribution, plus what is loaded by name at runtime (EC and
// legacy charsets for TLS and documents, DNS for the JDK resolver, zipfs, locale data for zh and en).
val runtimeModules = listOf(
    "java.base", "java.compiler", "java.desktop", "java.instrument", "java.management", "java.naming", "java.net.http",
    "java.sql", "jdk.jfr", "jdk.unsupported", "jdk.crypto.ec", "jdk.charsets", "jdk.localedata", "jdk.naming.dns",
    "jdk.zipfs",
)

val runtimeImage by tasks.registering(Exec::class) {
    description = "Builds a trimmed Java runtime for the release bundle with jlink."
    val output = layout.buildDirectory.dir("runtime")
    val jlink = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        .map { it.metadata.installationPath.file("bin/jlink").asFile.path }
    inputs.property("modules", runtimeModules)
    outputs.dir(output)
    doFirst {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch")
        // The native proof library and the bundle name are linux-x86_64 only.
        require(os.startsWith("linux") && (arch == "amd64" || arch == "x86_64")) { "The release bundle is built on linux-x86_64 only" }
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

val bundleTar by tasks.registering(Tar::class) {
    description = "Packs the distribution with its jlink runtime: diw-server-<version>-linux-x86_64.tar.gz."
    group = "distribution"
    val name = "diw-server-${project.version}"
    archiveFileName.set("$name-linux-x86_64.tar.gz")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    compression = Compression.GZIP
    into(name) {
        with(distributions["main"].contents)
        from(runtimeImage) { into("runtime") }
    }
}

tasks.jar {
    manifest { attributes("Implementation-Version" to project.version) }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Dfile.encoding=UTF-8")
    testLogging { events("passed", "failed", "skipped") }
}
