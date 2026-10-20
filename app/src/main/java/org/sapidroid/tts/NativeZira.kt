package org.sapidroid.tts

/** JNI entry points into libsapidroid.so (the OneCore voice engine). */
object NativeZira {
    init {
        System.loadLibrary("sapidroid")
    }

    @JvmStatic external fun open(dir: String, voice: String): Long

    @JvmStatic external fun close(handle: Long)

    @JvmStatic external fun setRate(handle: Long, rate: Int)

    @JvmStatic external fun setPitch(handle: Long, pitch: Int)

    @JvmStatic external fun setVolume(handle: Long, volume: Int)

    /** Blocking; [cancel] from another thread stops it within one chunk. */
    @JvmStatic external fun speak(handle: Long, text: String): ShortArray?

    @JvmStatic external fun cancel(handle: Long)
}
