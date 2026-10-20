package org.sapidroid.tts

import java.util.Locale

/** The voices this build knows how to load from Microsoft's OneCore data. */
object VoiceCatalog {

    const val SAMPLE_RATE = 16000

    data class Info(val name: String, val locale: Locale)

    val all = listOf(
        Info("David", Locale.US),
        Info("Zira", Locale.US),
    )

    val defaultVoice: String = "David"

    fun infoFor(name: String?): Info? =
        all.firstOrNull { it.name.equals(name, ignoreCase = true) }

    fun forLocale(lang: String?, country: String?): Info =
        all.firstOrNull {
            it.locale.language.equals(lang, ignoreCase = true) &&
                (country.isNullOrEmpty() || it.locale.country.equals(country, ignoreCase = true))
        } ?: all.first()
}
