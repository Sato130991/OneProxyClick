package com.oneproxyclick.app.bridge

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Shared secret for the local control API (`Authorization: Bearer <token>`). 127.0.0.1 is
 * reachable by every installed app, so the token is what stops them from driving the proxy.
 * Stored privately in `filesDir`; embedded in the exported `opc-cli.sh`.
 */
object BridgeToken {
    private const val FILE = "bridge_token"
    @Volatile private var cached: String? = null

    fun get(context: Context): String = cached ?: synchronized(this) {
        cached ?: run {
            val f = File(context.applicationContext.filesDir, FILE)
            val existing = runCatching { f.readText().trim() }.getOrNull()?.takeIf { it.length >= 16 }
            existing ?: generate(f)
        }.also { cached = it }
    }

    fun regenerate(context: Context): String = synchronized(this) {
        generate(File(context.applicationContext.filesDir, FILE)).also { cached = it }
    }

    /** Constant-time comparison. */
    fun matches(context: Context, candidate: String?): Boolean {
        if (candidate.isNullOrEmpty()) return false
        return MessageDigest.isEqual(candidate.toByteArray(), get(context).toByteArray())
    }

    private fun generate(f: File): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        f.writeText(token)
        return token
    }
}
