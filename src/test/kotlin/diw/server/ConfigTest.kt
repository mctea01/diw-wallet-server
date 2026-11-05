package diw.server

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** One settings file for every deployment: the template, the parser and every deployment file agree. */
class ConfigTest {
    private val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }

    /** DIW_ names that are not settings: the platform, the settings file, compose's own and the build's. */
    private val notSettings = setOf(
        "DIW_PLATFORM", "DIW_CONFIG", "DIW_CONFIG_TEXT", "DIW_UPSTREAM", "DIW_VERSION", "DIW_MEMORY_LIMIT", "DIW_JAVA_OPTS",
        "DIW_SERVER_OPTS", "DIW_ZK_NATIVE", "DIW_ZK_FEATURES", "DIW_SERVER",
    )

    private fun deploymentFiles(): List<File> = listOf(
        File(root, "compose.yaml"), File(root, "Dockerfile"), File(root, "Dockerfile.native"), File(root, ".env.example"),
    ) + File(root, "deploy").walkTopDown().filter { it.isFile && "node_modules" !in it.path && !it.name.endsWith(".json") }.toList()

    @Test
    fun theCommittedTemplateIsTheGeneratedOne() {
        assertEquals(ConfigKeys.template(), File(root, "deploy/server.conf.example").readText(),
            "deploy/server.conf.example is stale: regenerate it with diw-server config-template")
    }

    @Test
    fun theTemplateListsEveryKeyAndParses() {
        val template = ConfigKeys.template()
        ConfigKeys.all.forEach { assertTrue("${it.env}=" in template, "${it.env} missing from the template") }
        val values = ServerConfig.parseFile(template, "template")
        assertEquals(setOf("public_url"), values.keys)
        ServerConfig.from(values)
    }

    @Test
    fun deploymentFilesNameOnlyKnownSettings() {
        val name = Regex("\\bDIW_[A-Z0-9_]+\\b")
        for (file in deploymentFiles()) {
            val unknown = name.findAll(file.readText()).map { it.value }.filter { it !in notSettings && ConfigKeys.all.none { k -> k.env == it } }.toSet()
            assertTrue(unknown.isEmpty(), "${file.relativeTo(root)} names unknown settings: $unknown")
        }
    }

    @Test
    fun everyImageAndUnitNamesItsPlatformAndNoServiceSetting() {
        val expected = mapOf(
            "Dockerfile" to Platform.CONTAINER, "Dockerfile.native" to Platform.CONTAINER, "compose.yaml" to Platform.COMPOSE,
            "deploy/cloudrun/service.yaml" to Platform.CLOUD_RUN, "deploy/cloudflare/src/index.ts" to Platform.CLOUDFLARE,
            "deploy/systemd/diw-server.service" to Platform.HOST,
        )
        for ((path, platform) in expected) {
            // The image build's one-off class data sharing run is not the image's settings.
            val text = File(root, path).readLines().filterNot { "cds" in it || "ArchiveClassesAtExit" in it }.joinToString("\n")
            assertTrue(Regex("DIW_PLATFORM\\W+(value\\W+)?${platform.id}\\b").containsMatchIn(text), "$path does not set DIW_PLATFORM to ${platform.id}")
            // Service settings belong in server.conf, never baked into an image or unit.
            val service = ConfigKeys.all.filter { !it.deployment }.map { it.env }
                .filter { Regex("\\b$it\\s*[:=]").containsMatchIn(text) && !Regex("^\\s*#.*\\b$it", RegexOption.MULTILINE).containsMatchIn(text) }
            assertTrue(service.isEmpty(), "$path sets service settings itself: $service")
        }
    }

    @Test
    fun theFileReadsLikeAnEnvFileOrTheOldForm() {
        val parsed = ServerConfig.parseFile(
            """
            # comment
            DIW_PUBLIC_URL=https://diw.example.org
            export DIW_FEATURES="relay,verifier"
            relay_max_mb=32
            DIW_VERIFIER_NAME='查驗 站'
            """.trimIndent(), "test",
        )
        val c = ServerConfig.from(parsed)
        assertEquals("https://diw.example.org", c.publicUrl)
        assertEquals(setOf("relay", "verifier"), c.features)
        assertEquals(32L shl 20, c.relay.maxBytes)
        assertEquals("查驗 站", c.verifierName)
        assertFailsWith<IllegalArgumentException> { ServerConfig.parseFile("DIW_PLATFORM=compose", "test") }
        assertFailsWith<IllegalArgumentException> { ServerConfig.parseFile("no equals sign", "test") }
    }

    @Test
    fun platformsChangeOnlyDefaultsAndTheDigestIgnoresThem() {
        val file = Files.createTempFile("server", ".conf").toFile()
        file.writeText("DIW_PUBLIC_URL=https://diw.example.org\nDIW_FEATURES=relay,bonds-age\nDIW_RELAY_MAX_ROOMS=10\n")
        val host = ServerConfig.load(mapOf("DIW_CONFIG" to file.path))
        val compose = ServerConfig.load(mapOf("DIW_CONFIG" to file.path, "DIW_PLATFORM" to "compose"))
        val cloudRun = ServerConfig.load(mapOf("DIW_CONFIG_TEXT" to file.readText(), "DIW_PLATFORM" to "cloudrun", "PORT" to "9000"))
        assertEquals("127.0.0.1", host.listenHost)
        assertEquals("0.0.0.0", compose.listenHost)
        assertTrue(compose.trustForwarded)
        assertEquals(9000, cloudRun.listenPort)
        assertEquals("/tmp/diw-server", cloudRun.dataDir.path)
        assertEquals(false, cloudRun.signingGenerate)
        // signing_generate is a service setting, so a stateless platform's default shows in the digest.
        assertEquals(host.settingsDigest(), compose.settingsDigest())
        // The same settings written another way (order, case, an explicit default) give the same digest.
        val other = ServerConfig.load(mapOf("DIW_CONFIG_TEXT" to "relay_max_rooms=10\nfeatures=bonds-age, relay\npublic_url=https://diw.example.org/\nrelay_max_mb=256\n"))
        assertEquals(host.settingsDigest(), other.settingsDigest())
        assertNotEquals(host.settingsDigest(), ServerConfig.load(mapOf("DIW_CONFIG" to file.path, "DIW_RELAY_MAX_ROOMS" to "11")).settingsDigest())
        assertEquals("environment", ServerConfig.load(mapOf("DIW_CONFIG" to file.path, "DIW_RELAY_MAX_ROOMS" to "11")).sources["relay_max_rooms"])
        assertEquals("platform compose", compose.sources["listen_host"])
        assertFailsWith<IllegalArgumentException> { ServerConfig.load(mapOf("DIW_CONFIG" to file.path, "DIW_PLATFORM" to "mars")) }
    }

    @Test
    fun secretsNeverAppearInTheEffectiveSettings() {
        val c = ServerConfig.from(mapOf("public_url" to "https://x.example", "verifier_access_token" to "secret-code-1", "signing_key_pem" to "PEM"))
        val shown = c.effective()
        assertEquals(ServerConfig.SECRET_SET, shown["verifier_access_token"])
        assertEquals(ServerConfig.SECRET_SET, shown["signing_key_pem"])
        assertTrue(shown.values.none { it == "secret-code-1" || it == "PEM" })
        assertEquals(shown["verifier_access_token"], ServerConfig.from(mapOf("public_url" to "https://x.example", "verifier_access_token" to "another-code")).effective()["verifier_access_token"])
    }
}
