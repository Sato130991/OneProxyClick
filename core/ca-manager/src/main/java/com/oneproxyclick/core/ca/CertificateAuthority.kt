package com.oneproxyclick.core.ca

import android.content.Context
import android.util.Base64
import android.util.LruCache
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.ExtendedKeyUsage
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.GeneralName
import org.bouncycastle.asn1.x509.GeneralNames
import org.bouncycastle.asn1.x509.KeyPurposeId
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Private root CA ("OneProxyClick CA") used to sign leaf certificates on the fly.
 *
 * - The CA key pair and certificate are created on first use and persisted (DER) in `filesDir/ca/`,
 *   which is private to the app.
 * - Leaf certificates share one RSA key pair generated per process (RSA keygen is the slowest
 *   step on a phone); each leaf is only a signature over a new TBS certificate and is cached
 *   in an [LruCache] keyed by host name.
 */
class CertificateAuthority private constructor(private val dir: File) {

    /** Material needed by a TLS server to impersonate one host. */
    class LeafIdentity(
        val host: String,
        val certificate: X509Certificate,
        val privateKey: PrivateKey,
        /** Leaf first, then the root CA. */
        val chain: Array<X509Certificate>,
    )

    private val bcProvider = BouncyCastleProvider()
    private val random = SecureRandom()
    private val certFactory: CertificateFactory = CertificateFactory.getInstance("X.509")
    private val leafCache = LruCache<String, LeafIdentity>(LEAF_CACHE_SIZE)
    private val lock = Any()

    @Volatile private var caCert: X509Certificate? = null
    @Volatile private var caKey: PrivateKey? = null
    private val leafKeyPair: KeyPair by lazy { generateRsa(LEAF_KEY_BITS) }

    /** The root certificate; generates or loads it on first access. */
    val caCertificate: X509Certificate
        get() {
            ensureInitialized()
            return caCert!!
        }

    /** Loads the CA from disk or creates it. Safe to call from any thread; blocking. */
    fun ensureInitialized() {
        if (caCert != null) return
        synchronized(lock) {
            if (caCert != null) return
            if (!loadFromDisk()) generateAndPersist()
        }
    }

    /** Deletes the current CA (all previously installed copies become useless) and creates a new one. */
    fun regenerate() {
        synchronized(lock) {
            File(dir, CA_CERT_FILE).delete()
            File(dir, CA_KEY_FILE).delete()
            caCert = null
            caKey = null
            leafCache.evictAll()
            generateAndPersist()
        }
    }

    /** Returns (cached) leaf identity for [rawHost], signed by the CA, with a matching SAN. */
    fun leafFor(rawHost: String): LeafIdentity {
        val host = rawHost.trim().trimEnd('.').lowercase().removePrefix("[").removeSuffix("]")
        leafCache.get(host)?.let { return it }
        ensureInitialized()
        val identity = synchronized(lock) {
            leafCache.get(host) ?: createLeaf(host).also { leafCache.put(host, it) }
        }
        return identity
    }

    /** DER encoding of the CA certificate. */
    fun caDer(): ByteArray = caCertificate.encoded

    /** PEM encoding of the CA certificate. */
    fun caPem(): String {
        val b64 = Base64.encodeToString(caDer(), Base64.NO_WRAP)
        return buildString {
            append("-----BEGIN CERTIFICATE-----\n")
            b64.chunked(64).forEach { append(it).append('\n') }
            append("-----END CERTIFICATE-----\n")
        }
    }

    /** SHA-256 fingerprint as colon separated hex. */
    fun fingerprintSha256(): String =
        MessageDigest.getInstance("SHA-256").digest(caDer()).joinToString(":") { "%02X".format(it) }

    // ---------------------------------------------------------------------------------------

