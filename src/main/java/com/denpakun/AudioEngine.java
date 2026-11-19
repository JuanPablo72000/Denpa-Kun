package com.denpakun;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BiConsumer;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;

public final class AudioEngine implements AutoCloseable {
    public static final int SAMPLE_RATE = 16_000;
    public static final int FRAME_MILLIS = 20;
    public static final int FRAME_BYTES = SAMPLE_RATE * FRAME_MILLIS / 1_000 * Short.BYTES;
    public static final AudioFormat PCM_FORMAT = new AudioFormat(SAMPLE_RATE, 16, 1, true, true);

    private static final int DEVICE_BUFFER_FRAMES = 3;
    private static final int CAPTURE_BACKLOG = 3;
    private static final int PLAYBACK_BACKLOG = 12;
    private static final long DEVICE_POLL_MILLIS = 1;
    private static final long CAPTURE_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final List<AudioFormat> DEVICE_FORMATS = List.of(
            PCM_FORMAT,
            new AudioFormat(SAMPLE_RATE, 16, 1, true, false),
            new AudioFormat(SAMPLE_RATE, 16, 2, true, false),
            new AudioFormat(48_000, 16, 1, true, false),
            new AudioFormat(48_000, 16, 2, true, false),
            new AudioFormat(44_100, 16, 1, true, false),
            new AudioFormat(44_100, 16, 2, true, false));

    public enum Device {
        MICROPHONE, SPEAKERS
    }

    public record CapturedFrame(byte[] pcm, long timestampNanos) {
    }

    private enum Mode {
        IDLE, CAPTURE, PLAYBACK, CLOSED
    }

    // A new request invalidates any earlier native line open still in progress.
    private record Request(Mode mode) {
    }

    private final Object lock = new Object();
    private final ArrayDeque<CapturedFrame> captured = new ArrayDeque<>();
    private final ArrayDeque<byte[]> toPlay = new ArrayDeque<>();
    private final BiConsumer<Device, String> onError;
    private final Thread worker;
    private volatile Request request = new Request(Mode.IDLE);
    private DataLine activeLine;
    private boolean finishingPlayback;

    public AudioEngine(BiConsumer<Device, String> onError) {
        this.onError = Objects.requireNonNull(onError);
        worker = Thread.ofPlatform().daemon().name("denpakun-audio").start(this::run);
    }

    public void startCapture() {
        start(Mode.CAPTURE);
    }

    public void stopCapture() {
        stop(Mode.CAPTURE);
    }

    public CapturedFrame pollCapture() {
        synchronized (lock) {
            return captured.pollFirst();
        }
    }

    public void startPlayback() {
        start(Mode.PLAYBACK);
    }

    public void play(byte[] pcm) {
        if (pcm.length != FRAME_BYTES) {
            throw new IllegalArgumentException("Cada bloque PCM debe contener " + FRAME_BYTES + " bytes.");
        }
        synchronized (lock) {
            if (request.mode() != Mode.PLAYBACK || finishingPlayback) {
                return;
            }
            if (toPlay.size() == PLAYBACK_BACKLOG) {
                toPlay.removeFirst();
            }
            toPlay.addLast(pcm.clone());
        }
        LockSupport.unpark(worker);
    }

    public void finishPlayback() {
        synchronized (lock) {
            if (request.mode() != Mode.PLAYBACK) {
                return;
            }
            finishingPlayback = true;
        }
        LockSupport.unpark(worker);
    }

    public void stopPlayback() {
        stop(Mode.PLAYBACK);
    }

    @Override
    public void close() {
        synchronized (lock) {
            changeMode(Mode.CLOSED);
        }
    }

    private void start(Mode mode) {
        synchronized (lock) {
            if (request.mode() != Mode.CLOSED && (request.mode() != mode || finishingPlayback)) {
                changeMode(mode);
            }
        }
    }

    private void stop(Mode mode) {
        synchronized (lock) {
            if (request.mode() == mode) {
                changeMode(Mode.IDLE);
            }
        }
    }

    // Called with lock held. Closing here prevents microphone use after stopCapture returns.
    private void changeMode(Mode mode) {
        request = new Request(mode);
        captured.clear();
        toPlay.clear();
        finishingPlayback = false;
        if (activeLine != null) {
            activeLine.close();
            activeLine = null;
        }
        LockSupport.unpark(worker);
    }

    private void run() {
        while (request.mode() != Mode.CLOSED) {
            Request current = request;
            switch (current.mode()) {
                case IDLE -> LockSupport.park();
                case CLOSED -> {
                    return;
                }
                case CAPTURE, PLAYBACK -> process(current);
            }
        }
    }

    private void process(Request current) {
        try (DataLine line = openLine(current)) {
            if (line == null) {
                return;
            }
            synchronized (lock) {
                if (request != current) {
                    return;
                }
                activeLine = line;
                line.start();
            }
            if (line instanceof TargetDataLine input) {
                capture(input, current);
            } else {
                playback((SourceDataLine) line, current);
            }
        } catch (LineUnavailableException | InterruptedException | RuntimeException error) {
            if (returnToIdle(current)) {
                Device device = current.mode() == Mode.CAPTURE ? Device.MICROPHONE : Device.SPEAKERS;
                String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                onError.accept(device, detail);
            }
        }
    }

    private boolean returnToIdle(Request current) {
        synchronized (lock) {
            if (request != current) {
                return false;
            }
            changeMode(Mode.IDLE);
            return true;
        }
    }

