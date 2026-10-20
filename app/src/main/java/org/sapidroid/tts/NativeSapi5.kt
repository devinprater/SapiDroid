package org.sapidroid.tts

/** JNI entry points for the SAPI 5 engine (Microsoft Sam, Mike, Mary). */
object NativeSapi5 {
    init {
        System.loadLibrary("sapidroid")
    }

    @JvmStatic external fun open(dataDir: String, voice: String): Long

    @JvmStatic external fun close(handle: Long)

    @JvmStatic external fun setRate(handle: Long, rate: Int)

    @JvmStatic external fun setPitch(handle: Long, pitch: Int)

    @JvmStatic external fun setVolume(handle: Long, volume: Int)

    @JvmStatic external fun sampleRate(handle: Long): Int

    /** Blocking; [cancel] from another thread stops it. */
    @JvmStatic external fun speak(handle: Long, text: String): ShortArray?

    @JvmStatic external fun cancel(handle: Long)
}
