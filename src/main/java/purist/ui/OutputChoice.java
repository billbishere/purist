package purist.ui;

import java.util.Objects;
import java.util.Optional;

/**
 * One entry of the Settings → Output list: an ASIO driver or a WASAPI endpoint.
 *
 * @param type    which engine opens it
 * @param key     what identifies it: the ASIO driver's registry name, or the WASAPI endpoint id
 * @param name    the name shown: the driver name, or the endpoint's friendly name
 * @param present false for a remembered WASAPI device that is not connected right now
 */
public record OutputChoice(Type type, String key, String name, boolean present) {

    public enum Type { ASIO, WASAPI }

    public OutputChoice {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(name, "name");
    }

    /** The {@code output.device} value: {@code asio:<driver name>} or {@code wasapi:<endpoint id>}. */
    public String settingValue() {
        return (type == Type.ASIO ? "asio:" : "wasapi:") + key;
    }

    /** {@code "ASIO · <name>"} or {@code "WASAPI · <name>"}, marked when the device is not connected. */
    public String label() {
        return (type == Type.ASIO ? "ASIO · " : "WASAPI · ") + name + (present ? "" : " (not connected)");
    }

    /**
     * The choice an {@code output.device} value names, or empty for an unknown prefix or an empty key.
     *
     * @param name the remembered display name; an ASIO driver's name is its key
     */
    public static Optional<OutputChoice> parse(String value, String name) {
        if (value == null) {
            return Optional.empty();
        }
        Type type;
        String key;
        if (value.startsWith("asio:")) {
            type = Type.ASIO;
            key = value.substring(5);
        } else if (value.startsWith("wasapi:")) {
            type = Type.WASAPI;
            key = value.substring(7);
        } else {
            return Optional.empty();
        }
        if (key.isEmpty()) {
            return Optional.empty();
        }
        String shown = type == Type.ASIO ? key : name == null || name.isBlank() ? key : name;
        return Optional.of(new OutputChoice(type, key, shown, true));
    }
}
