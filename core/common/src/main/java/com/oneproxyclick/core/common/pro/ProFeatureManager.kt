package com.oneproxyclick.core.common.pro

import android.app.Activity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Advanced features that require "OneProxyClick Pro" in the `play` flavor. */
enum class ProFeature {
    /** Auto-JSON GET/POST + live `.jsonl` stream. */
    LIVE_JSON,

    /** KaliTerm bridge: local control API on 127.0.0.1 + forward to local port. */
    KALITERM_BRIDGE,

    /** More than one simultaneous Repeater tab. */
    MULTI_REPEATER_TABS,

    /** Match & Replace rules. */
    MATCH_REPLACE,
}

enum class Distribution { FOSS, PLAY }

sealed interface PurchaseState {
    /** FOSS build: nothing to buy. */
    data object NotRequired : PurchaseState
    data object Connecting : PurchaseState
    /** Billing unavailable (no Play Store, sideloaded build, not signed in…). */
    data class Unavailable(val reason: String) : PurchaseState
    /** Ready to buy; [price] is the store-formatted price. */
    data class Available(val price: String) : PurchaseState
    /** Payment pending (e.g. cash payment); unlocks when it completes. */
    data object Pending : PurchaseState
    data object Owned : PurchaseState
    data class Error(val message: String) : PurchaseState
}

/**
 * Feature gate. Base features (VPN tunnel, HTTPS capture, bubble, 1 Repeater tab, Decoder, single
 * JSON export) are never gated. Implementations live in the `foss` / `play` source sets of :app.
 *
 * All Pro features share one lifetime purchase, so [isUnlocked] maps to [isPro]; the per-feature
 * API keeps call sites explicit and allows finer tiers later.
 */
interface ProFeatureManager {
    val distribution: Distribution
    val isPro: StateFlow<Boolean>
    val purchaseState: StateFlow<PurchaseState>

    fun isUnlocked(feature: ProFeature): Boolean = isPro.value

    /** Connects to the store and refreshes entitlement (idempotent). */
    fun connect()

    /** Starts the purchase flow for the lifetime product. */
    fun purchase(activity: Activity)

    /** Re-queries owned purchases (e.g. after reinstall). */
    fun restore()
}

/** Process-wide access point; the Application installs the flavor's implementation first thing. */
object Pro {
    @Volatile var manager: ProFeatureManager = Locked
        private set

    fun install(manager: ProFeatureManager) {
        this.manager = manager
    }

    fun isUnlocked(feature: ProFeature): Boolean = manager.isUnlocked(feature)

    /** Fail-closed placeholder until the Application installs the real manager. */
    private object Locked : ProFeatureManager {
        override val distribution = Distribution.PLAY
        override val isPro: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
        override val purchaseState: StateFlow<PurchaseState> = MutableStateFlow<PurchaseState>(PurchaseState.Connecting).asStateFlow()
        override fun connect() = Unit
        override fun purchase(activity: Activity) = Unit
        override fun restore() = Unit
    }
}