    private DataLine openLine(Request current) throws LineUnavailableException {
        Class<? extends DataLine> type = current.mode() == Mode.CAPTURE
                ? TargetDataLine.class : SourceDataLine.class;
        Exception lastError = null;
        for (AudioFormat format : DEVICE_FORMATS) {
            if (request != current) {
                return null;
            }
            DataLine line = null;
            try {
                line = (DataLine) AudioSystem.getLine(new DataLine.Info(type, format));
                int bufferBytes = frameBytes(format) * DEVICE_BUFFER_FRAMES;
                if (line instanceof TargetDataLine input) {
                    input.open(format, bufferBytes);
                } else {
                    ((SourceDataLine) line).open(format, bufferBytes);
                }
                return line;
            } catch (LineUnavailableException | IllegalArgumentException error) {
                if (line != null) {
                    line.close();
                }
                lastError = error;
            }
        }
        var unavailable = new LineUnavailableException("No se pudo abrir un dispositivo de audio PCM compatible.");
        unavailable.initCause(lastError);
        throw unavailable;
    }

    private void capture(TargetDataLine line, Request current) throws InterruptedException {
        AudioFormat format = line.getFormat();
        byte[] buffer = new byte[frameBytes(format)];
        int offset = 0;
        long lastRead = System.nanoTime();
        while (request == current) {
            int count = availableBytes(line, buffer.length - offset);
            if (count == 0) {
                if (System.nanoTime() - lastRead >= CAPTURE_TIMEOUT_NANOS) {
                    throw new IllegalStateException("El micrófono dejó de entregar muestras de audio.");
                }
                Thread.sleep(DEVICE_POLL_MILLIS);
                continue;
            }
            int read = line.read(buffer, offset, count);
            if (read == 0) {
                Thread.sleep(DEVICE_POLL_MILLIS);
                continue;
            }
            offset += read;
            lastRead = System.nanoTime();
            if (offset == buffer.length) {
                CapturedFrame frame = new CapturedFrame(convert(buffer, format, PCM_FORMAT), lastRead);
                synchronized (lock) {
                    if (request == current) {
                        if (captured.size() == CAPTURE_BACKLOG) {
                            captured.removeFirst();
                        }
                        captured.addLast(frame);
                    }
                }
                offset = 0;
            }
        }
    }

    private void playback(SourceDataLine line, Request current) throws InterruptedException {
        while (request == current) {
            byte[] pcm;
            boolean drain;
            synchronized (lock) {
                if (request != current) {
                    return;
                }
                pcm = toPlay.pollFirst();
                drain = finishingPlayback && pcm == null;
            }
            if (drain) {
                line.drain();
                returnToIdle(current);
                return;
            }
            if (pcm == null) {
                LockSupport.park();
            } else {
                write(line, convert(pcm, PCM_FORMAT, line.getFormat()), current);
            }
        }
    }

    private void write(SourceDataLine line, byte[] buffer, Request current) throws InterruptedException {
        int offset = 0;
        while (request == current && offset < buffer.length) {
            int count = availableBytes(line, buffer.length - offset);
            int written = count == 0 ? 0 : line.write(buffer, offset, count);
            if (written == 0) {
                Thread.sleep(DEVICE_POLL_MILLIS);
            }
            offset += written;
        }
    }

    private static int availableBytes(DataLine line, int limit) {
        if (!line.isOpen()) {
            throw new IllegalStateException("Se cerró el dispositivo de audio.");
        }
        int available = Math.min(limit, line.available());
        return available - available % line.getFormat().getFrameSize();
    }

    private static int frameBytes(AudioFormat format) {
        return Math.round(format.getFrameRate() * FRAME_MILLIS / 1_000) * format.getFrameSize();
    }

    private static byte[] convert(byte[] input, AudioFormat source, AudioFormat target) {
        if (source.matches(target)) {
            return input.clone();
        }

        int[] mono = new int[input.length / source.getFrameSize()];
        for (int frame = 0; frame < mono.length; frame++) {
            int offset = frame * source.getFrameSize();
            int sum = 0;
            for (int channel = 0; channel < source.getChannels(); channel++) {
                int sampleOffset = offset + channel * Short.BYTES;
                int high = source.isBigEndian() ? sampleOffset : sampleOffset + 1;
                int low = source.isBigEndian() ? sampleOffset + 1 : sampleOffset;
                sum += (short) ((input[high] << 8) | (input[low] & 0xff));
            }
            mono[frame] = sum / source.getChannels();
        }

        byte[] output = new byte[frameBytes(target)];
        int frames = output.length / target.getFrameSize();
        for (int frame = 0; frame < frames; frame++) {
            int sample = resample(mono, frame, frames);
            for (int channel = 0; channel < target.getChannels(); channel++) {
                int offset = frame * target.getFrameSize() + channel * Short.BYTES;
                int high = target.isBigEndian() ? offset : offset + 1;
                int low = target.isBigEndian() ? offset + 1 : offset;
                output[high] = (byte) (sample >> 8);
                output[low] = (byte) sample;
            }
        }
        return output;
    }

    private static int resample(int[] mono, int frame, int outputFrames) {
        int position = frame * mono.length;
        int left = position / outputFrames;
        if (mono.length <= outputFrames) {
            // Linear interpolation when the playback device uses a higher rate.
            int fraction = position % outputFrames;
            int right = Math.min(left + 1, mono.length - 1);
            int weighted = mono[left] * (outputFrames - fraction) + mono[right] * fraction;
            return Math.round(weighted / (float) outputFrames);
        }

        // Average the source time interval when reducing the microphone sample rate.
        int end = position + mono.length;
        int weighted = 0;
        for (int sample = left; sample * outputFrames < end; sample++) {
            int overlap = Math.min(end, (sample + 1) * outputFrames) - Math.max(position, sample * outputFrames);
            weighted += mono[sample] * overlap;
        }
        return Math.round(weighted / (float) mono.length);
    }
}
