package com.grmemby.app.locale

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.core.app.LocaleManagerCompat
import java.util.Locale

object AppLanguageManager {
    private const val DEFAULT_LANGUAGE_TAG = "zh-CN"
    fun applySavedLanguage(context: Context) {
        syncDefaultLocale(getCurrentLanguageTag(context))
    }

    fun getCurrentLanguageTag(context: Context): String {
        val explicitLanguage = formatLanguageTag(LocaleManagerCompat.getApplicationLocales(context).toLanguageTags())
        return explicitLanguage.ifBlank { DEFAULT_LANGUAGE_TAG }
    }

    fun wrapContext(base: Context): Context {
        val languageTag = getCurrentLanguageTag(base)
        if (languageTag.isBlank()) {
            return base
        }

        val locale = Locale.forLanguageTag(languageTag)
        Locale.setDefault(locale)
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocale(locale)
        configuration.setLayoutDirection(locale)
        return base.createConfigurationContext(configuration)
    }

    private fun formatLanguageTag(languageTag: String?): String {
        return languageTag?.trim().orEmpty()
    }

    private fun syncDefaultLocale(languageTag: String) {
        val locale = if (languageTag.isBlank()) {
            Resources.getSystem().configuration.locales.get(0)
        } else {
            Locale.forLanguageTag(languageTag)
        }
        Locale.setDefault(locale)
    }
}
