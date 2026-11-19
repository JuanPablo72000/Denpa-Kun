package com.denpakun;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.PortUnreachableException;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

public final class UdpTransceiver implements AutoCloseable {
    public static final int PORT = 50005;
    public static final int HEADER_BYTES = 21;
    public static final int PAYLOAD_BYTES = 640;
    public static final int PACKET_BYTES = HEADER_BYTES + PAYLOAD_BYTES;
    public static final int FRAME_MILLIS = 20;

    private static final int CHECKSUM_OFFSET = 13;
    private static final long FRAME_NANOS = TimeUnit.MILLISECONDS.toNanos(FRAME_MILLIS);
    private static final long CSMA_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final long WATCHDOG_NANOS = TimeUnit.MILLISECONDS.toNanos(300);
    private static final long REORDER_NANOS =
            TimeUnit.MILLISECONDS.toNanos(FaultSimulator.MAX_JITTER_MILLIS);
    private static final long MAX_SLEEP_NANOS = TimeUnit.MILLISECONDS.toNanos(8);
    private static final long SPIN_NANOS = TimeUnit.MICROSECONDS.toNanos(1_500);
    private static final int REORDER_CAPACITY = 16;
    private static final int MAX_CORRUPT_LEAD = REORDER_CAPACITY + (int) (WATCHDOG_NANOS / FRAME_NANOS);
    private static final int CONTROL_MAGIC = 0x444E504B; // DNPK
    private static final long VOICE_MODE_MARKER = 0xA9C6_31F2_6957_8EBCL;
    private static final long VOICE_MODE_MASK = 3;
    private static final long[] PAYLOAD_FLIP_SYNDROMES = payloadFlipSyndromes();
    private static final MediaFormat DEFAULT_VOICE = new MediaFormat(
            FrameType.AUDIO_FRAME, SignalModulator.Mode.DSB_SC);
    private static final MediaFormat SSTV = new MediaFormat(FrameType.SSTV_FRAME, null);

    private final Object gate = new Object();
    private final DatagramSocket socket;
    private final boolean peerWinsTie;
    private final FaultSimulator faults;
    private final Listener listener;
    private final Thread clock;
    private final Deque<InboundPacket> unidentifiedCorrupt = new ArrayDeque<>();

    private volatile ChannelState state = ChannelState.IDLE;
    private boolean closed;
    private Transmission transmission;
    private Reception reception;
    private boolean heardPeer;
    private long lastDatagram;
    private boolean completedReception;
    private long lastReceptionEnd;
    private long lastRemoteTimestamp;
    private long sent;
    private long received;
    private long dropped;
    private long missing;
    private int corrupt;
    private double jitterMillis;

    public UdpTransceiver(InetAddress peer, FaultSimulator faults, Listener listener)
            throws IOException {
        this(null, peer, faults, listener);
    }

    public UdpTransceiver(InetAddress local, InetAddress peer, FaultSimulator faults,
                          Listener listener) throws IOException {
        Objects.requireNonNull(peer, "peer");
        this.faults = Objects.requireNonNull(faults, "faults");
        this.listener = Objects.requireNonNull(listener, "listener");

        socket = new DatagramSocket(new InetSocketAddress(local, PORT));
        try {
            socket.connect(new InetSocketAddress(peer, PORT));
            socket.setSoTimeout(FRAME_MILLIS);
            InetAddress effectiveLocal = socket.getLocalAddress();
            if (peer.equals(effectiveLocal)) {
                throw new IOException("La dirección del otro equipo coincide con la dirección local");
            }
            peerWinsTie = Arrays.compareUnsigned(peer.getAddress(), effectiveLocal.getAddress()) < 0;
        } catch (IOException | RuntimeException error) {
            socket.close();
            throw error;
        }

        clock = daemon("denpa-udp-clock", this::runClock);
        clock.start();
        daemon("denpa-udp-receive", this::receiveLoop).start();
    }

    public ChannelState state() {
        return state;
    }

    public boolean isOpen() {
        return !socket.isClosed();
    }

    public Stats stats() {
        synchronized (gate) {
            return new Stats(sent, received, dropped, missing, jitterMillis);
        }
    }

