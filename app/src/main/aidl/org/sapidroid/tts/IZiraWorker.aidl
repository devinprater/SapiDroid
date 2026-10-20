package org.sapidroid.tts;

/**
 * The voice engine, in a process of its own.
 *
 * A native call that will not return is cleared by the owner killing this
 * process, never by interrupting the thread inside it.
 */
interface IZiraWorker {
    /**
     * Starts synthesizing [text] with [voice].  Returns a request id; if the
     * previous request is still running it waits up to [waitMs], then
     * answers BUSY.
     */
    int say(String voice, int rate, int pitch, String text, int waitMs);

    /**
     * The next block of little-endian 16-bit samples, or null when the
     * request is finished, cancelled, unknown, or [waitMs] passed with
     * nothing ready.
     */
    byte[] pull(int requestId, int maxBytes, int waitMs);

    /** True once [requestId] has completed, or the engine failed. */
    boolean finished(int requestId);

    /** Asks the engine to stop.  Never interrupts the thread inside it. */
    void cancel(int requestId);

    /** Kills this worker process.  Only the owner may call this. */
    void shutdown();

    /** Engine output rate in Hz, or 0 if the voice data will not load. */
    int sampleRate(String voice);

    /** Empty when the voice data is present, otherwise why it is not. */
    String probe(String voice);
}
