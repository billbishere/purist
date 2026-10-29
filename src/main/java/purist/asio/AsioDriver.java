package purist.asio;

import java.lang.foreign.MemorySegment;

/**
 * One loaded ASIO driver. Every method except {@link #outputReady()} and {@link #buffer} belongs on the
 * single ASIO control thread (the driver's COM apartment); the driver calls the {@link AsioCallbacks}
 * from its own thread.
 */
public interface AsioDriver extends AutoCloseable {

    /** The name the driver is registered under. */
    String name();

    /** The number of output channels the device offers. */
    int outputChannels() throws AsioException;

    BufferSize bufferSize() throws AsioException;

    /** True only when the driver answers {@code ASE_OK}: it can run at exactly {@code rate} Hz. */
    boolean canSampleRate(double rate);

    void setSampleRate(double rate) throws AsioException;

    double sampleRate() throws AsioException;

    /** {@code future(kAsioCanDoIoFormat, DSD)} answered with {@code ASE_SUCCESS} or {@code ASE_OK}. */
    boolean canDoDsd();

    /** {@code future(kAsioSetIoFormat, ...)}: switches the driver to DSD or back to PCM. */
    void setIoFormat(boolean dsd) throws AsioException;

    /** The {@code ASIOSampleType} of output channel {@code channel}, see {@link AsioConstants}. */
    int outputChannelType(int channel) throws AsioException;

    /**
     * Creates the double buffers for the given output channels and installs {@code cb}.
     *
     * @param outputChannels the driver's channel numbers to open, in buffer-index order
     * @param bufferFrames samples per channel per buffer half, normally {@link BufferSize#preferred()}
     */
    void createBuffers(int[] outputChannels, int bufferFrames, AsioCallbacks cb) throws AsioException;

    /**
     * Buffer half {@code half} (0 or 1) of the channel at {@code index} in the array given to
     * {@link #createBuffers}, as a segment of {@code byteSize} bytes. Valid until {@link #disposeBuffers};
     * callers on the callback thread should fetch it once and keep it.
     */
    MemorySegment buffer(int index, int half, long byteSize);

    void start() throws AsioException;

    void stop() throws AsioException;

    /** Releases the buffers; the callbacks never fire again once this has returned. */
    void disposeBuffers() throws AsioException;

    /** Tells the driver the half just filled is ready to play. Callback-thread safe; errors are ignored. */
    void outputReady();

    /** Opens the driver's own control panel, which may block until the user closes it. */
    void controlPanel() throws AsioException;

    /** Stops and disposes if needed (ignoring driver errors), then releases the driver. Idempotent. */
    @Override
    void close();
}
