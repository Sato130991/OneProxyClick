package com.oneproxyclick.core.proxy

data class ProxyConfig(
    val bindHost: String = "127.0.0.1",
    val port: Int = DEFAULT_PORT,
    /** Terminate TLS inside CONNECT tunnels. When false HTTPS is relayed blindly (no decryption). */
    val mitmEnabled: Boolean = true,
    /** Validate upstream server certificates + host names. Disable for self-signed test servers. */
    val verifyUpstreamTls: Boolean = true,
    /** Hosts (and their sub-domains) that are always tunnelled without decryption (pinned apps). */
    val passthroughHosts: List<String> = emptyList(),
    val upstreamTimeoutMs: Long = 60_000,
) {
    companion object {
        const val DEFAULT_PORT = 8080
        /** Max aggregated HTTP message size (request or response) held in memory. */
        const val MAX_MESSAGE_BYTES = 32 * 1024 * 1024
    }
}

sealed interface EngineState {
    data object Stopped : EngineState
    data class Running(val host: String, val port: Int) : EngineState
    data class Failed(val message: String) : EngineState
}
