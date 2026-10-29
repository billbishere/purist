package purist.audio;

import java.util.function.Consumer;

/** An audio output device that can be opened for one bit-perfect stream at a time. */
public interface AudioSink extends AutoCloseable {

    /** The device's display name. */
    String name();

    /**
     * Stops and disposes the previous stream (if any), then opens a stream that carries {@code f} to the
     * device. After a refusal there is no open stream.
     *
     * @throws NotBitPerfectException if the device cannot play this format bit-perfectly; the message is the
     *         reason to show when the track is skipped
     * @throws AudioDeviceException   if the device itself failed
     */
    SinkStream open(StreamFormat f) throws NotBitPerfectException, AudioDeviceException;

    /** Registers a callback that receives a message when the device is lost while a stream is open. */
    void addDeviceListener(Consumer<String> onDeviceLost);

    /** Disposes the open stream and releases the device. */
    @Override
    void close();
}
