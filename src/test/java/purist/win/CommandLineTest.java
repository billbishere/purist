package purist.win;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class CommandLineTest {

    @Test
    void theProgramArgumentsAreTheLastEntriesOfTheWideArgv() {
        List<String> wide = List.of("C:\\jdk\\bin\\javaw.exe", "-Dx=1", "-jar", "purist.jar", "C:\\Music\\雨の街",
                "C:\\Music\\b.flac");
        assertEquals(List.of("C:\\Music\\雨の街", "C:\\Music\\b.flac"),
                CommandLine.lastOf(wide, new String[] {"C:\\Music\\???", "C:\\Music\\b.flac"}));
    }

    @Test
    void aShorterWideArgvFallsBackToTheJavaArguments() {
        assertEquals(List.of("a", "b"), CommandLine.lastOf(List.of("b"), new String[] {"a", "b"}));
    }

    @Test
    void noArgumentsReadNothingNative() {
        assertEquals(List.of(), CommandLine.args(new String[0]));
    }

    @Test
    void thisProcessCommandLineStartsWithTheJavaLauncher() {
        List<String> argv = CommandLine.wideArgv();
        assertFalse(argv.isEmpty());
        assertTrue(argv.get(0).toLowerCase().contains("java"), argv.get(0));
    }

    /** The real failure: a launched JVM sees {@code ???} in {@code main}'s arguments, the wide argv keeps the name. */
    @Test
    @Timeout(60)
    void aChildJvmReadsANonAnsiArgumentIntact() throws Exception {
        String name = "C:\\Music\\Kenji Arakawa - 雨の街 (Rain City)";
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Process p = new ProcessBuilder(java, "--enable-native-access=ALL-UNNAMED", "-cp",
                System.getProperty("java.class.path"), Probe.class.getName(), name)
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        assertTrue(p.waitFor(50, TimeUnit.SECONDS));
        assertEquals(0, p.exitValue(), out);
        assertEquals(name, out);
    }

    /** Prints its one argument, as {@link CommandLine#args} reads it, in UTF-8. */
    public static final class Probe {
        public static void main(String[] args) {
            byte[] utf8 = CommandLine.args(args).get(0).getBytes(StandardCharsets.UTF_8);
            System.out.write(utf8, 0, utf8.length);
            System.out.flush();
        }
    }
}
