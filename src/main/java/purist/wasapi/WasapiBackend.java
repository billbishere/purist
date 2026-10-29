package purist.wasapi;

import purist.win.ComException;

/** Where {@link WasapiSink} gets its audio clients: the real endpoints ({@link WasapiClient}) or a fake. */
interface WasapiBackend {

    /**
     * A new, uninitialised audio client on endpoint {@code endpointId}. Control thread.
     *
     * @throws ComException if the endpoint is not there or cannot be activated
     */
    AudioClient activate(String endpointId) throws ComException;

    /** Releases what the backend holds. Control thread. */
    void close();
}
