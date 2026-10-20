/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 *
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. <student name and student number insert here upon modification>
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.util.function.Supplier;

/**
 * The frame processors selectable from the command line, each mapped to the object they create
 *
 * <p>
 * {@link CommandLineController} records effects rather than processor instances, so that
 * processors are only created once, in the command-line order specified, 
 * by the {@link VideoPlayerModel}.
 * </p>
 */
public enum FrameEffect {
    NUMBER('n', "--number-frames", FrameNumberer::new),
    SCRATCH('s', "--scratch-frames", FrameScratcher::new),
    FLICKER('f', "--flicker-frames", FrameFlickerer::new),
    BLACK_AND_WHITE('w', "--black-and-white", FrameBlackAndWhiter::new),
    YELLOW('y', "--yellow-frames", FrameYellower::new),
    VIGNETTE('v', "--vignette-frames", FrameVignetter::new),
    DUST('d', "--dust-frames", FrameDuster::new),
    JITTER('j', "--jitter-frames", FrameJitterer::new),
    MOTTLE('m', "--mottle-frames", FrameMottler::new),
    BLEED('b', "--bleed-frames", FrameBleeder::new),
    PEPPER('p', "--pepper-frames", FramePepperer::new);

    private final char shortOption;
    private final String longOption;
    private final Supplier<FrameProcessor> factory;

    FrameEffect(char shortOption, String longOption, Supplier<FrameProcessor> factory) {
        this.shortOption = shortOption;
        this.longOption = longOption;
        this.factory = factory;
    }

    public char getShortOption() {
        return shortOption;
    }

    public String getLongOption() {
        return longOption;
    }

    public FrameProcessor create() {
        return factory.get();
    }

    public static FrameEffect fromShort(char option) {
        for (FrameEffect effect : values()) {
            if (effect.shortOption == option) {
                return effect;
            }
        }
        return null;
    }

    public static FrameEffect fromLong(String option) {
        for (FrameEffect effect : values()) {
            if (effect.longOption.equals(option)) {
                return effect;
            }
        }
        return null;
    }
}
