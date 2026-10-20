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

    enum class Engine { ONECORE, ANNA, SAPI4, SAPI5 }

    data class Info(
        val engine: Engine,
        val name: String,
        val label: String,
        val locale: Locale = Locale.US
    )

    private val oneCore = listOf(
        Info(Engine.ONECORE, "David", "David (OneCore)"),
        Info(Engine.ONECORE, "Zira", "Zira (OneCore)"),
        Info(Engine.ONECORE, "Mark", "Mark (OneCore)"),
        // Microsoft Speech Platform 11 packs, which this engine also reads.
        Info(Engine.ONECORE, "Hazel", "Hazel (OneCore)", Locale("en", "GB")),
        Info(Engine.ONECORE, "Hayley", "Hayley (OneCore)", Locale("en", "AU")),
        Info(Engine.ONECORE, "Heather", "Heather (OneCore)", Locale("en", "CA")),
        Info(Engine.ONECORE, "Heera", "Heera (OneCore)", Locale("en", "IN")),
        Info(Engine.ONECORE, "Helen", "Helen (OneCore)", Locale("en", "US")),
        Info(Engine.ONECORE, "ZiraPro", "Zira Pro (OneCore)", Locale("en", "US")),
        // OneCore voices from Windows 10/11 language packs.
        Info(Engine.ONECORE, "George", "George (OneCore)", Locale("en", "GB")),
        Info(Engine.ONECORE, "Susan", "Susan (OneCore)", Locale("en", "GB")),
        Info(Engine.ONECORE, "Sarah", "Sarah (OneCore)", Locale("en", "GB")),
        Info(Engine.ONECORE, "James", "James (OneCore)", Locale("en", "AU")),
        Info(Engine.ONECORE, "Matilda", "Matilda (OneCore)", Locale("en", "AU")),
        Info(Engine.ONECORE, "Catherine", "Catherine (OneCore)", Locale("en", "AU")),
        Info(Engine.ONECORE, "Richard", "Richard (OneCore)", Locale("en", "IN")),
        Info(Engine.ONECORE, "Linda", "Linda (OneCore)", Locale("en", "IN")),
    )

    /** Microsoft Anna, the Windows Vista and 7 voice: her own engine. */
    private val anna = listOf(
        Info(Engine.ANNA, "Anna", "Anna (Vista)"),
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

    /**
     * SAPI 5's Sam, Mike and Mary. Different engine from the SAPI 4 voices of
     * the same names, so their internal names carry the 5.
     */
    private val sapi5 = listOf(
        Info(Engine.SAPI5, "Sam5", "Sam (SAPI 5)"),
        Info(Engine.SAPI5, "Mike5", "Mike (SAPI 5)"),
        Info(Engine.SAPI5, "Mary5", "Mary (SAPI 5)"),
    )

    val all: List<Info> = sapi5 + sapi4 + oneCore + anna

    val defaultVoice: String = "David"

    /**
     * The voices whose data is actually present. A OneCore voice is there when
     * its M1033<name>.APM file is; the other engines keep their own folders, so
     * everything else is reported and the engine says if it is missing.
     */
    fun installed(context: android.content.Context): List<Info> {
        val voice = java.io.File(context.filesDir, "voice")
        return all.filter { info ->
            when (info.engine) {
                Engine.ONECORE -> java.io.File(voice, "M1033${info.name}.APM").exists() ||
                    info.name == defaultVoice
                else -> true
            }
        }
    }

    fun infoFor(name: String?): Info? {
        if (name == null) return null
        all.firstOrNull { it.label.equals(name, ignoreCase = true) }?.let { return it }
        all.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it }
        val bare = name.removeSuffix(" (SAPI 5)")
            .removeSuffix(" (SAPI 4)")
            .removeSuffix(" (OneCore)")
        return all.firstOrNull { it.name.equals(bare, ignoreCase = true) }
    }

    fun forLocale(lang: String?, country: String?): Info =
        all.firstOrNull {
            it.locale.language.equals(lang, ignoreCase = true) &&
                (country.isNullOrEmpty() || it.locale.country.equals(country, ignoreCase = true))
        } ?: all.first { it.name == defaultVoice }
}
