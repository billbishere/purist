package purist.audio;

/**
 * One open, fixed-format output stream on an {@link AudioSink}: a {@link StreamCore} that the decoder thread
 * writes into and the driver callback drains, plus the control to start and stop that draining. A stream
 * lasts until the sink opens another one (or is closed); using it afterwards is an error.
 */
public interface SinkStream {

    /**
     * The core to {@link StreamCore#setSource set the source on} and {@link StreamCore#write write into}.
     * The same core serves every track this stream {@link #accepts accepts}, which is what makes playback
     * gapless.
     */
    StreamCore core();

    /**
     * The signal path of what is currently being written: the source most recently given to
     * {@link StreamCore#setSource}, the device's sample type, rate and buffer size, and the transform in
     * effect. Built on each call, so a caller that wants the path of one particular track should read it right
     * after that track's {@code setSource}.
     */
    SignalPath signalPath();

    /**
     * True when a track in {@code next} can be written into this stream without reopening it: it has this
     * stream's kind, rate and channel count, and can be written bit-perfectly into this stream's sample type.
     */
    boolean accepts(StreamFormat next);

    /** Starts the driver calling back for audio. Fill the ring first, or the start-up counts as dropouts. */
    void start() throws AudioDeviceException;

    /**
     * Stops the driver calling back. When this returns, no callback is running or will run until the next
     * {@link #start}, which is what makes {@link StreamCore#reset()} safe.
     */
    void stop();
}
