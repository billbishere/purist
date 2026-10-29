package purist.wasapi;

import purist.win.ComException;

/**
 * The {@code IAudioClient} calls {@link WasapiSink} makes, always on its control thread, and only ever in
 * exclusive mode: there is no shared-mode method here, so nothing can fall back to the Windows mixer. The real
 * one is in {@link WasapiClient}; tests use a fake.
 */
interface AudioClient {

    /** {@code IsFormatSupported(AUDCLNT_SHAREMODE_EXCLUSIVE, f, NULL)}; the HRESULT, S_OK meaning yes. */
    int isFormatSupported(WaveFormat f);

    /** The default device period ({@code GetDevicePeriod}), in 100 ns units. */
    long defaultDevicePeriod() throws ComException;

    /**
     * {@code Initialize(AUDCLNT_SHAREMODE_EXCLUSIVE, AUDCLNT_STREAMFLAGS_EVENTCALLBACK, period, period, f, NULL)};
     * the HRESULT.
     */
    int initialize(WaveFormat f, long periodHns);

    /** {@code GetBufferSize}, in frames. After an unaligned {@code Initialize} it is the next aligned size. */
    int bufferSize() throws ComException;

    void setEventHandle(long event) throws ComException;

    /** {@code GetService(IID_IAudioRenderClient)}. */
    RenderClient renderClient() throws ComException;

    void start() throws ComException;

    void stop() throws ComException;

    void reset() throws ComException;

    int currentPadding() throws ComException;

    /** {@code IUnknown::Release}; the client is not used again. */
    void release();
}
