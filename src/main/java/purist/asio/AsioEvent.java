package purist.asio;

/** Something the driver asks of the host from its own thread, delivered through {@link AsioCallbacks#event}. */
public enum AsioEvent {
    /** {@code kAsioResetRequest}: the driver wants to be torn down and re-created. */
    RESET_REQUEST,
    /** {@code kAsioBufferSizeChange}: the value is the new buffer size in samples. */
    BUFFER_SIZE_CHANGE,
    /** {@code kAsioResyncRequest}. */
    RESYNC_REQUEST,
    /** {@code kAsioLatenciesChanged}. */
    LATENCIES_CHANGED,
    /** {@code sampleRateDidChange}: the value is the new rate in Hz, rounded to an integer. */
    SAMPLE_RATE_CHANGED,
    /** {@code kAsioOverload}: the driver could not keep up. */
    OVERLOAD
}
