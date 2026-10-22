package comp3011;

import static comp3011.FrameEffect.*;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * This set of tests is to confirm the command line input for Part 1 of the assessment
 */
class CommandLineControllerTest {
    @TempDir
    Path tempDir;

    private String movie;

    @BeforeEach
    void createMovieFile() throws IOException {
        movie = Files.createFile(tempDir.resolve("movie.mp4")).toString();
    }

    /**
     * Helper to actually use the command line controller
     */
    private CommandLineController parse(String... args) {
        return new CommandLineController(args);
    }

    /**
     * Check for sucess across the board, used when something should succeed
     */
    private void assertEffects(List<FrameEffect> expected, String... args) {
        CommandLineController controller = parse(args);
        assertNull(controller.getErrorMessage());
        assertEquals(0, controller.getExitCode());
        assertTrue(controller.shouldLaunchApplication());
        assertEquals(expected, controller.getFrameEffects());
    }

    /**
     * Check for failure across the board, used when something should fail
     */
    private void assertFails(String... args) {
        CommandLineController controller = parse(args);
        assertNotNull(controller.getErrorMessage());
        assertNotEquals(0, controller.getExitCode());
        assertFalse(controller.shouldLaunchApplication());
    }

    @Test
    @DisplayName("This test checks for equivalence between different formats for arguments as defined by the assessment page.")
    void equivalentArgumentStylesTest() {
        List<FrameEffect> expected = List.of(JITTER, FLICKER, DUST, DUST, NUMBER);
        assertEffects(expected, "--jitter-frames", "--flicker-frames", "--dust-frames", "--dust-frames",
                "--number-frames", movie);
        assertEffects(expected, "-j", "-f", "-d", "-d", "-n", movie);
        assertEffects(expected, "-jfddn", movie);
        assertEffects(expected, "-jf", "--dust-frames", "-d", "--number-frames", movie);
    }

    @Test
    @DisplayName("This test confirms that all possible effects have both a short and long version in the command line")
    void effectArgumentFormatsTest() {
        for (FrameEffect effect : FrameEffect.values()) {
            assertEffects(List.of(effect), "-" + effect.getShortOption(), movie);
            assertEffects(List.of(effect), effect.getLongOption(), movie);
        }
    }

    @Test
    @DisplayName("This test confirms that order is maintained across more complicated inputs")
    void effectOrderTest() {
        assertEffects(List.of(NUMBER, SCRATCH, SCRATCH, NUMBER, FLICKER, BLACK_AND_WHITE, YELLOW, VIGNETTE, DUST,
                JITTER, MOTTLE, BLEED, PEPPER), "-nssnfwyvdjmbp", movie);
        assertEffects(List.of(PEPPER, BLEED, MOTTLE, JITTER, DUST, VIGNETTE, YELLOW, BLACK_AND_WHITE, FLICKER,
                SCRATCH, NUMBER), "-pbmjdv", movie, "--yellow-frames", "-w", "-f", "--scratch-frames", "-n");
    }

    @Test
    @DisplayName("This test confirms that the error handling returns correctly for invalid inputs")
    void invalidArgumentsExitNonZero() {
        assertFails("--number-frames");
        assertFails("--illegal-argument", movie);
        assertFails(movie, movie);
        assertFails(movie, "--Flicker-frames");
        assertFails(movie, "--B");
        assertFails("-sfbnz", movie);
        assertFails("-12", movie);
        assertFails(tempDir.resolve("missing.mp4").toString());
    }

    @Test
    @DisplayName("This test checks that unknown inputs nested inside chains of letters are still picked up")
    void unknownStackedLetterIsReported() {
        assertEquals("Unknown option: -z (in -sfbnz)", parse("-sfbnz", movie).getErrorMessage());
        assertEquals("Unknown option: -z", parse("-z", movie).getErrorMessage());
    }

    @Test
    @DisplayName("This test confirms asking for help does only that and does not open the app")
    void helpAloneDoesNotLaunchOrFail() {
        for (String help : List.of("--help", "-h")) {
            CommandLineController controller = parse(help);
            assertNull(controller.getErrorMessage());
            assertEquals(0, controller.getExitCode());
            assertFalse(controller.shouldLaunchApplication());
        }
    }
}