package com.denpakun;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.BitSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.imageio.ImageIO;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.css.PseudoClass;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

public final class MainApp extends Application {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final byte[] SILENCE = new byte[AudioEngine.FRAME_BYTES];
    private static final int LOG_LIMIT = 200;
    private static final PseudoClass PRESSED = PseudoClass.getPseudoClass("talking");
    private static final Color VOICE_COLOR = Color.web("#69dfbe");
    private static final Color CARRIER_COLOR = Color.web("#eab572");

    private final ExecutorService connectionWorker = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("denpa-connection").factory());
    private final ExecutorService imageWorker = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("denpa-image").factory());
    private final FaultSimulator faults = new FaultSimulator();
    private final TextField peerInput = new TextField();
    private final Button connect = new Button("Conectar");
    private final Button ptt = new Button("Mantén pulsado para hablar");
    private final Button chooseImage = new Button("Abrir imagen");
    private final Button sendImage = new Button("Enviar imagen");
    private final Label channel = new Label("Sin enlace");
    private final Label connectionInfo = new Label("UDP · puerto 50005");
    private final Label imageInfo = new Label("PNG o JPG · 160 × 120");
    private final Label receiveInfo = new Label("En espera de una imagen");
    private final ProgressBar imageProgress = new ProgressBar(0);
    private final ImageView outgoingPreview = new ImageView();
    private final ImageView incomingPreview = new ImageView();
    private final ListView<LogEntry> events = new ListView<>();
    private final SignalScope voice = new SignalScope(VOICE_COLOR);
    private final SignalScope carrier = new SignalScope(CARRIER_COLOR);
    private final Label sent = new Label("0");
    private final Label received = new Label("0");
    private final Label missing = new Label("0");
    private final Label dropped = new Label("0");
    private final Label corrupt = new Label("0");
    private final Label jitter = new Label("0.0 ms");
    private final BitSet decodedRows = new BitSet(SstvCodec.HEIGHT);
    private Timeline dashboard;
    private Connection connection;
    private Connection voiceGesture;
    private boolean spaceHeld;
    private boolean mouseHeld;
    private boolean loadingImage;
    private volatile boolean listenToTones;
    private WritableImage incomingImage;
    private PreparedImage preparedImage;

    @Override
    public void start(Stage stage) {
        Label brand = new Label("Denpa-Kun");
        brand.getStyleClass().add("brand");
        Label subtitle = new Label("Transceptor P2P · Voz y SSTV");
        subtitle.getStyleClass().add("muted");
        channel.getStyleClass().setAll("channel-state", "offline");
        connectionInfo.getStyleClass().add("muted");
        VBox identity = new VBox(3, brand, subtitle);
        VBox station = new VBox(6, channel, connectionInfo);
        station.setAlignment(Pos.CENTER_RIGHT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(16, identity, spacer, station);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header");

        VBox left = new VBox(14, buildLinkPanel(), buildFaultPanel(), buildLogPanel());
        left.setPrefWidth(324);
        left.setMinWidth(294);
        VBox right = new VBox(14, buildSignalPanel(), buildImagePanel(stage));
        right.setMinWidth(480);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox workspace = new HBox(14, left, right);
        workspace.getStyleClass().add("workspace");
        ScrollPane scroll = new ScrollPane(workspace);
        scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.getStyleClass().add("workspace-scroll");
        BorderPane root = new BorderPane();
        root.setTop(header);
        root.setCenter(scroll);

        Scene scene = new Scene(root, 1060, 810);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("styles.css")).toExternalForm());
        setupPtt(scene, stage);
        refreshControls();
        dashboard = new Timeline(new KeyFrame(Duration.millis(50), event -> updateDashboard()));
        dashboard.setCycleCount(Timeline.INDEFINITE);
        dashboard.play();
        stage.setTitle("Denpa-Kun · Transceptor P2P");
        stage.setMinWidth(830);
        stage.setMinHeight(580);
        stage.setScene(scene);
        stage.show();
        peerInput.requestFocus();
    }

    private VBox buildLinkPanel() {
        Label peerLabel = new Label("IP o nombre del equipo remoto");
        peerLabel.setLabelFor(peerInput);
        peerLabel.getStyleClass().add("muted");
        peerInput.setPromptText("192.168.137.1");
        peerInput.setOnAction(event -> connect.fire());
        connect.getStyleClass().add("primary-button");
        connect.setOnAction(event -> toggleConnection());
        HBox.setHgrow(peerInput, Priority.ALWAYS);
        HBox endpoint = new HBox(8, peerInput, connect);
        ptt.setMaxWidth(Double.MAX_VALUE);
        ptt.getStyleClass().add("ptt");
        Label hint = new Label("Atajo · Barra espaciadora");
        hint.getStyleClass().add("muted");
        return panel("Enlace de voz", peerLabel, endpoint, ptt, hint);
    }

    private VBox buildFaultPanel() {
        Slider loss = new Slider(0, FaultSimulator.MAX_LOSS_PERCENT, 0);
        loss.setAccessibleText("Pérdida de paquetes");
        Label lossValue = new Label("0 %");
        loss.valueProperty().addListener((observable, oldValue, value) -> {
            int percent = (int) Math.round(value.doubleValue());
            faults.setLossPercent(percent);
            lossValue.setText(percent + " %");
        });
        Slider delay = new Slider(0, FaultSimulator.MAX_JITTER_MILLIS, 0);
        delay.setAccessibleText("Jitter");
        Label delayValue = new Label("0 ms");
        delay.valueProperty().addListener((observable, oldValue, value) -> {
            int millis = (int) Math.round(value.doubleValue());
            faults.setJitterMillis(millis);
            delayValue.setText(millis + " ms");
        });
        CheckBox bitFlip = new CheckBox("Inyectar corrupción después del CRC");
        bitFlip.selectedProperty().addListener((observable, oldValue, value) -> faults.setCorruptionEnabled(value));
        return panel("Simulador de fallos", sliderRow("Pérdida de paquetes", lossValue, loss),
                sliderRow("Jitter máximo", delayValue, delay), bitFlip);
    }

    private VBox buildLogPanel() {
        events.setPrefHeight(181);
        events.setPlaceholder(new Label("Sin eventos"));
        events.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(LogEntry entry, boolean empty) {
                super.updateItem(entry, empty);
                setText(empty || entry == null ? null : entry.time() + "  " + entry.text());
                getStyleClass().remove("warning-log");
                if (!empty && entry != null && entry.warning()) {
                    getStyleClass().add("warning-log");
                }
            }
        });
        return panel("Eventos", events);
    }

    private VBox buildSignalPanel() {
        HBox metrics = new HBox(6, metric("Enviados", sent, "Datagramas enviados por UDP"),
                metric("Válidos", received, "Datagramas recibidos con CRC válido"),
                metric("Simulados", dropped, "Datagramas descartados en este emisor"),
                metric("Faltantes", missing, "Saltos de secuencia de tramas recibidas"),
                metric("Corruptos", corrupt, "Datagramas recibidos con CRC incorrecto"),
                metric("Jitter RX", jitter, "Variación estimada del espaciado entre tramas recibidas"));
        return panel("Osciloscopio", metrics, scope("Voz en banda base", voice, "voice-label"),
                scope("Portadora modulada / tonos SSTV", carrier, "carrier-label"));
    }

    private VBox buildImagePanel(Stage stage) {
        CheckBox tones = new CheckBox("Escuchar SSTV recibido");
        tones.selectedProperty().addListener((observable, oldValue, value) -> listenToTones = value);
        chooseImage.setOnAction(event -> loadImage(stage));
        sendImage.setOnAction(event -> toggleImageTransmission());
        sendImage.getStyleClass().add("primary-button");
        HBox actions = new HBox(8, chooseImage, sendImage, tones);
        actions.setAlignment(Pos.CENTER_LEFT);
        imageProgress.setMaxWidth(Double.MAX_VALUE);
        VBox transmit = imageTile("Vista previa", outgoingPreview, "Sin imagen", imageInfo);
        VBox receive = imageTile("Recepción", incomingPreview, "Sin imagen", receiveInfo);
        HBox images = new HBox(10, transmit, receive);
        HBox.setHgrow(transmit, Priority.ALWAYS);
        HBox.setHgrow(receive, Priority.ALWAYS);
        return panel("Imágenes SSTV", actions, images, imageProgress);
    }

    private static VBox imageTile(String title, ImageView view, String placeholder, Label caption) {
        view.setFitWidth(192);
        view.setFitHeight(144);
        view.setPreserveRatio(true);
        view.setSmooth(false);
        Label empty = new Label(placeholder);
        empty.getStyleClass().add("muted");
        empty.visibleProperty().bind(view.imageProperty().isNull());
        StackPane screen = new StackPane(empty, view);
        screen.setMinHeight(144);
        screen.getStyleClass().add("image-screen");
        caption.getStyleClass().add("muted");
        caption.setMaxWidth(212);
        caption.setTooltip(new Tooltip());
        caption.getTooltip().textProperty().bind(caption.textProperty());
        VBox tile = new VBox(5, new Label(title), screen, caption);
        tile.setFillWidth(true);
        tile.setPrefWidth(240);
        return tile;
    }

    private static VBox panel(String title, Node... content) {
        Label heading = new Label(title);
        heading.getStyleClass().add("panel-title");
        VBox panel = new VBox(10, heading);
        panel.getStyleClass().add("panel");
        panel.getChildren().addAll(content);
        return panel;
    }

    private static VBox sliderRow(String title, Label value, Slider slider) {
        Label label = new Label(title);
        label.setLabelFor(slider);
        value.getStyleClass().add("control-value");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return new VBox(4, new HBox(4, label, spacer, value), slider);
    }

    private static VBox scope(String title, SignalScope scope, String colorClass) {
        Label label = new Label(title);
        label.getStyleClass().add(colorClass);
        return new VBox(4, label, scope);
    }

    private static VBox metric(String title, Label value, String description) {
        Label label = new Label(title);
        label.getStyleClass().add("metric-label");
        value.getStyleClass().add("metric-value");
        VBox metric = new VBox(3, label, value);
        metric.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(metric, Priority.ALWAYS);
        metric.getStyleClass().add("metric");
        Tooltip.install(metric, new Tooltip(description));
        return metric;
    }

    private void toggleConnection() {
        if (connection != null) {
            Connection previous = connection;
            releaseVoice();
            connection = null;
            connectionWorker.execute(previous::close);
            appendLog("Enlace cerrado.", false);
            refreshControls();
            return;
        }
        String remote = peerInput.getText().strip();
        if (remote.isEmpty()) {
            appendLog("Escribe la IP o el nombre del equipo remoto.", true);
            peerInput.requestFocus();
            return;
        }
        Connection next = new Connection();
        connection = next;
        corrupt.setText("0");
        refreshControls();
        connectionWorker.execute(() -> {
            try {
                InetAddress peer = InetAddress.getByName(remote);
                if (peer.isAnyLocalAddress() || peer.isMulticastAddress()) {
                    throw new IOException("Introduce una dirección de un equipo individual.");
                }
                next.peerAddress = peer.getHostAddress();
                next.radio = new UdpTransceiver(peer, faults, next);
                next.show(() -> {
                    appendLog("Enlace abierto con " + next.peerAddress + ".", false);
                    refreshControls();
                    ptt.requestFocus();
                });
            } catch (IOException | RuntimeException failure) {
                next.close();
                next.show(() -> {
                    connection = null;
                    appendLog("No se pudo abrir el enlace · " + failure.getMessage(), true);
                    refreshControls();
                });
            }
        });
    }

    private void setupPtt(Scene scene, Stage stage) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.SPACE && (spaceHeld || scene.getFocusOwner() == ptt
                    || !(scene.getFocusOwner() instanceof TextInputControl
                    || scene.getFocusOwner() instanceof Button || scene.getFocusOwner() instanceof CheckBox))) {
                event.consume();
                if (!spaceHeld) {
                    spaceHeld = true;
                    beginVoice();
                }
            }
        });
        scene.addEventFilter(KeyEvent.KEY_RELEASED, event -> {
            if (event.getCode() == KeyCode.SPACE && spaceHeld) {
                event.consume();
                spaceHeld = false;
                if (!mouseHeld) {
                    releaseVoice();
                }
            }
        });
        ptt.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                event.consume();
                ptt.requestFocus();
                mouseHeld = true;
                beginVoice();
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && mouseHeld) {
                event.consume();
                mouseHeld = false;
                if (!spaceHeld) {
                    releaseVoice();
                }
            }
        });
        stage.focusedProperty().addListener((observable, oldValue, focused) -> {
            if (!focused) {
                spaceHeld = false;
                mouseHeld = false;
                releaseVoice();
            }
        });
    }

    private void beginVoice() {
        Connection current = connection;
        if (voiceGesture != null || current == null || current.radio == null
                || current.radio.state() != ChannelState.IDLE) {
            return;
        }
        UdpTransceiver.FrameSource source = new UdpTransceiver.FrameSource() {
            private boolean started;

            @Override
            public UdpTransceiver.CapturedFrame nextFrame() {
                if (!started) {
                    current.audio.startCapture();
                    started = true;
                }
                AudioEngine.CapturedFrame capture = current.audio.pollCapture();
                byte[] pcm = capture == null ? SILENCE : capture.pcm();
                long timestamp = capture == null ? System.nanoTime() : capture.timestampNanos();
                byte[] encoded = SignalModulator.modulate(pcm);
                current.waveforms = new Waveforms(pcm, encoded);
                return new UdpTransceiver.CapturedFrame(encoded, timestamp);
            }

            @Override
            public void close() {
                current.audio.stopCapture();
            }
        };
        if (current.radio.startTransmission(UdpTransceiver.FrameType.AUDIO_FRAME, source)) {
            voiceGesture = current;
        } else {
            appendLog("Canal ocupado. Vuelve a pulsar para transmitir.", false);
        }
        refreshControls();
    }

    private void releaseVoice() {
        if (voiceGesture != null) {
            Connection active = voiceGesture;
            voiceGesture = null;
            active.radio.stopTransmission();
            refreshControls();
        }
    }

    private void loadImage(Stage stage) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Seleccionar imagen SSTV");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Imágenes PNG y JPEG", "*.png", "*.jpg", "*.jpeg"));
        File file = chooser.showOpenDialog(stage);
        if (file == null) {
            return;
        }
        loadingImage = true;
        imageInfo.setText("Preparando imagen…");
        refreshControls();
        imageWorker.execute(() -> {
            try {
                BufferedImage original = ImageIO.read(file);
                if (original == null) {
                    throw new IOException("El formato no se pudo leer.");
                }
                BufferedImage preview = SstvCodec.resize(original);
                PreparedImage result = new PreparedImage(file.getName(), preview, SstvCodec.encode(preview));
                Platform.runLater(() -> {
                    preparedImage = result;
                    outgoingPreview.setImage(SwingFXUtils.toFXImage(result.preview(), null));
                    imageInfo.setText(result.name());
                    loadingImage = false;
                    imageProgress.setProgress(0);
                    refreshControls();
                });
            } catch (IOException | RuntimeException failure) {
                Platform.runLater(() -> {
                    appendLog("No se pudo abrir la imagen · " + failure.getMessage(), true);
                    imageInfo.setText(preparedImage == null ? "PNG o JPG · 160 × 120" : preparedImage.name());
                    loadingImage = false;
                    refreshControls();
                });
            }
        });
    }

    private void toggleImageTransmission() {
        Connection current = connection;
        if (current == null || current.radio == null || preparedImage == null) {
            return;
        }
        if (current.sendingImage && current.radio.state() == ChannelState.TRANSMITTING) {
            current.radio.stopTransmission();
            refreshControls();
            return;
        }
        if (current.radio.state() != ChannelState.IDLE) {
            return;
        }
        List<byte[]> frames = preparedImage.frames();
        current.imageFrame = 0;
        current.sendingImage = true;
        UdpTransceiver.FrameSource source = () -> {
            int index = current.imageFrame;
            if (index == frames.size()) {
                return null;
            }
            byte[] block = frames.get(index);
            current.waveforms = new Waveforms(null, block);
            current.imageFrame = index + 1;
            return new UdpTransceiver.CapturedFrame(block, System.nanoTime());
        };
        if (current.radio.startTransmission(UdpTransceiver.FrameType.SSTV_FRAME, source)) {
            imageProgress.setProgress(0);
            appendLog("Envío SSTV iniciado · " + preparedImage.name(), false);
        } else {
            current.sendingImage = false;
            appendLog("Canal ocupado. Vuelve a pulsar para enviar la imagen.", false);
        }
        refreshControls();
    }

    private void refreshControls() {
        Connection current = connection;
        boolean ready = current != null && current.radio != null && current.radio.isOpen();
        ChannelState state = ready ? current.radio.state() : ChannelState.IDLE;
        boolean talking = ready && state == ChannelState.TRANSMITTING && voiceGesture == current;
        boolean sending = ready && state == ChannelState.TRANSMITTING && current.sendingImage;
        peerInput.setDisable(current != null);
        connect.setText(current == null ? "Conectar" : ready ? "Desconectar" : "Cancelar");
        ptt.setDisable(!ready || state == ChannelState.RECEIVING || (state == ChannelState.TRANSMITTING && !talking));
        ptt.pseudoClassStateChanged(PRESSED, talking);
        ptt.setText(talking ? "Transmitiendo voz" : state == ChannelState.RECEIVING
                ? "Micrófono bloqueado" : "Mantén pulsado para hablar");
        chooseImage.setDisable(loadingImage || sending);
        sendImage.setDisable(!ready || loadingImage || preparedImage == null || (state != ChannelState.IDLE && !sending));
        sendImage.setText(sending ? "Detener envío" : "Enviar imagen");
        channel.getStyleClass().setAll("channel-state", ready ? state.name().toLowerCase(Locale.ROOT) : "offline");
        channel.setText(ready ? switch (state) {
            case IDLE -> "Canal libre";
            case TRANSMITTING -> "Transmitiendo";
            case RECEIVING -> "Recibiendo";
        } : current == null ? "Sin enlace" : "Abriendo enlace…");
        connectionInfo.setText(ready ? current.peerAddress + " · UDP 50005" : "UDP · puerto 50005");
    }

    private void updateDashboard() {
        Connection current = connection;
        Waveforms sample = current == null ? null : current.waveforms;
        voice.display(sample == null ? null : sample.baseband());
        carrier.display(sample == null ? null : sample.carrier());
        if (current == null || current.radio == null) {
            sent.setText("0");
            received.setText("0");
            missing.setText("0");
            dropped.setText("0");
            corrupt.setText("0");
            jitter.setText("0.0 ms");
            return;
        }
        UdpTransceiver.Stats stats = current.radio.stats();
        sent.setText(Long.toString(stats.sent()));
        received.setText(Long.toString(stats.received()));
        missing.setText(Long.toString(stats.missing()));
        dropped.setText(Long.toString(stats.dropped()));
        jitter.setText(String.format(Locale.forLanguageTag("es-MX"), "%.1f ms", stats.jitterMillis()));
        if (current.sendingImage && preparedImage != null) {
            imageProgress.setProgress((double) current.imageFrame / preparedImage.frames().size());
        }
    }

    private void appendLog(String message, boolean warning) {
        if (events.getItems().size() == LOG_LIMIT) {
            events.getItems().removeFirst();
        }
        events.getItems().add(new LogEntry(LocalTime.now().format(TIME), message, warning));
        events.scrollTo(events.getItems().size() - 1);
    }

    @Override
    public void stop() {
        if (dashboard != null) {
            dashboard.stop();
        }
        Connection previous = connection;
        connection = null;
        if (previous != null) {
            connectionWorker.execute(previous::close);
        }
        connectionWorker.shutdown();
        imageWorker.shutdownNow();
    }

    private final class Connection implements UdpTransceiver.Listener, AutoCloseable {
        private final AudioEngine audio = new AudioEngine(this::onAudioError);
        private volatile UdpTransceiver radio;
        private String peerAddress;
        private volatile Waveforms waveforms;
        private volatile int imageFrame;
        private boolean sendingImage;
        private boolean playing;
        private SstvCodec.Decoder decoder;

        @Override
        public void onStateChanged(ChannelState state) {
            boolean finishedImageReception = state == ChannelState.IDLE && decoder != null;
            if (state == ChannelState.RECEIVING) {
                audio.stopPlayback();
                playing = false;
                decoder = null;
            } else {
                if (state == ChannelState.IDLE && playing) {
                    audio.finishPlayback();
                    playing = false;
                } else if (state == ChannelState.TRANSMITTING) {
                    audio.stopPlayback();
                    playing = false;
                }
            }
            waveforms = null;
            show(() -> {
                if (finishedImageReception && decodedRows.isEmpty()) {
                    receiveInfo.setText("Sin líneas recuperadas");
                }
                if (state != ChannelState.TRANSMITTING
                        && (radio == null || radio.state() != ChannelState.TRANSMITTING)) {
                    if (voiceGesture == this) {
                        voiceGesture = null;
                    }
                    if (sendingImage && preparedImage != null) {
                        imageProgress.setProgress((double) imageFrame / preparedImage.frames().size());
                    }
                    sendingImage = false;
                }
                refreshControls();
            });
        }

        @Override
        public void onFrame(UdpTransceiver.FrameType type, int sequence, byte[] payload) {
            if (type == UdpTransceiver.FrameType.AUDIO_FRAME) {
                byte[] pcm = SignalModulator.demodulate(payload);
                waveforms = new Waveforms(pcm, payload);
                play(pcm);
            } else if (type == UdpTransceiver.FrameType.SSTV_FRAME) {
                waveforms = new Waveforms(null, payload);
                if (listenToTones) {
                    play(payload);
                } else if (playing) {
                    audio.stopPlayback();
                    playing = false;
                }
                if (decoder == null) {
                    decoder = new SstvCodec.Decoder();
                    show(() -> {
                        decodedRows.clear();
                        incomingImage = new WritableImage(SstvCodec.WIDTH, SstvCodec.HEIGHT);
                        incomingPreview.setImage(incomingImage);
                        receiveInfo.setText("Recibiendo líneas…");
                    });
                }
                decoder.accept(sequence - 1, payload, line -> show(() -> {
                    incomingImage.getPixelWriter().setPixels(0, line.row(), SstvCodec.WIDTH, 1,
                            PixelFormat.getIntArgbInstance(), line.argb(), 0, SstvCodec.WIDTH);
                    decodedRows.set(line.row());
                    receiveInfo.setText(decodedRows.cardinality() + " / " + SstvCodec.HEIGHT + " líneas");
                }));
            }
        }

        private void play(byte[] pcm) {
            if (!playing) {
                audio.startPlayback();
                playing = true;
            }
            audio.play(pcm);
        }

        @Override
        public void onCorruptPacket(int total) {
            show(() -> corrupt.setText(Integer.toString(total)));
        }

        @Override
        public void onEvent(String message) {
            show(() -> appendLog(message, message.startsWith("CRC32 incorrecto")));
        }

        @Override
        public void onClosed() {
            show(() -> {
                connection = null;
                voiceGesture = null;
                connectionWorker.execute(audio::close);
                refreshControls();
            });
        }

        private void onAudioError(AudioEngine.Device device, String message) {
            show(() -> {
                if (device == AudioEngine.Device.MICROPHONE && voiceGesture == this) {
                    releaseVoice();
                }
                appendLog((device == AudioEngine.Device.MICROPHONE ? "Micrófono · " : "Altavoces · ") + message, true);
            });
        }

        private void show(Runnable action) {
            Platform.runLater(() -> {
                if (connection == this) {
                    action.run();
                }
            });
        }

        @Override
        public void close() {
            if (radio != null) {
                radio.close();
            }
            audio.close();
        }
    }

    private record Waveforms(byte[] baseband, byte[] carrier) {}
    private record LogEntry(String time, String text, boolean warning) {}
    private record PreparedImage(String name, BufferedImage preview, List<byte[]> frames) {}

    private static final class SignalScope extends Region {
        private final Canvas canvas = new Canvas();
        private final Color color;
        private byte[] pcm;

        SignalScope(Color color) {
            this.color = color;
            setMinHeight(55);
            setPrefHeight(82);
            getChildren().add(canvas);
        }

        void display(byte[] next) {
            if (pcm != next) {
                pcm = next;
                draw();
            }
        }

        @Override
        protected void layoutChildren() {
            canvas.setWidth(getWidth());
            canvas.setHeight(getHeight());
            draw();
        }

        private void draw() {
            double width = canvas.getWidth();
            double height = canvas.getHeight();
            if (width <= 0 || height <= 0) {
                return;
            }
            GraphicsContext graphics = canvas.getGraphicsContext2D();
            graphics.setFill(Color.web("#0b1619"));
            graphics.fillRect(0, 0, width, height);
            graphics.setLineWidth(1);
            graphics.setStroke(Color.web("#1a2d31"));
            for (int column = 1; column < 16; column++) {
                double x = width * column / 16;
                graphics.strokeLine(x, 0, x, height);
            }
            for (int row = 1; row < 4; row++) {
                double y = height * row / 4;
                graphics.strokeLine(0, y, width, y);
            }
            if (pcm == null) {
                return;
            }
            double[] samples = SignalModulator.waveform(pcm);
            graphics.setStroke(color);
            graphics.setLineWidth(1.3);
            graphics.beginPath();
            graphics.moveTo(0, height / 2 - samples[0] * height * 0.45);
            for (int index = 1; index < samples.length; index++) {
                double x = width * index / (samples.length - 1);
                double y = height / 2 - samples[index] * height * 0.45;
                graphics.lineTo(x, y);
            }
            graphics.stroke();
        }
    }
}