    /** Transfers ownership of the source if the channel is available. */
    public boolean startTransmission(FrameType type, FrameSource source) {
        return startTransmission(type, SignalModulator.Mode.DSB_SC, source);
    }

    /** Voice uses the selected mode for this transmission. SSTV always sends its own FM tones. */
    public boolean startTransmission(FrameType type, SignalModulator.Mode mode, FrameSource source) {
        Objects.requireNonNull(source, "source");
        if (!Objects.requireNonNull(type, "type").isMedia()) {
            throw new IllegalArgumentException("Se requiere un tipo de trama de audio o imagen");
        }
        MediaFormat format = type == FrameType.AUDIO_FRAME
                ? new MediaFormat(type, Objects.requireNonNull(mode, "mode")) : SSTV;
        synchronized (gate) {
            long now = System.nanoTime();
            if (closed || state != ChannelState.IDLE) {
                return false;
            }
            if (heardPeer && now - lastDatagram < CSMA_NANOS) {
                listener.onEvent("El canal sigue ocupado por tráfico reciente");
                return false;
            }

            Transmission current = new Transmission(format, source, now);
            transmission = current;
            enqueue(current, FrameType.START_TX, 0, now, current.controlPayload);
            changeState(ChannelState.TRANSMITTING);
            wakeClock();
            return true;
        }
    }

    public void stopTransmission() {
        synchronized (gate) {
            if (transmission != null) {
                finishTransmission(transmission);
                wakeClock();
            }
        }
    }

    private void runClock() {
        while (true) {
            try {
                long deadline;
                synchronized (gate) {
                    while (transmission == null && !closed) {
                        gate.wait();
                    }
                    if (closed) {
                        return;
                    }
                    // A wakeup before acquiring the gate has already been observed here.
                    Thread.interrupted();
                    Transmission current = transmission;
                    OutboundPacket pending = current.outbox.peekFirst();
                    long now = System.nanoTime();
                    if (pending != null && pending.deadline() - now <= 0) {
                        sendReadyPackets(current);
                        continue;
                    }
                    if (!current.finished && current.nextCapture - now <= 0) {
                        capture(current);
                        continue;
                    }
                    deadline = current.nextCapture;
                    if (pending != null && (current.finished || pending.deadline() - deadline < 0)) {
                        deadline = pending.deadline();
                    }
                }
                waitUntil(deadline);
            } catch (InterruptedException wakeup) {
                // Channel changes wake the clock so it can recalculate its next deadline.
            }
        }
    }

