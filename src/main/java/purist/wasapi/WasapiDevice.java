package purist.wasapi;

import java.util.Objects;

/**
 * An active WASAPI render endpoint.
 *
 * @param id   the endpoint id string ({@code IMMDevice::GetId}), stable across restarts; what settings remember
 * @param name the friendly name ({@code PKEY_Device_FriendlyName}), such as {@code "Speakers (2- TOPPING USB DAC)"}
 */
public record WasapiDevice(String id, String name) {

    public WasapiDevice {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
    }
}
