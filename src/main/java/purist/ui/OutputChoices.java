package purist.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import purist.app.Settings;
import purist.asio.AsioDriverInfo;
import purist.wasapi.WasapiDevice;

/**
 * The output list and its setting. {@code output.device} holds {@code asio:<driver name>} or
 * {@code wasapi:<endpoint id>}, with {@code output.deviceName} remembering a WASAPI device's friendly name for when
 * it is not connected. The pre-WASAPI key {@code output.driver} (an ASIO driver name) is migrated on first load.
 *
 * <p>DSD over WASAPI is DoP, which a DAC that does not decode it plays as full-scale noise, and nothing in WASAPI says
 * whether it does. So it is opt-in per endpoint: {@code wasapi.dop.<endpoint id>=true} (see {@link #dopEnabled}).
 */
public final class OutputChoices {

    static final String DEVICE = "output.device";
    static final String DEVICE_NAME = "output.deviceName";
    static final String LEGACY_DRIVER = "output.driver";
    /** {@code wasapi.dop.<endpoint id>=true} when the user says that DAC decodes DoP. */
    static final String DOP_PREFIX = "wasapi.dop.";

    /**
     * The list and its selection.
     *
     * @param choices  everything to show, in order
     * @param selected the one to open, or null when there is nothing at all
     */
    public record Resolved(List<OutputChoice> choices, OutputChoice selected) {
    }

    private OutputChoices() {
    }

    /** Every ASIO driver, then every WASAPI endpoint, each in the order given. */
    public static List<OutputChoice> list(List<AsioDriverInfo> asio, List<WasapiDevice> wasapi) {
        List<OutputChoice> l = new ArrayList<>(asio.size() + wasapi.size());
        for (AsioDriverInfo d : asio) {
            l.add(new OutputChoice(OutputChoice.Type.ASIO, d.name(), d.name(), true));
        }
        for (WasapiDevice d : wasapi) {
            l.add(new OutputChoice(OutputChoice.Type.WASAPI, d.id(), d.name(), true));
        }
        return List.copyOf(l);
    }

    /** Moves an old {@code output.driver} to {@code output.device} (unless that is already set) and drops it. */
    public static void migrate(Settings s) {
        String old = s.get(LEGACY_DRIVER, null);
        if (old == null) {
            return;
        }
        if (s.get(DEVICE, null) == null && !old.isEmpty()) {
            s.put(DEVICE, "asio:" + old);
        }
        s.put(LEGACY_DRIVER, null);
    }

    /** Remembers {@code c} as the output to open next time. */
    public static void remember(Settings s, OutputChoice c) {
        s.put(DEVICE, c.settingValue());
        s.put(DEVICE_NAME, c.type() == OutputChoice.Type.WASAPI ? c.name() : null);
    }

    /** True if the user said the WASAPI endpoint {@code endpointId} decodes DoP. Off unless set. */
    public static boolean dopEnabled(Settings s, String endpointId) {
        return "true".equals(s.get(DOP_PREFIX + endpointId, null));
    }

    /** Records whether the WASAPI endpoint {@code endpointId} decodes DoP; off removes the key. Not saved yet. */
    public static void setDopEnabled(Settings s, String endpointId, boolean on) {
        s.put(DOP_PREFIX + endpointId, on ? "true" : null);
    }

    /**
     * Picks the output: the remembered one if it is available; else the first ASIO driver; else the first WASAPI
     * device. No brand is preferred. A remembered WASAPI device whose id is not listed is looked for by its friendly
     * name (Windows gives a re-plugged or re-installed DAC a new endpoint id). One that is not connected at all stays
     * selected, added to the list as not connected, so a DAC that is merely switched off is not silently replaced
     * (playing then reports it unavailable and retries). A remembered ASIO driver that is no longer installed falls
     * back to the default.
     */
    public static Resolved resolve(Settings s, List<OutputChoice> available) {
        Optional<OutputChoice> saved = OutputChoice.parse(s.get(DEVICE, null), s.get(DEVICE_NAME, null));
        if (saved.isPresent()) {
            OutputChoice want = saved.get();
            for (OutputChoice c : available) {
                if (c.type() == want.type() && c.key().equals(want.key())) {
                    return new Resolved(available, c);
                }
            }
            if (want.type() == OutputChoice.Type.WASAPI) {
                for (OutputChoice c : available) {
                    if (c.type() == OutputChoice.Type.WASAPI && c.name().equals(want.name())) {
                        return new Resolved(available, c);
                    }
                }
                OutputChoice absent = new OutputChoice(want.type(), want.key(), want.name(), false);
                List<OutputChoice> withIt = new ArrayList<>(available);
                withIt.add(absent);
                return new Resolved(List.copyOf(withIt), absent);
            }
        }
        for (OutputChoice c : available) {
            if (c.type() == OutputChoice.Type.ASIO) {
                return new Resolved(available, c);
            }
        }
        return new Resolved(available, available.isEmpty() ? null : available.getFirst());
    }
}
