package com.denpakun;

/** Coherent suppressed-carrier AM on a four-sample, 4 kHz digital square carrier. */
public final class SignalModulator {
    private SignalModulator() {
    }

    public static byte[] modulate(byte[] pcm) {
        return mix(pcm);
    }

    public static byte[] demodulate(byte[] pcm) {
        return mix(pcm);
    }

    public static double[] waveform(byte[] pcm) {
        int samples = sampleCount(pcm);
        double[] waveform = new double[samples];
        for (int sample = 0; sample < samples; sample++) {
            int offset = sample * Short.BYTES;
            short value = (short) ((pcm[offset] << 8) | (pcm[offset + 1] & 0xff));
            waveform[sample] = value / 32768.0;
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

    private static int sampleCount(byte[] pcm) {
        if (pcm.length % Short.BYTES != 0) {
            throw new IllegalArgumentException("El PCM debe contener muestras completas de 16 bits.");
        }
        return pcm.length / Short.BYTES;
    }
}
