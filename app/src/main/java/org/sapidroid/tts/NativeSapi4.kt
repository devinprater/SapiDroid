package org.sapidroid.tts

/** JNI entry points for the SAPI 4 engine (Sam, Mike, Mary and their modes). */
object NativeSapi4 {
    init {
        System.loadLibrary("sapidroid")
    }

    /** Loads the engine's data from the user's own msttssyn.dll. 0 on success. */
    @JvmStatic external fun init(dllPath: String): Int

    @JvmStatic external fun listModes(voiceDir: String): Array<String>?

    @JvmStatic external fun open(voiceDir: String, mode: String): Long

    /** [pitchDefault, pitchMin, pitchMax, speedDefault, speedMin, speedMax] */
    @JvmStatic external fun limits(handle: Long): IntArray?

    @JvmStatic external fun sampleRate(handle: Long): Int

    @JvmStatic external fun setSpeed(handle: Long, wpm: Int)

    @JvmStatic external fun setPitch(handle: Long, pitch: Int)

    /** Blocking; [cancel] from another thread stops it. Text is Windows-1252. */
    @JvmStatic external fun speak(handle: Long, text: ByteArray): ShortArray?

    @JvmStatic external fun cancel(handle: Long)

    @JvmStatic external fun close(handle: Long)
}
