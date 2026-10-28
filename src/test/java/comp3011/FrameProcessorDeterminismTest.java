package comp3011;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The workers each own a separate effect chain, so two independently built chains must produce identical pixels for
 * the same frame number, in any order of processing.
 */
class FrameProcessorDeterminismTest {
    private static final int WIDTH = 96;
    private static final int HEIGHT = 64;
    private static final int CHANNELS = 3;

    private static final InfoVideo VIDEO = new InfoVideo(
            "test-movie", 100, WIDTH, HEIGHT, Frame.DEPTH_UBYTE, CHANNELS, WIDTH * CHANNELS, 25.0, 25, 40_000, 0);

    private static List<FrameProcessor> chain(List<FrameEffect> effects) throws Exception {
        List<FrameProcessor> chain = new ArrayList<>();
        int[] occurrences = new int[FrameEffect.values().length];
        for (FrameEffect effect : effects) {
            chain.add(effect.create(occurrences[effect.ordinal()]++));
        }
        for (FrameProcessor processor : chain) {
            processor.initialise(VIDEO);
        }
        return chain;
    }

    private static byte[] run(List<FrameProcessor> chain, int frameNumber) throws Exception {
        Frame frame = new Frame(WIDTH, HEIGHT, Frame.DEPTH_UBYTE, CHANNELS);
        ByteBuffer pixels = (ByteBuffer) frame.image[0];
        for (int i = 0; i < WIDTH * HEIGHT * CHANNELS; i++) {
            pixels.put(i, (byte) (i * 31 + frameNumber));
        }
        InfoFrame info = new InfoFrame(frameNumber, frameNumber * 40_000L);
        for (FrameProcessor processor : chain) {
            processor.process(frame, info);
        }
        byte[] result = new byte[WIDTH * HEIGHT * CHANNELS];
        for (int i = 0; i < result.length; i++) {
            result[i] = pixels.get(i);
        }
        return result;
    }

    @Test
    @DisplayName("two chains built from the same effects give identical output, whatever order frames arrive in")
    void separateChainsAgree() throws Exception {
        List<FrameEffect> effects = new ArrayList<>(Arrays.asList(FrameEffect.values()));
        effects.add(FrameEffect.DUST); // repeats must be seeded by occurrence, not by who built the chain
        effects.add(FrameEffect.FLICKER);

        List<FrameProcessor> inOrder = chain(effects);
        List<FrameProcessor> shuffled = chain(effects);

        int[] frames = { 0, 1, 2, 3, 40, 41 };
        byte[][] expected = new byte[frames.length][];
        for (int i = 0; i < frames.length; i++) {
            expected[i] = run(inOrder, frames[i]);
        }
        for (int i = frames.length - 1; i >= 0; i--) {
            assertArrayEquals(expected[i], run(shuffled, frames[i]), "frame " + frames[i]);
        }
    }
}