    private static void waitUntil(long deadline) throws InterruptedException {
        while (!Thread.interrupted()) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return;
            }
            // On Windows, Thread.sleep can use a finer timer than timed monitor waits.
            if (remaining > SPIN_NANOS) {
                long sleepNanos = Math.min(MAX_SLEEP_NANOS, remaining - SPIN_NANOS);
                Thread.sleep(Duration.ofNanos(sleepNanos));
            } else {
                Thread.onSpinWait();
            }
        }
    }

    private void wakeClock() {
        if (Thread.currentThread() != clock) {
            clock.interrupt();
        }
    }

    private void capture(Transmission current) {
        try {
            CapturedFrame frame = current.source.nextFrame();
            if (frame == null) {
                finishTransmission(current);
                return;
            }
            int sequence = current.nextSequence++;
            enqueue(current, current.format.type(), sequence, frame.timestamp(), frame.payload());
            long now = System.nanoTime();
            long nextDeadline = current.nextCapture + FRAME_NANOS;
            current.nextCapture = nextDeadline - now > 0 ? nextDeadline : now + FRAME_NANOS;
        } catch (IOException | RuntimeException error) {
            listener.onEvent("Se interrumpió la captura. " + error.getMessage());
            finishTransmission(current);
        }
    }

    private void finishTransmission(Transmission current) {
        if (current.finished) {
            return;
        }
        releaseSource(current);
        enqueue(current, FrameType.END_TX, current.nextSequence, System.nanoTime(), current.controlPayload);
        completeTransmissionIfDrained(current);
    }

    private void releaseSource(Transmission current) {
        if (current.finished) {
            return;
        }
        current.finished = true;
        try {
            current.source.close();
        } catch (RuntimeException error) {
            listener.onEvent("No se pudo liberar la captura. " + error.getMessage());
        }
    }

    private void completeTransmissionIfDrained(Transmission current) {
        if (current.finished && current.outbox.isEmpty()) {
            transmission = null;
            changeState(ChannelState.IDLE);
        }
    }

    private void enqueue(Transmission current, FrameType type, int sequence, long timestamp,
                         byte[] payload) {
        byte[] bytes = encode(type, sequence, timestamp, payload, current.format.mode());
        FaultSimulator.Decision decision = faults.apply(bytes, HEADER_BYTES);
        if (decision.dropped()) {
            dropped++;
            return;
        }
        current.outbox.addLast(new OutboundPacket(bytes, System.nanoTime() + decision.delayNanos()));
    }

    private void sendReadyPackets(Transmission current) {
        while (!current.outbox.isEmpty()) {
            OutboundPacket packet = current.outbox.getFirst();
            if (packet.deadline() - System.nanoTime() > 0) {
                return;
            }
            current.outbox.removeFirst();
            try {
                socket.send(new DatagramPacket(packet.bytes(), packet.bytes().length));
                sent++;
            } catch (IOException error) {
                listener.onEvent("Falló el envío UDP. " + error.getMessage());
            }
        }
        completeTransmissionIfDrained(current);
    }

    private void receiveLoop() {
        // One extra byte lets an oversized datagram be distinguished from a valid frame.
        byte[] bytes = new byte[PACKET_BYTES + 1];
        DatagramPacket datagram = new DatagramPacket(bytes, bytes.length);
        while (!socket.isClosed()) {
            try {
                datagram.setLength(bytes.length);
                socket.receive(datagram);
                handleDatagram(bytes, datagram.getLength(), System.nanoTime());
            } catch (SocketTimeoutException | PortUnreachableException inactive) {
                synchronized (gate) {
                    advanceReception(System.nanoTime());
                }
            } catch (IOException error) {
                synchronized (gate) {
                    if (!closed) {
                        listener.onEvent("Se cerró la recepción UDP. " + error.getMessage());
                        close();
                    }
                }
            }
        }
    }

    private void handleDatagram(byte[] bytes, int length, long now) {
        synchronized (gate) {
            if (!closed) {
                heardPeer = true;
                lastDatagram = now;
                receiveDatagram(bytes, length, now);
                advanceReception(now);
            }
        }
    }

    private void receiveDatagram(byte[] bytes, int length, long now) {
        if (length != PACKET_BYTES) {
            listener.onEvent("Trama UDP descartada por longitud inválida (" + length + " bytes)");
            return;
        }
        ByteBuffer header = ByteBuffer.wrap(bytes);
        FrameType type = FrameType.fromCode(header.get());
        int sequence = header.getInt();
        long timestamp = header.getLong();
        long checksum = header.getLong();
        boolean validHeader = type != null && (type == FrameType.START_TX ? sequence == 0 : sequence > 0)
                && sequence != Integer.MAX_VALUE;
        long actualChecksum = checksum(bytes);
        if (checksum != actualChecksum) {
            listener.onCorruptPacket(++corrupt);
            listener.onEvent("CRC32 incorrecto en la trama " + sequence);
            if (validHeader && type.isMedia()
                    && Arrays.binarySearch(PAYLOAD_FLIP_SYNDROMES, checksum ^ actualChecksum) >= 0) {
                rememberCorrupt(new InboundPacket(type, sequence, timestamp, now, null, null));
            }
            return;
        }
        if (!validHeader) {
            listener.onEvent("Trama UDP descartada por cabecera inválida");
            return;
        }
        received++;
        MediaFormat format = switch (type) {
            case AUDIO_FRAME -> voiceFormat(bytes);
            case SSTV_FRAME -> SSTV;
            case START_TX, END_TX -> controlFormat(bytes);
        };
        byte[] payload = type.isMedia() ? Arrays.copyOfRange(bytes, HEADER_BYTES, PACKET_BYTES) : null;
        InboundPacket packet = new InboundPacket(type, sequence, timestamp, now, payload, format);
        accept(packet);
    }

    private void accept(InboundPacket packet) {
        if (isFromCompletedReception(packet)) {
            return;
        }
        if (transmission != null) {
            if (packet.type() != FrameType.START_TX || !peerWins(packet, transmission)) {
                if (packet.type() != FrameType.END_TX) {
                    repeatClaim(transmission, packet.arrivedAt());
                }
                return;
            }
            abortTransmission();
            listener.onEvent("Colisión detectada. Se cedió el canal al otro equipo");
        }
        if (reception != null && startsNewReception(packet, reception)) {
            drainReception(packet.arrivedAt(), true);
            if (reception != null) {
                finishReception(packet.arrivedAt());
            }
        }
        if (reception == null) {
            if (packet.type() == FrameType.END_TX && packet.format() == null) {
                return;
            }
            reception = new Reception(packet);
            jitterMillis = 0;
            changeState(ChannelState.RECEIVING);
        }
        if (packet.type() == FrameType.START_TX) {
            if (!reception.sawStart || packet.timestamp() == reception.startTimestamp) {
                reception.sawStart = true;
                reception.startTimestamp = packet.timestamp();
                reception.lastActivity = packet.arrivedAt();
                identifyReception(packet);
            }
            return;
        }
        InboundPacket previous = reception.pending.get(packet.sequence());
        if ((reception.sawStart && packet.timestamp() < reception.startTimestamp)
                || packet.sequence() < reception.nextSequence
                || (previous != null && !previous.isCorruptMedia())) {
            return;
        }
        identifyReception(packet);
        if (packet.format() != null && !packet.format().equals(reception.format)) {
            listener.onEvent("Trama UDP descartada por cambio de formato durante la sesión");
            return;
        }
        reception.lastActivity = Math.max(reception.lastActivity, packet.arrivedAt());
        reception.latestTimestamp = Math.max(reception.latestTimestamp, packet.timestamp());
        if (packet.type().isMedia()) {
            updateJitter(packet, reception);
        }
        reception.pending.put(packet.sequence(), packet);
    }

    private void identifyReception(InboundPacket reference) {
        if (reception.format != null || reference.format() == null) {
            return;
        }
        reception.format = reference.format();
        listener.onSessionStarted(reception.format.type(), reception.format.mode());
        while (!unidentifiedCorrupt.isEmpty()) {
            InboundPacket candidate = unidentifiedCorrupt.removeFirst();
            long age = reference.arrivedAt() - candidate.arrivedAt();
            if (age < WATCHDOG_NANOS && belongsWith(candidate, reference)) {
                acceptCorrupt(candidate);
            }
        }
    }

    private static boolean belongsWith(InboundPacket candidate, InboundPacket reference) {
        return switch (reference.type()) {
            case START_TX -> candidate.timestamp() >= reference.timestamp();
            case END_TX -> candidate.sequence() < reference.sequence()
                    && candidate.timestamp() <= reference.timestamp();
            case AUDIO_FRAME, SSTV_FRAME -> candidate.sequence() < reference.sequence()
                    ? candidate.timestamp() <= reference.timestamp() + FRAME_NANOS
                    : candidate.timestamp() >= reference.timestamp() - FRAME_NANOS;
        };
    }

    private void rememberCorrupt(InboundPacket packet) {
        if (transmission != null || isFromCompletedReception(packet)) {
            return;
        }
        if (reception != null && reception.format != null) {
            acceptCorrupt(packet);
        } else {
            if (unidentifiedCorrupt.size() == MAX_CORRUPT_LEAD) {
                unidentifiedCorrupt.removeFirst();
            }
            unidentifiedCorrupt.addLast(packet);
        }
    }

    private void acceptCorrupt(InboundPacket packet) {
        // A failed CRC makes the header tentative. A nearby sequence, known format and
        // session timestamp let us attribute a missing playback block to a rejected datagram.
        if (packet.type() != reception.format.type()
                || packet.sequence() < reception.nextSequence
                || packet.sequence() - reception.nextSequence > MAX_CORRUPT_LEAD
                || (reception.sawStart && packet.timestamp() < reception.startTimestamp)
                || (reception.deliveredMedia && packet.timestamp() < reception.lastMediaTimestamp - FRAME_NANOS)) {
            return;
        }
        if (reception.pending.putIfAbsent(packet.sequence(), packet) == null) {
            reception.lastActivity = Math.max(reception.lastActivity, packet.arrivedAt());
        }
    }

    private boolean isFromCompletedReception(InboundPacket packet) {
        return completedReception && packet.arrivedAt() - lastReceptionEnd < WATCHDOG_NANOS
                && packet.timestamp() <= lastRemoteTimestamp;
    }

    private boolean peerWins(InboundPacket packet, Transmission current) {
        // nanoTime values are ordered as wire values. Separate hosts have independent origins.
        return packet.timestamp() < current.startedAt
                || (packet.timestamp() == current.startedAt && peerWinsTie);
    }

    private void repeatClaim(Transmission current, long now) {
        // Both contenders need the original START timestamp, even if the first START was lost.
        if (!current.finished && now - current.lastClaimAt >= CSMA_NANOS) {
            current.lastClaimAt = now;
            enqueue(current, FrameType.START_TX, 0, current.startedAt, current.controlPayload);
            wakeClock();
        }
    }

    private void abortTransmission() {
        Transmission current = transmission;
        transmission = null;
        releaseSource(current);
        current.outbox.clear();
        wakeClock();
    }

    private static boolean startsNewReception(InboundPacket packet, Reception current) {
        if (packet.timestamp() <= current.latestTimestamp) {
            return false;
        }
        return packet.type() == FrameType.START_TX
                || (packet.type().isMedia() && packet.sequence() < current.nextSequence);
    }

    private void updateJitter(InboundPacket packet, Reception current) {
        InboundPacket previous = current.jitterReference;
        if (previous == null || packet.sequence() > previous.sequence()) {
            current.jitterReference = packet;
            if (previous != null) {
                long arrivals = packet.arrivedAt() - previous.arrivedAt();
                long captures = packet.timestamp() - previous.timestamp();
                double deltaMillis = Math.abs((double) arrivals - captures) / 1_000_000;
                jitterMillis += (deltaMillis - jitterMillis) / 16;
            }
        }
    }

    private void advanceReception(long now) {
        drainReception(now, false);
        if (reception != null && now - reception.lastActivity >= WATCHDOG_NANOS) {
            finishReception(now);
            listener.onEvent("Canal liberado tras 300 ms sin tramas de la sesión");
        }
    }

    private void drainReception(long now, boolean force) {
        if (reception == null) {
            return;
        }
        while (!reception.pending.isEmpty()) {
            InboundPacket packet = reception.pending.firstEntry().getValue();
            int absent = packet.sequence() - reception.nextSequence;
            if (absent > 0 && packet.isCorruptMedia()
                    && reception.pending.values().stream().allMatch(InboundPacket::isCorruptMedia)) {
                // A CRC-failed datagram alone cannot establish how many other slots were sent.
                break;
            }
            if (absent > 0 || packet.isCorruptMedia()) {
                if (!reception.waitingForGap) {
                    reception.waitingForGap = true;
                    reception.gapSince = packet.arrivedAt();
                }
                if (!force && now - reception.gapSince < REORDER_NANOS
                        && reception.pending.size() < REORDER_CAPACITY) {
                    break;
                }
            }
            if (absent > 0) {
                missing += absent;
                reportGap(reception.nextSequence, absent, false);
            }
            reception.pending.pollFirstEntry();
            reception.nextSequence = packet.sequence() + 1;
            reception.waitingForGap = false;
            if (packet.type() == FrameType.END_TX) {
                finishReception(now);
                return;
            }
            if (packet.isCorruptMedia()) {
                reportGap(packet.sequence(), 1, true);
            } else {
                reception.deliveredMedia = true;
                reception.lastMediaTimestamp = packet.timestamp();
                listener.onFrame(packet.type(), packet.sequence(), packet.payload(), reception.format.mode());
            }
        }
    }

    private void reportGap(int sequence, int count, boolean corruptFrame) {
        if (reception.format != null) {
            listener.onFrameGap(reception.format.type(), sequence, count, corruptFrame);
        }
    }

    private void finishReception(long now) {
        completedReception = true;
        lastReceptionEnd = now;
        lastRemoteTimestamp = reception.latestTimestamp;
        reception = null;
        changeState(ChannelState.IDLE);
    }

    private void changeState(ChannelState next) {
        if (state != next) {
            state = next;
            listener.onStateChanged(next);
        }
    }

    @Override
    public void close() {
        synchronized (gate) {
            if (closed) {
                return;
            }
            closed = true;
            if (transmission != null) {
                abortTransmission();
            }
            reception = null;
            socket.close();
            wakeClock();
            changeState(ChannelState.IDLE);
            listener.onClosed();
        }
    }

    private static byte[] encode(FrameType type, int sequence, long timestamp, byte[] payload,
                                 SignalModulator.Mode mode) {
        byte[] bytes = new byte[PACKET_BYTES];
        ByteBuffer packet = ByteBuffer.wrap(bytes);
        packet.put(type.code).putInt(sequence).putLong(timestamp);
        packet.position(HEADER_BYTES);
        packet.put(payload);
        if (type == FrameType.AUDIO_FRAME && mode != SignalModulator.Mode.DSB_SC) {
            writeVoiceMode(bytes, mode);
        }
        packet.putLong(CHECKSUM_OFFSET, checksum(bytes));
        return bytes;
    }

    private static byte[] controlPayload(MediaFormat format) {
        byte[] payload = new byte[PAYLOAD_BYTES];
        int modeId = format.mode() == null ? 0 : format.mode().wireId();
        ByteBuffer.wrap(payload).putInt(CONTROL_MAGIC).put(format.type().code).put((byte) modeId);
        return payload;
    }

    private static MediaFormat controlFormat(byte[] bytes) {
        ByteBuffer payload = ByteBuffer.wrap(bytes, HEADER_BYTES, PAYLOAD_BYTES);
        if (payload.getInt() != CONTROL_MAGIC) {
            return null;
        }
        FrameType type = FrameType.fromCode(payload.get());
        SignalModulator.Mode mode = SignalModulator.Mode.fromWireId(Byte.toUnsignedInt(payload.get()));
        if (type == FrameType.SSTV_FRAME) {
            return SSTV;
        }
        return type == FrameType.AUDIO_FRAME && mode != null ? new MediaFormat(type, mode) : null;
    }

    // Repeating the mode inside PCM tolerates a missing START without changing the header.
    // Each tagged signed sample changes by at most one unit; legacy voice remains bit-for-bit.
    private static void writeVoiceMode(byte[] bytes, SignalModulator.Mode mode) {
        long tag = VOICE_MODE_MARKER | mode.wireId();
        for (int sample = 0; sample < Long.SIZE; sample++) {
            int offset = pcmLowByteOffset(sample);
            int bit = (int) (tag >>> (Long.SIZE - 1 - sample)) & 1;
            bytes[offset] = (byte) ((bytes[offset] & 0xfe) | bit);
        }
    }

    private static MediaFormat voiceFormat(byte[] bytes) {
        long tag = 0;
        for (int sample = 0; sample < Long.SIZE; sample++) {
            tag = (tag << 1) | (bytes[pcmLowByteOffset(sample)] & 1);
        }
        if ((tag & ~VOICE_MODE_MASK) == VOICE_MODE_MARKER) {
            SignalModulator.Mode mode = SignalModulator.Mode.fromWireId((int) (tag & VOICE_MODE_MASK));
            if (mode != null) {
                return new MediaFormat(FrameType.AUDIO_FRAME, mode);
            }
        }
        return DEFAULT_VOICE;
    }

    private static int pcmLowByteOffset(int sample) {
        return HEADER_BYTES + sample * Short.BYTES + 1;
    }

    private static long checksum(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes, 0, CHECKSUM_OFFSET);
        crc.update(bytes, HEADER_BYTES, PAYLOAD_BYTES);
        return crc.getValue();
    }

    private static long[] payloadFlipSyndromes() {
        // Inverting a payload byte changes the CRC by the same XOR for every packet
        // of this length. Attribute only those CRC failures whose position is in PCM.
        byte[] bytes = new byte[PACKET_BYTES];
        long baseline = checksum(bytes);
        long[] syndromes = new long[PAYLOAD_BYTES];
        for (int index = 0; index < syndromes.length; index++) {
            int offset = HEADER_BYTES + index;
            bytes[offset] = (byte) 0xff;
            syndromes[index] = baseline ^ checksum(bytes);
            bytes[offset] = 0;
        }
        Arrays.sort(syndromes);
        return syndromes;
    }

    private static Thread daemon(String name, Runnable task) {
        Thread thread = new Thread(task, name);
        thread.setDaemon(true);
        return thread;
    }

    public enum FrameType {
        START_TX(0), AUDIO_FRAME(1), END_TX(2), SSTV_FRAME(3);

        private final byte code;

        FrameType(int code) {
            this.code = (byte) code;
        }

        private boolean isMedia() {
            return this == AUDIO_FRAME || this == SSTV_FRAME;
        }

        private static FrameType fromCode(byte code) {
            return switch (code) {
                case 0 -> START_TX;
                case 1 -> AUDIO_FRAME;
                case 2 -> END_TX;
                case 3 -> SSTV_FRAME;
                default -> null;
            };
        }
    }

    public record CapturedFrame(byte[] payload, long timestamp) {
        public CapturedFrame {
            if (Objects.requireNonNull(payload, "payload").length != PAYLOAD_BYTES) {
                throw new IllegalArgumentException("La carga PCM debe tener 640 bytes");
            }
        }
    }

    public record Stats(long sent, long received, long dropped, long missing, double jitterMillis) { }

    @FunctionalInterface
    public interface FrameSource extends AutoCloseable {
        /** Returns the next PCM block without blocking, or null to finish the transmission. */
        CapturedFrame nextFrame() throws IOException;

        @Override
        default void close() { }
    }

    /** Callbacks run synchronously on transport threads or on the caller of a state change. */
    public interface Listener {
        default void onStateChanged(ChannelState state) { }

        default void onSessionStarted(FrameType type, SignalModulator.Mode mode) { }

        default void onFrame(FrameType type, int sequence, byte[] payload) { }

        default void onFrame(FrameType type, int sequence, byte[] payload, SignalModulator.Mode mode) {
            onFrame(type, sequence, payload);
        }

        /** Reports unavailable media slots in order, with a one-based first sequence. */
        default void onFrameGap(FrameType type, int firstSequence, int frameCount, boolean corrupt) { }

        default void onCorruptPacket(int total) { }

        default void onEvent(String message) { }

        default void onClosed() { }
    }

    private static final class Transmission {
        private final MediaFormat format;
        private final byte[] controlPayload;
        private final FrameSource source;
        private final long startedAt;
        private final Deque<OutboundPacket> outbox = new ArrayDeque<>();
        private int nextSequence = 1;
        private long nextCapture;
        private long lastClaimAt;
        private boolean finished;

        private Transmission(MediaFormat format, FrameSource source, long startedAt) {
            this.format = format;
            this.controlPayload = controlPayload(format);
            this.source = source;
            this.startedAt = startedAt;
            nextCapture = startedAt + FRAME_NANOS;
            lastClaimAt = startedAt - CSMA_NANOS;
        }
    }

    private static final class Reception {
        private final NavigableMap<Integer, InboundPacket> pending = new TreeMap<>();
        private MediaFormat format;
        private int nextSequence = 1;
        private boolean sawStart;
        private long startTimestamp;
        private long latestTimestamp;
        private long lastActivity;
        private boolean deliveredMedia;
        private long lastMediaTimestamp;
        private boolean waitingForGap;
        private long gapSince;
        private InboundPacket jitterReference;

        private Reception(InboundPacket first) {
            latestTimestamp = first.timestamp();
            lastActivity = first.arrivedAt();
        }
    }

    private record MediaFormat(FrameType type, SignalModulator.Mode mode) { }

    private record OutboundPacket(byte[] bytes, long deadline) { }

    private record InboundPacket(FrameType type, int sequence, long timestamp, long arrivedAt,
                                 byte[] payload, MediaFormat format) {
        private boolean isCorruptMedia() {
            return type.isMedia() && payload == null;
        }
    }
}
