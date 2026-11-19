package com.denpakun;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

public final class SstvCodec {
    public static final int WIDTH = 160;
    public static final int HEIGHT = 120;
    public static final int FRAME_BYTES = 640;

    private static final int SAMPLE_RATE = 16_000;
    private static final int FRAME_SAMPLES = FRAME_BYTES / Short.BYTES;
    private static final int PIXEL_SAMPLES = 10;
    private static final int PIXELS_PER_FRAME = FRAME_SAMPLES / PIXEL_SAMPLES;
    private static final int LINE_FRAMES = 1 + WIDTH / PIXELS_PER_FRAME;
    private static final int VERTICAL_SYNC_FRAMES = 2;
    public static final int IMAGE_FRAMES = VERTICAL_SYNC_FRAMES + HEIGHT * LINE_FRAMES;
    private static final double SYNC_HZ = 1_200;
    private static final double BLACK_HZ = 1_500;
    private static final double WHITE_HZ = 2_300;
    private static final double TONE_TOLERANCE_HZ = 60;
    private static final int OPAQUE_BLACK = 0xff000000;
    private static final int LOSS_LIGHT = 0xffff4ca8;
    private static final int LOSS_DARK = 0xff75254f;
    private static final int MISSING_SYNC = 0xffffc45a;
    private static final int SYNC_MARKER_WIDTH = 8;
    private static final int AMPLITUDE = 24_000;

    private SstvCodec() { }

