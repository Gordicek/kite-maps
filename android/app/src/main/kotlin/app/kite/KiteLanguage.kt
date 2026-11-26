package app.kite

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

enum class KiteLanguage(val tag: String, val label: String) {
    SYSTEM("", "System"), ENGLISH("en", "English"), CZECH("cs-CZ", "Čeština");

    val locale: Locale get() = if (tag.isEmpty()) Locale.getDefault() else Locale.forLanguageTag(tag)

    companion object {
        fun wrap(base: Context): Context {
            val tag = base.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("uiLanguage", "").orEmpty()
            if (tag.isEmpty()) return base
            val config = Configuration(base.resources.configuration)
            config.setLocale(Locale.forLanguageTag(tag))
            return base.createConfigurationContext(config)
        }
    }
}

internal val czechUi: Boolean get() = AppSettings.language.locale.language == "cs"

internal fun kt(en: String, cs: String): String = if (czechUi) cs else en
