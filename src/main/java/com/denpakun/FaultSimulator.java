package com.denpakun;

import java.util.concurrent.ThreadLocalRandom;

public final class FaultSimulator {
    public static final int MAX_LOSS_PERCENT = 50;
    public static final int MAX_JITTER_MILLIS = 150;

    private volatile double lossPercent;
    private volatile double jitterMillis;
    private volatile boolean corruptionEnabled;

    public void setLossPercent(double percent) {
        lossPercent = requireRange(percent, MAX_LOSS_PERCENT);
    }

    public void setJitterMillis(double millis) {
        jitterMillis = requireRange(millis, MAX_JITTER_MILLIS);
    }

    public void setCorruptionEnabled(boolean enabled) {
        corruptionEnabled = enabled;
    }

    Decision apply(byte[] packet, int payloadOffset) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        if (random.nextDouble(100) < lossPercent) {
            return new Decision(true, 0);
        }
        if (corruptionEnabled) {
            packet[random.nextInt(payloadOffset, packet.length)] ^= (byte) 0xFF;
        }
        long maximumDelay = Math.round(jitterMillis * 1_000_000);
        return new Decision(false, random.nextLong(maximumDelay + 1));
    }

    private static double requireRange(double value, int maximum) {
        if (!Double.isFinite(value) || value < 0 || value > maximum) {
            throw new IllegalArgumentException("El valor debe estar entre 0 y " + maximum);
        }
        return value;
    }

    record Decision(boolean dropped, long delayNanos) { }
}
