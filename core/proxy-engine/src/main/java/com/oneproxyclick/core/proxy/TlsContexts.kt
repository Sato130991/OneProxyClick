package com.oneproxyclick.core.proxy

import android.util.LruCache
import com.oneproxyclick.core.ca.CertificateAuthority
import io.netty.handler.ssl.SslContext
import io.netty.handler.ssl.SslContextBuilder
import io.netty.handler.ssl.SslProvider
import io.netty.handler.ssl.util.InsecureTrustManagerFactory
import java.net.Socket
import java.security.Principal
import java.security.PrivateKey
import java.security.cert.X509Certificate
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * Netty TLS contexts. Server contexts impersonate intercepted hosts with CA-signed leaves.
 * The platform (Conscrypt) JSSE provider is used; ALPN is not negotiated so both sides speak
 * HTTP/1.1.
 */
internal class TlsContexts(private val ca: CertificateAuthority) {

    private val serverCache = LruCache<String, SslContext>(256)

    fun serverContext(rawHost: String): SslContext {
        val host = rawHost.lowercase()
        serverCache.get(host)?.let { return it }
        synchronized(this) {
            serverCache.get(host)?.let { return it }
            val identity = ca.leafFor(host)
            val context = SslContextBuilder
                .forServer(SingleKeyManager(identity.privateKey, identity.chain))
                .sslProvider(SslProvider.JDK)
                .build()
            serverCache.put(host, context)
            return context
        }
    }

    val verifyingClient: SslContext by lazy {
        SslContextBuilder.forClient().sslProvider(SslProvider.JDK).build()
    }

    val insecureClient: SslContext by lazy {
        SslContextBuilder.forClient()
            .sslProvider(SslProvider.JDK)
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .build()
    }

    fun clear() = serverCache.evictAll()

    /** Key manager that always presents one RSA key + chain, avoiding platform KeyStore types. */
    private class SingleKeyManager(
        private val key: PrivateKey,
        private val chain: Array<X509Certificate>,
    ) : X509ExtendedKeyManager() {
        private fun aliasFor(keyType: String?): String? =
            if (keyType == null || keyType.contains("RSA", ignoreCase = true)) ALIAS else null

        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?): String? = null
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? =
            aliasFor(keyType)?.let { arrayOf(it) }
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = aliasFor(keyType)
        override fun chooseEngineServerAlias(keyType: String?, issuers: Array<out Principal>?, engine: SSLEngine?): String? = aliasFor(keyType)
        override fun getCertificateChain(alias: String?): Array<X509Certificate> = chain
        override fun getPrivateKey(alias: String?): PrivateKey = key

        companion object {
            const val ALIAS = "oneproxyclick-leaf"
        }
    }
}
