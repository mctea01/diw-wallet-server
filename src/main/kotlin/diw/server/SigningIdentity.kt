package diw.server

import org.multipaz.asn1.ASN1Integer
import org.multipaz.crypto.AsymmetricKey
import org.multipaz.crypto.Crypto
import org.multipaz.crypto.EcCurve
import org.multipaz.crypto.EcPrivateKey
import org.multipaz.crypto.X500Name
import org.multipaz.crypto.X509Cert
import org.multipaz.crypto.X509CertChain
import org.multipaz.mdoc.util.MdocUtil
import org.multipaz.util.truncateToWholeSeconds
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/**
 * The key that signs zkTLS verification requests and web-proof receipts, with its certificate chain (leaf first,
 * self-signed root last). Kept in one PEM file, the private key followed by the chain, so a platform secret (Cloud
 * Run's Secret Manager) can hold it whole. The wallet trusts the root after the user compares [rootSha256].
 */
class SigningIdentity(val key: AsymmetricKey.X509CertifiedExplicit) {
    val chain: List<X509Cert> get() = key.certChain.certificates
    val root: X509Cert get() = chain.last()

    /** SHA-256 of the root certificate's DER, lower-case hex: what the user compares in the wallet. */
    val rootSha256: String get() = sha256Hex(root.encoded.toByteArray())

    companion object {
        private val PRIVATE = Regex("-----BEGIN (EC )?PRIVATE KEY-----[\\s\\S]+?-----END (EC )?PRIVATE KEY-----")
        private val CERT = Regex("-----BEGIN CERTIFICATE-----[\\s\\S]+?-----END CERTIFICATE-----")

        fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

        /** A new P-256 root and a leaf for [publicUrl]'s host, valid for twenty years. */
        suspend fun create(publicUrl: String): SigningIdentity {
            val host = URI(publicUrl).host
            val now = Clock.System.now().truncateToWholeSeconds()
            val from = now - 1.days
            val until = now + 7300.days
            val rootKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val root = MdocUtil.generateReaderRootCertificate(
                readerRootKey = AsymmetricKey.anonymous(rootKey),
                subject = X500Name.fromName("O=DIW Wallet Server,CN=$host Signing Root"),
                serial = ASN1Integer.fromRandom(128), validFrom = from, validUntil = until,
                crlUrl = "${publicUrl.trimEnd('/')}/pki/crl",
            )
            val leafKey = Crypto.createEcPrivateKey(EcCurve.P256)
            val leaf = MdocUtil.generateReaderCertificate(
                readerRootKey = AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(root)), rootKey),
                readerKey = leafKey.publicKey,
                subject = X500Name.fromName("O=DIW Wallet Server,CN=$host"),
                dnsName = host,
                serial = ASN1Integer.fromRandom(128), validFrom = from, validUntil = until,
            )
            return SigningIdentity(AsymmetricKey.X509CertifiedExplicit(X509CertChain(listOf(leaf, root)), leafKey))
        }

        fun parse(pem: String): SigningIdentity {
            val keyPem = PRIVATE.find(pem)?.value ?: throw IllegalArgumentException("No private key in the signing file")
            val certs = CERT.findAll(pem).map { X509Cert.fromPem(it.value) }.toList()
            require(certs.size >= 2) { "The signing file needs the leaf and the root certificate" }
            val key = EcPrivateKey.fromPem(keyPem, certs.first().ecPublicKey)
            return SigningIdentity(AsymmetricKey.X509CertifiedExplicit(X509CertChain(certs), key))
        }

        fun pem(identity: SigningIdentity): String =
            identity.key.privateKey.toPem() + "\n" + identity.chain.joinToString("\n") { it.toPem() } + "\n"

        /**
         * The identity in [file]; when it is missing and [generate] is set, a new one is written there (0600) so it
         * survives restarts. A mounted secret is read only.
         */
        suspend fun load(file: File, publicUrl: String, generate: Boolean, pem: String? = null): SigningIdentity {
            if (pem != null) return parse(pem)
            if (file.isFile) return parse(file.readText())
            require(generate) { "Signing file not found: ${file.path} (run diw-server keygen to make one)" }
            return create(publicUrl).also { write(file, it) }
        }

        fun write(file: File, identity: SigningIdentity) = writePrivateFile(file, pem(identity))
    }
}

/** Writes [text] to [file], readable by the owner only where the file system allows it. */
internal fun writePrivateFile(file: File, text: String) {
    file.parentFile?.mkdirs()
    val path = file.toPath()
    Files.deleteIfExists(path)
    runCatching { Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))) }
        .getOrElse { Files.createFile(path) }
    file.writeText(text)
}
