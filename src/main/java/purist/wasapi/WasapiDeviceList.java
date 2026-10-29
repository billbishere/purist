package purist.wasapi;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import purist.win.Ole32;

/** Finds the active WASAPI render endpoints ({@code EnumAudioEndpoints(eRender, DEVICE_STATE_ACTIVE)}). */
public final class WasapiDeviceList {

    private WasapiDeviceList() {
    }

    /**
     * Every active render endpoint with its id and friendly name, in Windows' order. Safe from any thread,
     * the EDT included: the enumeration runs on a short-lived thread of its own in the multithreaded apartment.
     *
     * @throws IllegalStateException if Windows could not list the endpoints
     */
    public static List<WasapiDevice> active() {
        CompletableFuture<List<WasapiDevice>> result = new CompletableFuture<>();
        Thread t = new Thread(() -> {
            try {
                Ole32.coInitializeMta();
            } catch (Throwable e) {
                result.completeExceptionally(e);
                return;
            }
            WasapiClient client = new WasapiClient();
            try {
                result.complete(List.copyOf(client.activeDevices()));
            } catch (Throwable e) {
                result.completeExceptionally(e);
            } finally {
                try {
                    client.close();
                } finally {
                    Ole32.coUninitialize();
                }
            }
        }, "wasapi-enumerate");
        t.setDaemon(true);
        t.start();
        try {
            return result.join();
        } catch (CompletionException e) {
            throw new IllegalStateException("Cannot list the WASAPI devices: " + e.getCause().getMessage(),
                    e.getCause());
        }
    }
}
