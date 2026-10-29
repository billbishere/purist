package purist.wasapi;

/**
 * {@code IAudioRenderClient}. {@link #getBuffer} and {@link #releaseBuffer} run on the render thread (and on the
 * control thread to prefill before {@code Start}, while the render thread is idle); they allocate nothing and
 * return the HRESULT rather than throwing. {@link #release} runs on the control thread once the render thread
 * has ended.
 */
interface RenderClient {

    /** {@code GetBuffer(frames, &p)}; on success {@link #buffer()} is {@code p}. */
    int getBuffer(int frames);

    /** The address the last successful {@link #getBuffer} returned. */
    long buffer();

    /** {@code ReleaseBuffer(frames, flags)}. */
    int releaseBuffer(int frames, int flags);

    void release();
}
