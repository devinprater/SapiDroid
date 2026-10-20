package org.sapidroid.tts

/**
 * The OneCore voices that can be downloaded as plain files, from Sapple's
 * bundle: Microsoft's Windows 10/11 English voices on the older, readable model
 * format.
 *
 * `prefix` is the name the bundle gives the voice's files ("M2057George.APM");
 * the installer renames them to "M1033George.APM", which is what the engine
 * looks for. The newer voices in the same bundle use a model format this engine
 * cannot load, so they are not offered.
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
        Pack("George", "M2057George", "English (United Kingdom)", "en-GB"),
        Pack("Susan", "M2057Susan", "English (United Kingdom)", "en-GB"),
        Pack("Hazel", "M2057Hazel", "English (United Kingdom)", "en-GB"),
    )

    /** What the menu says about everything it cannot offer. */
    const val NOTE =
        "Everything here comes from one download, Sapple's 200 MB app bundle, " +
            "which carries Microsoft's voice files for personal use. It is kept " +
            "until you close this screen, so picking several voices downloads it " +
            "once. Voices built on Microsoft's newer model (Sarah, Eva, James, " +
            "Matilda, Catherine, Richard, Linda) are not offered: this engine " +
            "cannot load them."

    fun byVoice(voice: String): Pack? = all.firstOrNull { it.voice == voice }
}