    private fun loadFromDisk(): Boolean {
        val certFile = File(dir, CA_CERT_FILE)
        val keyFile = File(dir, CA_KEY_FILE)
        if (!certFile.isFile || !keyFile.isFile) return false
        return try {
            val cert = certFactory.generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
            cert.checkValidity()
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
            caCert = cert
            caKey = key
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun generateAndPersist() {
        val keyPair = generateRsa(CA_KEY_BITS)
        val name = X500NameBuilder(BCStyle.INSTANCE)
            .addRDN(BCStyle.CN, CA_COMMON_NAME)
            .addRDN(BCStyle.O, "OneProxyClick")
            .addRDN(BCStyle.OU, "Local HTTPS debugging proxy")
            .build()
        val now = System.currentTimeMillis()
        val builder = JcaX509v3CertificateBuilder(
            name,
            BigInteger(159, random).add(BigInteger.ONE),
            Date(now - TimeUnit.DAYS.toMillis(2)),
            Date(now + TimeUnit.DAYS.toMillis(CA_VALIDITY_DAYS)),
            name,
            keyPair.public,
        )
        val ext = JcaX509ExtensionUtils()
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(0))
        builder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature),
        )
        builder.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(keyPair.public))
        val cert = toPlatformCert(builder.build(signer(keyPair.private)))

        dir.mkdirs()
        writeAtomically(File(dir, CA_KEY_FILE), keyPair.private.encoded)
        writeAtomically(File(dir, CA_CERT_FILE), cert.encoded)
        caCert = cert
        caKey = keyPair.private
    }

    private fun createLeaf(host: String): LeafIdentity {
        val ca = caCert!!
        val issuer = X500Name.getInstance(ca.subjectX500Principal.encoded)
        val cn = if (host.length <= 64) host else host.takeLast(64)
        val subject = X500NameBuilder(BCStyle.INSTANCE)
            .addRDN(BCStyle.CN, cn)
            .addRDN(BCStyle.O, "OneProxyClick")
            .build()
        val now = System.currentTimeMillis()
        val publicKey: PublicKey = leafKeyPair.public
        val builder = JcaX509v3CertificateBuilder(
            issuer,
            BigInteger(127, random).add(BigInteger.ONE),
            Date(now - TimeUnit.DAYS.toMillis(1)),
            Date(now + TimeUnit.DAYS.toMillis(LEAF_VALIDITY_DAYS)),
            subject,
            publicKey,
        )
        val ext = JcaX509ExtensionUtils()
        val sanEntry = if (isIpLiteral(host)) GeneralName(GeneralName.iPAddress, host)
        else GeneralName(GeneralName.dNSName, host)
        builder.addExtension(Extension.subjectAlternativeName, false, GeneralNames(sanEntry))
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(false))
        builder.addExtension(
            Extension.keyUsage, true,
            KeyUsage(KeyUsage.digitalSignature or KeyUsage.keyEncipherment),
        )
        builder.addExtension(Extension.extendedKeyUsage, false, ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth))
        builder.addExtension(Extension.subjectKeyIdentifier, false, ext.createSubjectKeyIdentifier(publicKey))
        builder.addExtension(Extension.authorityKeyIdentifier, false, ext.createAuthorityKeyIdentifier(ca))
        val leaf = toPlatformCert(builder.build(signer(caKey!!)))
        return LeafIdentity(host, leaf, leafKeyPair.private, arrayOf(leaf, ca))
    }

    private fun signer(key: PrivateKey) =
        JcaContentSignerBuilder("SHA256withRSA").setProvider(bcProvider).build(key)

    /** Re-parse with the platform provider so Conscrypt/JSSE accept the object without surprises. */
    private fun toPlatformCert(holder: X509CertificateHolder): X509Certificate =
        certFactory.generateCertificate(ByteArrayInputStream(holder.encoded)) as X509Certificate

    private fun generateRsa(bits: Int): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(bits, random) }.generateKeyPair()

    private fun writeAtomically(target: File, bytes: ByteArray) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeBytes(bytes)
        if (!tmp.renameTo(target)) {
            target.writeBytes(bytes)
            tmp.delete()
        }
    }

    companion object {
        const val CA_COMMON_NAME = "OneProxyClick CA"
        const val EXPORT_FILE_NAME = "OneProxyClick_CA.crt"
        private const val CA_CERT_FILE = "oneproxyclick_ca.der"
        private const val CA_KEY_FILE = "oneproxyclick_ca.pk8"
        private const val CA_KEY_BITS = 2048
        private const val LEAF_KEY_BITS = 2048
        private const val CA_VALIDITY_DAYS = 3650L
        private const val LEAF_VALIDITY_DAYS = 365L
        private const val LEAF_CACHE_SIZE = 512

        @Volatile private var instance: CertificateAuthority? = null

        fun getInstance(context: Context): CertificateAuthority =
            instance ?: synchronized(this) {
                instance ?: CertificateAuthority(File(context.applicationContext.filesDir, "ca")).also { instance = it }
            }

        private val IPV4 = Regex("""^\d{1,3}(\.\d{1,3}){3}$""")

        internal fun isIpLiteral(host: String): Boolean = IPV4.matches(host) || host.contains(':')
    }
}
