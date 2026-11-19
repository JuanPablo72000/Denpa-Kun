package com.denpakun;

/** Voice modulation and demodulation at 16 kHz with a 4 kHz carrier. */
public final class SignalModulator {
    private static final int CARRIER_HZ = 4_000;

    public enum Mode {
        DSB_SC(0, "AM · Portadora suprimida", "Doble banda lateral, 4 kHz"),
        AM(1, "AM · Portadora completa", "Doble banda lateral con portadora, 4 kHz"),
        SSB_USB(2, "SSB · Banda lateral superior", "Banda lateral superior, 4 kHz");

        private final int wireId;
        private final String displayName;
        private final String description;

        Mode(int wireId, String displayName, String description) {
            this.wireId = wireId;
            this.displayName = displayName;
            this.description = description;
        }

        public int wireId() {
            return wireId;
        }

        public static Mode fromWireId(int wireId) {
            return switch (wireId) {
                case 0 -> DSB_SC;
                case 1 -> AM;
                case 2 -> SSB_USB;
                default -> null;
            };
        }

        public String displayName() {
            return displayName;
        }

        public String description() {
            return description;
        }

        @Override
        public String toString() {
            return displayName;
        }
    }

    private SignalModulator() {
    }

    public static byte[] modulate(byte[] pcm) {
        return modulate(pcm, Mode.DSB_SC);
    }

    public static byte[] modulate(byte[] pcm, Mode mode) {
        return switch (mode) {
            case DSB_SC -> mix(pcm);
            case AM -> mix(applyEnvelope(pcm, true));
            case SSB_USB -> UpperSideband.translate(pcm, true);
        };
    }

    public static byte[] demodulate(byte[] pcm) {
        return demodulate(pcm, Mode.DSB_SC);
    }

    public static byte[] demodulate(byte[] pcm, Mode mode) {
        return switch (mode) {
            case DSB_SC -> mix(pcm);
            case AM -> applyEnvelope(mix(pcm), false);
            case SSB_USB -> UpperSideband.translate(pcm, false);
        };
    }

    public static double[] waveform(byte[] pcm) {
        int samples = sampleCount(pcm);
        double[] waveform = new double[samples];
        for (int sample = 0; sample < samples; sample++) {
            waveform[sample] = readSample(pcm, sample) / 32768.0;
        }
        return waveform;
    }

    private static byte[] mix(byte[] pcm) {
        int samples = sampleCount(pcm);
        byte[] mixed = pcm.clone();
        // Each 320-sample frame starts on a carrier period. Complementing a signed
        // sample reflects around the midpoint of the PCM range, including -32768.
        for (int sample = 0; sample < samples; sample++) {
            if ((sample & 2) != 0) {
                int offset = sample * Short.BYTES;
                mixed[offset] = (byte) ~mixed[offset];
                mixed[offset + 1] = (byte) ~mixed[offset + 1];
            }
        }
        return mixed;
    }

    private static byte[] applyEnvelope(byte[] pcm, boolean encode) {
        int samples = sampleCount(pcm);
        byte[] result = new byte[pcm.length];
        // A half-scale carrier keeps both ends of the signed input in a positive envelope.
        for (int sample = 0; sample < samples; sample++) {
            int value = readSample(pcm, sample);
            int converted = encode ? (value - Short.MIN_VALUE) / 2 : value * 2 + Short.MIN_VALUE;
            writeSample(result, sample, converted);
        }
        return result;
    }

    private static short readSample(byte[] pcm, int sample) {
        int offset = sample * Short.BYTES;
        return (short) ((pcm[offset] << 8) | (pcm[offset + 1] & 0xff));
    }

    private static void writeSample(byte[] pcm, int sample, int value) {
        int offset = sample * Short.BYTES;
        int clipped = Math.clamp(value, Short.MIN_VALUE, Short.MAX_VALUE);
        pcm[offset] = (byte) (clipped >> 8);
        pcm[offset + 1] = (byte) clipped;
    }

    private static int sampleCount(byte[] pcm) {
        if (pcm.length % Short.BYTES != 0) {
            throw new IllegalArgumentException("El PCM debe contener muestras completas de 16 bits.");
        }
        return pcm.length / Short.BYTES;
    }

    private static final class UpperSideband {
        private static final int SAMPLES = AudioEngine.FRAME_BYTES / Short.BYTES;
        private static final int BIN_HZ = AudioEngine.SAMPLE_RATE / SAMPLES;
        private static final int CARRIER_BIN = CARRIER_HZ / BIN_HZ;
        private static final int VOICE_BINS = 3_400 / BIN_HZ;
        private static final double GAIN = 0.5;
        private static final double[][] VOICE_COSINES = basis(0, false);
        private static final double[][] VOICE_SINES = basis(0, true);
        private static final double[][] SIDEBAND_COSINES = basis(CARRIER_BIN, false);
        private static final double[][] SIDEBAND_SINES = basis(CARRIER_BIN, true);

        private static byte[] translate(byte[] pcm, boolean encode) {
            if (sampleCount(pcm) != SAMPLES) {
                throw new IllegalArgumentException("SSB requiere bloques PCM de 320 muestras.");
            }

            double[][] sourceCosines = encode ? VOICE_COSINES : SIDEBAND_COSINES;
            double[][] sourceSines = encode ? VOICE_SINES : SIDEBAND_SINES;
            double[][] targetCosines = encode ? SIDEBAND_COSINES : VOICE_COSINES;
            double[][] targetSines = encode ? SIDEBAND_SINES : VOICE_SINES;
            double scale = 2.0 / SAMPLES * (encode ? GAIN : 1.0 / GAIN);
            double[] input = new double[SAMPLES];
            double[] output = new double[SAMPLES];
            for (int sample = 0; sample < SAMPLES; sample++) {
                input[sample] = readSample(pcm, sample);
            }

            // Each frame spans 20 ms, so its Fourier bins are 50 Hz apart. Move only
            // the positive-frequency speech bins from 50–3400 Hz to 4050–7400 Hz.
            for (int bin = 0; bin < VOICE_BINS; bin++) {
                double cosine = 0;
                double sine = 0;
                for (int sample = 0; sample < SAMPLES; sample++) {
                    cosine += input[sample] * sourceCosines[bin][sample];
                    sine += input[sample] * sourceSines[bin][sample];
                }
                cosine *= scale;
                sine *= scale;
                for (int sample = 0; sample < SAMPLES; sample++) {
                    output[sample] += cosine * targetCosines[bin][sample] + sine * targetSines[bin][sample];
                }
            }

            byte[] translated = new byte[pcm.length];
            for (int sample = 0; sample < SAMPLES; sample++) {
                writeSample(translated, sample, (int) Math.round(output[sample]));
            }
            return translated;
        }

        private static double[][] basis(int offset, boolean sine) {
            double[][] basis = new double[VOICE_BINS][SAMPLES];
            for (int bin = 0; bin < VOICE_BINS; bin++) {
                double step = 2 * Math.PI * (offset + bin + 1) / SAMPLES;
                for (int sample = 0; sample < SAMPLES; sample++) {
                    double phase = step * sample;
                    basis[bin][sample] = sine ? Math.sin(phase) : Math.cos(phase);
                }
            }
            return basis;
        }
    }
}
