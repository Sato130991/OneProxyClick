package com.oneproxyclick.feature.overlay

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat

/** Intent extras understood by the main activity when opened from the overlay. */
object OverlayContract {
    const val EXTRA_OPEN_TAB = "com.oneproxyclick.extra.OPEN_TAB"
    const val EXTRA_TRANSACTION_ID = "com.oneproxyclick.extra.TRANSACTION_ID"
    const val EXTRA_REQUEST_VPN_PERMISSION = "com.oneproxyclick.extra.REQUEST_VPN"
    const val TAB_HISTORY = "history"
    const val TAB_INTERCEPT = "intercept"

    fun canDrawOverlays(context: Context): Boolean = Settings.canDrawOverlays(context)

    val isShowing get() = FloatingOverlayService.running

    fun show(context: Context): Boolean {
        if (!canDrawOverlays(context)) return false
        ContextCompat.startForegroundService(context, Intent(context, FloatingOverlayService::class.java))
        return true
    }

    fun hide(context: Context) {
        context.stopService(Intent(context, FloatingOverlayService::class.java))
    }
}
