package purist.asio;

/**
 * The driver's buffer sizes in samples per channel, as {@code getBufferSize} reports them. A
 * {@code granularity} of -1 means the allowed sizes between {@code min} and {@code max} are powers of
 * two; otherwise it is the step in samples.
 */
public record BufferSize(int min, int max, int preferred, int granularity) {
}
