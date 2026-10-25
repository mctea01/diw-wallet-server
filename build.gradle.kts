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
    runtimeOnly(libs.slf4j.simple)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)
}

tasks.jar {
    manifest { attributes("Implementation-Version" to project.version) }
}

tasks.test {
    useJUnitPlatform()
    jvmArgs("-Dfile.encoding=UTF-8")
    testLogging { events("passed", "failed", "skipped") }
}
