package com.oneproxyclick.core.vpn

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

enum class SplitMode {
    /** Every app goes through the proxy except the selected ones (and the always-excluded set). */
    EXCLUDE,
    /** Only the selected apps go through the proxy. */
    INCLUDE,
}

data class TunnelSettings(
    val mode: SplitMode = SplitMode.EXCLUDE,
    val packages: Set<String> = emptySet(),
)

private val Context.tunnelDataStore: DataStore<Preferences> by preferencesDataStore(name = "tunnel_settings")

class TunnelSettingsStore(context: Context) {
    private val store = context.applicationContext.tunnelDataStore

    val settings: Flow<TunnelSettings> = store.data.map { prefs ->
        TunnelSettings(
            mode = prefs[KEY_MODE]?.let { runCatching { SplitMode.valueOf(it) }.getOrNull() } ?: SplitMode.EXCLUDE,
            packages = prefs[KEY_PACKAGES] ?: emptySet(),
        )
    }

    suspend fun setMode(mode: SplitMode) {
        store.edit { it[KEY_MODE] = mode.name }
    }

    suspend fun setPackageSelected(packageName: String, selected: Boolean) {
        store.edit { prefs ->
            val current = prefs[KEY_PACKAGES] ?: emptySet()
            prefs[KEY_PACKAGES] = if (selected) current + packageName else current - packageName
        }
    }

    suspend fun clearPackages() {
        store.edit { it[KEY_PACKAGES] = emptySet() }
    }

    private companion object {
        val KEY_MODE = stringPreferencesKey("split_mode")
        val KEY_PACKAGES = stringSetPreferencesKey("split_packages")
    }
}

data class InstalledApp(val packageName: String, val label: String, val isSystem: Boolean)

object InstalledApps {
    /** Packages that are never routed through the proxy (anti-loop). */
    fun alwaysExcluded(context: Context): Set<String> = setOf(context.packageName) + TERMINAL_PACKAGES

    val TERMINAL_PACKAGES = setOf("com.kaliterm.app", "com.termux")

    /** Launchable apps (visible through the `<queries>` launcher intent). */
    suspend fun load(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val excluded = alwaysExcluded(context)
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(launcher, 0)
        resolved.asSequence()
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filterNot { it.packageName in excluded }
            .map { info ->
                InstalledApp(
                    packageName = info.packageName,
                    label = info.loadLabel(pm).toString(),
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0,
                )
            }
            .sortedWith(compareBy<InstalledApp> { it.isSystem }.thenBy { it.label.lowercase() })
            .toList()
    }
}
