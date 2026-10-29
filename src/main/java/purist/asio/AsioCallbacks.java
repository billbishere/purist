package purist.asio;

/**
 * What the host implements to receive the driver's callbacks. Both methods run on a thread owned by the
 * driver, not on the ASIO control thread: they must not allocate, take locks or block, and anything they
 * throw is swallowed and counted (see {@link ComAsioDriver#callbackErrors()}).
 */
public interface AsioCallbacks {

    /**
     * The driver has finished playing one half of its double buffer and wants the other filled.
     *
     * @param half 0 or 1: the buffer half to fill now
     */
    void bufferSwitch(int half);

    /** The driver reports a condition the host may need to act on, typically by posting work elsewhere. */
    void event(AsioEvent e, int value);
}