    public static BufferedImage resize(BufferedImage source) {
        Objects.requireNonNull(source, "source");
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(source, 0, 0, WIDTH, HEIGHT, null);
        } finally {
            graphics.dispose();
        }
        for (int row = 0; row < HEIGHT; row++) {
            for (int column = 0; column < WIDTH; column++) {
                int rgb = image.getRGB(column, row);
                int red = (rgb >>> 16) & 0xff;
                int green = (rgb >>> 8) & 0xff;
                int blue = rgb & 0xff;
                int gray = (299 * red + 587 * green + 114 * blue + 500) / 1_000;
                image.setRGB(column, row, grayArgb(gray));
            }
        }
        return image;
    }

    /** Encodes the Denpa-Kun SSTV format as 20 ms mono PCM blocks. */
    public static List<byte[]> encode(BufferedImage source) {
        BufferedImage image = resize(source);
        List<byte[]> frames = new ArrayList<>(IMAGE_FRAMES);
        ToneWriter tones = new ToneWriter();
        for (int frame = 0; frame < VERTICAL_SYNC_FRAMES; frame++) {
            frames.add(tones.syncFrame());
        }
        for (int row = 0; row < HEIGHT; row++) {
            frames.add(tones.syncFrame());
            for (int start = 0; start < WIDTH; start += PIXELS_PER_FRAME) {
                ByteBuffer pcm = ByteBuffer.allocate(FRAME_BYTES);
                for (int column = start; column < start + PIXELS_PER_FRAME; column++) {
                    int gray = image.getRGB(column, row) & 0xff;
                    double frequency = BLACK_HZ + (WHITE_HZ - BLACK_HZ) * gray / 255;
                    tones.write(pcm, frequency, PIXEL_SAMPLES);
                }
                frames.add(pcm.array());
            }
        }
        return List.copyOf(frames);
    }

    public record DecodedLine(int row, int[] argb, int decodedPixels, int unavailablePixels,
                              boolean missingSync) { }

    /** Receives blocks by their original position so lost blocks never shift the scan. */
    public static final class Decoder {
        private final int[][] lines = new int[HEIGHT][];
        private final int[] decodedPixels = new int[HEIGHT];
        private final BitSet received = new BitSet(IMAGE_FRAMES);
        private final BitSet unavailable = new BitSet(IMAGE_FRAMES);
        private final BitSet rejectedLines = new BitSet(HEIGHT);
        private boolean rejectedImage;

        public void accept(int frameIndex, byte[] pcm, Consumer<DecodedLine> onLine) {
            Objects.requireNonNull(pcm, "pcm");
            Objects.requireNonNull(onLine, "onLine");
            if (rejectedImage || frameIndex < 0 || frameIndex >= IMAGE_FRAMES
                    || pcm.length != FRAME_BYTES || received.get(frameIndex) || unavailable.get(frameIndex)) {
                return;
            }
            received.set(frameIndex);
            if (frameIndex < VERTICAL_SYNC_FRAMES) {
                if (!isSync(pcm)) {
                    rejectedImage = true;
                    for (int row = 0; row < HEIGHT; row++) {
                        clearLine(row, onLine);
                    }
                }
                return;
            }

            int scanFrame = frameIndex - VERTICAL_SYNC_FRAMES;
            int row = scanFrame / LINE_FRAMES;
            int lineFrame = scanFrame % LINE_FRAMES;
            if (lineFrame == 0) {
                if (!isSync(pcm)) {
                    rejectedLines.set(row);
                    clearLine(row, onLine);
                }
            } else if (!rejectedLines.get(row)) {
                decodePixels(row, (lineFrame - 1) * PIXELS_PER_FRAME, pcm, onLine);
            }
        }

        /** Marks a confirmed gap using the same zero-based scan positions as accept. */
        public void markUnavailable(int firstFrameIndex, int frameCount, Consumer<DecodedLine> onLine) {
            Objects.requireNonNull(onLine, "onLine");
            if (firstFrameIndex < 0 || firstFrameIndex >= IMAGE_FRAMES || frameCount <= 0) {
                return;
            }
            BitSet affectedRows = new BitSet(HEIGHT);
            int end = firstFrameIndex + Math.min(frameCount, IMAGE_FRAMES - firstFrameIndex);
            for (int frame = firstFrameIndex; frame < end; frame++) {
                if (!received.get(frame) && !unavailable.get(frame)) {
                    unavailable.set(frame);
                    int row = Math.max(0, frame - VERTICAL_SYNC_FRAMES) / LINE_FRAMES;
                    affectedRows.set(row);
                }
            }
            for (int row = affectedRows.nextSetBit(0); row >= 0; row = affectedRows.nextSetBit(row + 1)) {
                onLine.accept(snapshot(row));
            }
        }

        private void decodePixels(int row, int start, byte[] pcm, Consumer<DecodedLine> onLine) {
            int[] line = lines[row];
            if (line == null) {
                line = emptyLine();
                lines[row] = line;
            }
            for (int pixel = 0; pixel < PIXELS_PER_FRAME; pixel++) {
                double frequency = estimateFrequency(pcm, pixel * PIXEL_SAMPLES, PIXEL_SAMPLES);
                if (frequency >= BLACK_HZ - TONE_TOLERANCE_HZ
                        && frequency <= WHITE_HZ + TONE_TOLERANCE_HZ) {
                    int gray = (int) Math.round(255 * (frequency - BLACK_HZ) / (WHITE_HZ - BLACK_HZ));
                    line[start + pixel] = grayArgb(Math.clamp(gray, 0, 255));
                    decodedPixels[row]++;
                }
            }
            onLine.accept(snapshot(row));
        }

        private void clearLine(int row, Consumer<DecodedLine> onLine) {
            if (lines[row] != null) {
                lines[row] = null;
                decodedPixels[row] = 0;
                onLine.accept(snapshot(row));
            }
        }

        private DecodedLine snapshot(int row) {
            int[] display = lines[row] == null ? emptyLine() : lines[row].clone();
            int syncFrame = VERTICAL_SYNC_FRAMES + row * LINE_FRAMES;
            boolean missingSync = unavailable.get(syncFrame);
            if (missingSync) {
                Arrays.fill(display, WIDTH - SYNC_MARKER_WIDTH, WIDTH, MISSING_SYNC);
            }
            if (row == 0) {
                for (int frame = 0; frame < VERTICAL_SYNC_FRAMES; frame++) {
                    if (unavailable.get(frame)) {
                        int start = frame * SYNC_MARKER_WIDTH;
                        Arrays.fill(display, start, start + SYNC_MARKER_WIDTH, MISSING_SYNC);
                        missingSync = true;
                    }
                }
            }

            int unavailablePixels = 0;
            for (int frame = 1; frame < LINE_FRAMES; frame++) {
                if (unavailable.get(syncFrame + frame)) {
                    int start = (frame - 1) * PIXELS_PER_FRAME;
                    for (int x = start; x < start + PIXELS_PER_FRAME; x++) {
                        display[x] = (x / 4 + row) % 2 == 0 ? LOSS_LIGHT : LOSS_DARK;
                    }
                    unavailablePixels += PIXELS_PER_FRAME;
                }
            }
            return new DecodedLine(row, display, decodedPixels[row], unavailablePixels, missingSync);
        }
    }

    private static boolean isSync(byte[] pcm) {
        return Math.abs(estimateFrequency(pcm, 0, FRAME_SAMPLES) - SYNC_HZ) <= TONE_TOLERANCE_HZ;
    }

    private static double estimateFrequency(byte[] pcm, int start, int length) {
        // A sinusoid satisfies x[n-1] + x[n+1] = 2 cos(omega) x[n].
        // Fit that recurrence across the window so its initial phase does not matter.
        int previous = sample(pcm, start);
        int current = sample(pcm, start + 1);
        long correlation = 0;
        long energy = 0;
        for (int index = start + 2; index < start + length; index++) {
            int next = sample(pcm, index);
            correlation += (long) current * (previous + next);
            energy += 2L * current * current;
            previous = current;
            current = next;
        }
        if (energy == 0) {
            return Double.NaN;
        }
        double cosine = Math.clamp((double) correlation / energy, -1.0, 1.0);
        return Math.acos(cosine) * SAMPLE_RATE / Math.TAU;
    }

    private static short sample(byte[] pcm, int index) {
        int offset = index * Short.BYTES;
        return (short) ((pcm[offset] << 8) | (pcm[offset + 1] & 0xff));
    }

    private static int grayArgb(int gray) {
        return OPAQUE_BLACK | gray * 0x010101;
    }

    private static int[] emptyLine() {
        int[] line = new int[WIDTH];
        Arrays.fill(line, OPAQUE_BLACK);
        return line;
    }

    private static final class ToneWriter {
        private double phase;

        private byte[] syncFrame() {
            ByteBuffer pcm = ByteBuffer.allocate(FRAME_BYTES);
            write(pcm, SYNC_HZ, FRAME_SAMPLES);
            return pcm.array();
        }

        private void write(ByteBuffer pcm, double frequency, int sampleCount) {
            double step = Math.TAU * frequency / SAMPLE_RATE;
            for (int sample = 0; sample < sampleCount; sample++) {
                pcm.putShort((short) Math.round(AMPLITUDE * Math.sin(phase)));
                phase += step;
                if (phase >= Math.TAU) {
                    phase -= Math.TAU;
                }
            }
        }
    }
}
