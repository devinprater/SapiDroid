package org.sapidroid.tts

/**
 * The OneCore voices that can be downloaded as plain files, from Sapple's
 * bundle: Microsoft's English OneCore voices on the older, readable model
 * format.
 *
 * `prefix` is the name the bundle gives the voice's files ("M2057George.APM");
 * the installer renames them to "M1033George.APM", which is what the engine
 * looks for. David, Zira and Mark already arrive under the M1033 names. Three
 * voices in the same bundle (Sarah, Eva, Matilda) use a model format this
 * engine cannot load, so they are not offered.
 */
object OneCorePacks {

    data class Pack(
        val voice: String,
        val prefix: String,
        val language: String,
        val country: String
    ) {
        val label: String get() = "$language — $voice"
    }

    val all: List<Pack> = listOf(
        Pack("David", "M1033David", "English (United States)", "en-US"),
        Pack("Zira", "M1033Zira", "English (United States)", "en-US"),
        Pack("Mark", "M1033Mark", "English (United States)", "en-US"),
        Pack("George", "M2057George", "English (United Kingdom)", "en-GB"),
        Pack("Susan", "M2057Susan", "English (United Kingdom)", "en-GB"),
        Pack("Hazel", "M2057Hazel", "English (United Kingdom)", "en-GB"),
        Pack("James", "M3081James", "English (Australia)", "en-AU"),
        Pack("Catherine", "M3081Catherine", "English (Australia)", "en-AU"),
        Pack("Richard", "M4105Richard", "English (India)", "en-IN"),
        Pack("Linda", "M4105Linda", "English (India)", "en-IN"),
    )

    /** What the menu says about everything it cannot offer. */
    const val NOTE =
        "Everything here comes from one download, Sapple's 200 MB app bundle, " +
            "which carries Microsoft's voice files for personal use. It is kept " +
            "while the app is open, so picking several voices downloads it once. " +
            "Every download also installs " + SappleVoices.SHARED_DATA + ", the " +
            "shared table the engine reads whichever voice it speaks with. Three " +
            "voices in the bundle are not offered because this engine cannot " +
            "load their model: Sarah and Eva, and Matilda."

    fun byVoice(voice: String): Pack? = all.firstOrNull { it.voice == voice }
}
