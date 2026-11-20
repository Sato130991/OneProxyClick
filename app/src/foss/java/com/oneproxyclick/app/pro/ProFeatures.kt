package com.oneproxyclick.app.pro

import android.app.Activity
import android.content.Context
import com.oneproxyclick.core.common.pro.Distribution
import com.oneproxyclick.core.common.pro.ProFeatureManager
import com.oneproxyclick.core.common.pro.PurchaseState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** FOSS flavor (GitHub Releases): every feature is unlocked, no store, no Google libraries. */
object ProFeatures {
    fun create(@Suppress("UNUSED_PARAMETER") context: Context): ProFeatureManager = FossProFeatureManager
}

private object FossProFeatureManager : ProFeatureManager {
    override val distribution = Distribution.FOSS
    override val isPro: StateFlow<Boolean> = MutableStateFlow(true).asStateFlow()
    override val purchaseState: StateFlow<PurchaseState> = MutableStateFlow<PurchaseState>(PurchaseState.NotRequired).asStateFlow()
    override fun connect() = Unit
    override fun purchase(activity: Activity) = Unit
    override fun restore() = Unit
}
