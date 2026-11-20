package com.oneproxyclick.core.common

import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.content.res.Resources
import android.os.LocaleList
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.util.Locale

enum class AppLanguage(val tag: String, val nativeName: String) {
    ENGLISH("en", "English"),
    SPANISH("es", "Español");

    val locale: Locale get() = Locale.forLanguageTag(tag)

    companion object {
        fun fromTag(tag: String?): AppLanguage? = entries.firstOrNull { it.tag == tag }

        /** Spanish when the system is in Spanish (any region), English otherwise. */
        fun systemDefault(): AppLanguage =
            if (Resources.getSystem().configuration.locales[0]?.language == "es") SPANISH else ENGLISH
    }
}

private val Context.languageStore: DataStore<Preferences> by preferencesDataStore(name = "app_language")

/**
 * In-app language (independent from the system locale), switchable at runtime.
 *
 * Compose screens use [wrap] (localized Resources on top of the Activity), services use
 * [localized] and observe [language] to refresh views/notifications — nothing is restarted.
 */
object LanguageManager {
    private val KEY = stringPreferencesKey("language")

    private val _language = MutableStateFlow(AppLanguage.systemDefault())
    val language: StateFlow<AppLanguage> = _language.asStateFlow()

    @Volatile private var initialized = false

    /** Loads the saved choice; call once from Application.onCreate (tiny blocking read). */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val saved = runCatching {
            runBlocking { context.applicationContext.languageStore.data.first()[KEY] }
        }.getOrNull()
        _language.value = AppLanguage.fromTag(saved) ?: AppLanguage.systemDefault()
    }

    suspend fun set(context: Context, language: AppLanguage) {
        _language.value = language
        context.applicationContext.languageStore.edit { it[KEY] = language.tag }
    }

    fun configurationFor(base: Configuration, language: AppLanguage = _language.value): Configuration =
        Configuration(base).apply {
            setLocales(LocaleList(language.locale))
            setLayoutDirection(language.locale)
        }

    /** Plain localized context (for getString in services, receivers, view models). */
    fun localized(context: Context, language: AppLanguage = _language.value): Context =
        context.createConfigurationContext(configurationFor(context.resources.configuration, language))

    /**
     * Wraps [base] (typically an Activity) so `resources` are localized while everything else —
     * startActivity, window, theme — still goes through the original context.
     */
    fun wrap(base: Context, baseConfiguration: Configuration, language: AppLanguage = _language.value): Context {
        val localizedResources = base.createConfigurationContext(configurationFor(baseConfiguration, language)).resources
        return LocalizedContextWrapper(base, localizedResources)
    }

    private class LocalizedContextWrapper(base: Context, private val res: Resources) : ContextWrapper(base) {
        override fun getResources(): Resources = res
    }
}

/** Shorthand: localized string outside Compose. */
fun Context.tr(resId: Int, vararg args: Any): String =
    LanguageManager.localized(this).getString(resId, *args)
