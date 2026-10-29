package purist.app;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import javax.swing.SwingUtilities;
import purist.decode.DecoderRegistry;
import purist.ui.MainWindow;
import purist.ui.theme.Fonts;
import purist.ui.theme.PuristTheme;
import purist.ui.theme.Skin;
import purist.win.CommandLine;

/** Purist entry point. */
public final class Main {

    private static final String VERSION_RESOURCE = "/purist/version.properties";

    private Main() {
    }

    /** The application version, read from the Maven-filtered {@code purist/version.properties}. */
    public static String version() {
        try (InputStream in = Main.class.getResourceAsStream(VERSION_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + VERSION_RESOURCE);
            }
            Properties props = new Properties();
            props.load(in);
            String version = props.getProperty("version");
            if (version == null) {
                throw new IllegalStateException("No 'version' key in " + VERSION_RESOURCE);
            }
            return version;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read " + VERSION_RESOURCE, e);
        }
    }

    public static void main(String[] args) throws Exception {
        Profile profile = Profile.current();
        AppLog.init(profile.log());
        Logger log = AppLog.logger(Main.class);
        log.info("Purist " + version() + " starting, profile " + profile.dir());
        Settings settings = Settings.load(profile.settings());
        Fonts.register();
        AppLibrary library = AppLibrary.open(profile, DecoderRegistry.standard());
        List<Path> files = paths(CommandLine.args(args), log);
        SwingUtilities.invokeLater(() -> {
            PuristTheme.setup(Skin.fromSetting(settings.get(Skin.SETTING, null)));
            MainWindow.open(settings, profile, library, files);
            library.start();
        });
    }

    /** The files and folders named on the command line; one that is not a valid path is logged and left out. */
    static List<Path> paths(List<String> args, Logger log) {
        List<Path> out = new ArrayList<>();
        for (String a : args) {
            try {
                out.add(Path.of(a));
            } catch (InvalidPathException e) {
                log.warning("Not a path, ignored: " + a);
            }
        }
        return out;
    }
}
