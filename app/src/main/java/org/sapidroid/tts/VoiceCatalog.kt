package org.sapidroid.tts

import java.util.Locale

/**
 * The voices this build can speak with, across both engine families.
 *
 * OneCore voices load from Microsoft's M1033 files; SAPI 4 modes load from
 * msttssyn.dll and its .vce files. Both sets of data are the user's own.
 */
object VoiceCatalog {

    /** OneCore output rate; SAPI 4 reports its own per mode. */
    const val SAMPLE_RATE = 16000

    enum class Engine { ONECORE, SAPI4 }

    data class Info(
        val engine: Engine,
        val name: String,
        val label: String,
        val locale: Locale = Locale.US
    )

    private val oneCore = listOf(
        Info(Engine.ONECORE, "David", "David (OneCore)"),
        Info(Engine.ONECORE, "Zira", "Zira (OneCore)"),
    )

    private val sapi4 = listOf(
        "Sam", "Mike", "Mary",
        "Sam Whisper", "Male Whisper", "Female Whisper",
        "Sam in Space", "Mike in Space", "Mary in Space",
        "Sam in Hall", "Mike in Hall", "Mary in Hall",
        "Sam in Stadium", "Mike in Stadium", "Mary in Stadium",
        "RoboSoft One", "RoboSoft Two", "RoboSoft Three",
        "RoboSoft Four", "RoboSoft Five", "RoboSoft Six",
    ).map { Info(Engine.SAPI4, it, "$it (SAPI 4)") }

    val all: List<Info> = sapi4 + oneCore

    val defaultVoice: String = "David"

    fun infoFor(name: String?): Info? {
        if (name == null) return null
        val bare = name.removeSuffix(" (SAPI 4)").removeSuffix(" (OneCore)")
        return all.firstOrNull { it.name.equals(bare, ignoreCase = true) }
            ?: all.firstOrNull { it.label.equals(name, ignoreCase = true) }
    }

    fun forLocale(lang: String?, country: String?): Info =
        all.firstOrNull {
            it.locale.language.equals(lang, ignoreCase = true) &&
                (country.isNullOrEmpty() || it.locale.country.equals(country, ignoreCase = true))
        } ?: all.first { it.name == defaultVoice }
}
