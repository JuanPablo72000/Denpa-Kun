package com.denpakun;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
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
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

public final class MainApp extends Application {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final byte[] SILENCE = new byte[AudioEngine.FRAME_BYTES];
    private static final int LOG_LIMIT = 200;
    private static final String TALKING_STYLE = "talking";
    private static final String LOSS_STYLE = "has-loss";
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
    private final ComboBox<SignalModulator.Mode> modulation = new ComboBox<>();
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
    private final Label carrierLabel = new Label();
    private final ReceptionStrip receptionStrip = new ReceptionStrip();
    private final Label receptionStatus = new Label();
    private final Label sent = new Label("0");
    private final Label received = new Label("0");
    private final Label missing = new Label("0");
    private final Label dropped = new Label("0");
    private final Label corrupt = new Label("0");
    private final Label jitter = new Label("0.0 ms");
    private final BitSet decodedRows = new BitSet(SstvCodec.HEIGHT);
    private final BitSet affectedRows = new BitSet(SstvCodec.HEIGHT);
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
        stage.initStyle(StageStyle.UNDECORATED);
        stage.setTitle("Denpa-Kun · Transceptor P2P");
        String iconUrl = Objects.requireNonNull(getClass().getResource("denpa-kun.png")).toExternalForm();
        Image appIcon = new Image(iconUrl, 128, 128, true, true);
        stage.getIcons().setAll(new Image(iconUrl, 16, 16, true, true),
                new Image(iconUrl, 32, 32, true, true), appIcon);
        ImageView brandIcon = new ImageView(appIcon);
        brandIcon.setFitWidth(48);
        brandIcon.setFitHeight(48);
        brandIcon.setPreserveRatio(true);
        Label brand = new Label("Denpa-Kun");
        brand.getStyleClass().add("brand");
        Label subtitle = new Label("Transceptor P2P · Voz y SSTV");
        subtitle.getStyleClass().add("muted");
        channel.getStyleClass().setAll("channel-state", "offline");
        connectionInfo.getStyleClass().add("muted");
        HBox identity = new HBox(12, brandIcon, new VBox(3, brand, subtitle));
        identity.setAlignment(Pos.CENTER_LEFT);
        VBox station = new VBox(6, channel, connectionInfo);
        station.setAlignment(Pos.CENTER_RIGHT);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(16, identity, spacer, station);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("header");

        WindowChrome titleBar = new WindowChrome(stage);
        BorderPane root = new BorderPane();
        root.getStyleClass().add("app-window");
        root.setTop(new VBox(titleBar, header));
        root.setCenter(buildWorkspace(stage));

        Scene scene = new Scene(root);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("styles.css")).toExternalForm());
        titleBar.installResizeHandlers(scene);
        setupPtt(scene, stage);
        refreshControls();
        updateDashboard();
        dashboard = new Timeline(new KeyFrame(Duration.millis(50), event -> updateDashboard()));
        dashboard.setCycleCount(Timeline.INDEFINITE);
        dashboard.play();
        stage.setScene(scene);
        WindowChrome.sizeForScreen(stage);
        stage.show();
        peerInput.requestFocus();
    }

    private ScrollPane buildWorkspace(Stage stage) {
        VBox link = buildLinkPanel();
        VBox faultPanel = buildFaultPanel();
        VBox log = buildLogPanel();
        VBox signal = buildSignalPanel();
        VBox images = buildImagePanel(stage);

        VBox left = new VBox(14, link, faultPanel, log);
        left.setPrefWidth(310);
        left.setMinWidth(294);
        left.setMaxWidth(Region.USE_PREF_SIZE);
        VBox center = new VBox(14, signal, images);
        center.setMinWidth(466);
        center.setPrefWidth(540);
        VBox right = new VBox(14);
        right.setMinWidth(420);
        right.setPrefWidth(450);
        VBox.setVgrow(signal, Priority.ALWAYS);
        VBox.setVgrow(images, Priority.ALWAYS);
        VBox.setVgrow(log, Priority.ALWAYS);
        HBox.setHgrow(center, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox workspace = new HBox(14, left, center);
        workspace.setAlignment(Pos.TOP_LEFT);
        workspace.getStyleClass().add("workspace");
        workspace.setMinHeight(Region.USE_PREF_SIZE);

        ScrollPane scroll = new ScrollPane(workspace);
        scroll.setFitToWidth(true);
        scroll.setFitToHeight(true);
        scroll.getStyleClass().add("workspace-scroll");
        scroll.widthProperty().addListener((observable, previous, width) -> {
            boolean wide = width.doubleValue() >= 1_270;
            if (wide && images.getParent() == center) {
                left.getChildren().remove(log);
                center.getChildren().remove(images);
                right.getChildren().addAll(images, log);
                workspace.getChildren().add(right);
            } else if (!wide && images.getParent() == right) {
                workspace.getChildren().remove(right);
                right.getChildren().clear();
                center.getChildren().add(images);
                left.getChildren().add(log);
            }
        });
        return scroll;
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
        Label modulationLabel = new Label("Modulación de voz");
        modulationLabel.setLabelFor(modulation);
        modulationLabel.getStyleClass().add("muted");
        modulation.getItems().setAll(SignalModulator.Mode.values());
        modulation.setValue(SignalModulator.Mode.DSB_SC);
        modulation.setMaxWidth(Double.MAX_VALUE);
        modulation.setTooltip(new Tooltip());
        modulation.getTooltip().setText(modulation.getValue().description());
        modulation.valueProperty().addListener((observable, before, mode) -> {
            if (mode != null) {
                modulation.getTooltip().setText(mode.description());
                updateDashboard();
            }
        });
        ptt.setMaxWidth(Double.MAX_VALUE);
        ptt.getStyleClass().add("ptt");
        Label hint = new Label("Atajo · Barra espaciadora");
        hint.getStyleClass().add("muted");
        return panel("Enlace de voz", peerLabel, endpoint, new VBox(4, modulationLabel, modulation), ptt, hint);
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
        events.setPrefHeight(96);
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
        VBox.setVgrow(events, Priority.ALWAYS);
        return panel("Eventos", events);
    }

    private VBox buildSignalPanel() {
        HBox metrics = new HBox(6, metric("Enviados", sent, "Datagramas enviados por UDP"),
                metric("Válidos", received, "Datagramas recibidos con CRC válido"),
                metric("Descartes TX", dropped, "Datagramas descartados por el simulador en este emisor"),
                metric("Perdidos", missing, "Tramas de voz o SSTV ausentes tras esperar el reordenamiento"),
                metric("Corruptos", corrupt, "Datagramas recibidos con CRC incorrecto"),
                metric("Jitter RX", jitter, "Variación estimada del espaciado entre tramas recibidas"));
        receptionStatus.getStyleClass().add("reception-status");
        HBox key = new HBox(12, legend("Válida", "frame-valid"), legend("Perdida", "frame-lost"),
                legend("CRC incorrecto", "frame-corrupt"));
        VBox reception = new VBox(4, receptionStatus, receptionStrip, key);
        Tooltip.install(receptionStrip, new Tooltip("Últimas 80 tramas de la recepción. Cada bloque abarca 20 ms."));
        return panel("Osciloscopio", metrics, scope(new Label("Voz en banda base"), voice, "voice-label"),
                scope(carrierLabel, carrier, "carrier-label"), reception);
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
        StackPane transmit = imageScreen(outgoingPreview);
        StackPane receive = imageScreen(incomingPreview);
        receiveInfo.getStyleClass().add("image-receive-info");
        FlowPane imageKey = new FlowPane(10, 3, legend("Píxeles sin datos", "frame-lost"),
                legend("Sin pulso de sincronía", "frame-corrupt"));

        GridPane images = new GridPane(10, 5);
        ColumnConstraints outgoingColumn = new ColumnConstraints();
        outgoingColumn.setPercentWidth(50);
        ColumnConstraints incomingColumn = new ColumnConstraints();
        incomingColumn.setPercentWidth(50);
        images.getColumnConstraints().addAll(outgoingColumn, incomingColumn);
        images.addRow(0, new Label("Vista previa"), new Label("Recepción"));
        images.addRow(1, transmit, receive);
        images.addRow(2, imageCaption(imageInfo), imageCaption(receiveInfo));
        images.add(imageKey, 1, 3);
        GridPane.setVgrow(transmit, Priority.ALWAYS);
        GridPane.setVgrow(receive, Priority.ALWAYS);
        VBox.setVgrow(images, Priority.ALWAYS);
        return panel("Imágenes SSTV", actions, images, imageProgress);
    }

    private static StackPane imageScreen(ImageView view) {
        view.setManaged(false);
        view.setPreserveRatio(true);
        view.setSmooth(false);
        Label empty = new Label("Sin imagen");
        empty.getStyleClass().add("muted");
        empty.visibleProperty().bind(view.imageProperty().isNull());
        StackPane screen = new StackPane(empty, view) {
            @Override
            protected void layoutChildren() {
                super.layoutChildren();
                Insets insets = getInsets();
                double width = Math.max(1, getWidth() - insets.getLeft() - insets.getRight());
                double height = Math.max(1, getHeight() - insets.getTop() - insets.getBottom());
                view.setFitWidth(width);
                view.setFitHeight(height);
                view.relocate(insets.getLeft() + (width - view.getLayoutBounds().getWidth()) / 2,
                        insets.getTop() + (height - view.getLayoutBounds().getHeight()) / 2);
            }
        };
        screen.setMinHeight(144);
        screen.getStyleClass().add("image-screen");
        view.imageProperty().addListener((observable, previous, image) -> screen.requestLayout());
        return screen;
    }

    private static Label imageCaption(Label caption) {
        caption.getStyleClass().add("muted");
        caption.setWrapText(true);
        caption.setTooltip(new Tooltip());
        caption.getTooltip().textProperty().bind(caption.textProperty());
        return caption;
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

    private static VBox scope(Label label, SignalScope scope, String colorClass) {
        label.getStyleClass().add(colorClass);
        VBox.setVgrow(scope, Priority.ALWAYS);
        VBox content = new VBox(4, label, scope);
        VBox.setVgrow(content, Priority.ALWAYS);
        return content;
    }

    private static HBox legend(String title, String colorClass) {
        Region dot = new Region();
        dot.getStyleClass().addAll("legend-dot", colorClass);
        Label label = new Label(title);
        label.getStyleClass().add("muted");
        HBox item = new HBox(5, dot, label);
        item.setAlignment(Pos.CENTER_LEFT);
        return item;
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
                    || scene.getFocusOwner() instanceof Button || scene.getFocusOwner() instanceof CheckBox
                    || scene.getFocusOwner() instanceof ComboBox<?>))) {
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
        SignalModulator.Mode mode = modulation.getValue();
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
                byte[] encoded = SignalModulator.modulate(pcm, mode);
                current.waveforms = new Waveforms(capture == null ? null : pcm, encoded, mode);
                return new UdpTransceiver.CapturedFrame(encoded, timestamp);
            }

            @Override
            public void close() {
                current.audio.stopCapture();
            }
        };
        if (current.radio.startTransmission(UdpTransceiver.FrameType.AUDIO_FRAME, mode, source)) {
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
            current.waveforms = new Waveforms(null, block, null);
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
        modulation.setDisable(state != ChannelState.IDLE);
        ptt.setDisable(!ready || state == ChannelState.RECEIVING || (state == ChannelState.TRANSMITTING && !talking));
        var pttStyles = ptt.getStyleClass();
        if (talking && !pttStyles.contains(TALKING_STYLE)) {
            pttStyles.add(TALKING_STYLE);
        } else if (!talking) {
            pttStyles.remove(TALKING_STYLE);
        }
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
        ReceptionHistory history = current == null ? null : current.receptionHistory;
        ReceptionSnapshot reception = history == null ? null : history.snapshot();
        receptionStrip.display(reception);
        receptionStatus.setText(reception == null ? "Recepción · En espera de tramas" : reception.summary());
        setLossHighlight(receptionStatus, reception != null && reception.lost() > 0);
        SignalModulator.Mode mode = modulation.getValue();
        if (sample != null) {
            mode = sample.mode();
        } else if (history != null && current.radio != null && current.radio.state() == ChannelState.RECEIVING) {
            mode = history.mode;
        }
        carrierLabel.setText(mode == null ? "Tonos SSTV" : "Señal modulada · " + mode.displayName());
        if (current == null || current.radio == null) {
            sent.setText("0");
            received.setText("0");
            missing.setText("0");
            setLossHighlight(missing, false);
            dropped.setText("0");
            corrupt.setText("0");
            jitter.setText("0.0 ms");
            return;
        }
        UdpTransceiver.Stats stats = current.radio.stats();
        sent.setText(Long.toString(stats.sent()));
        received.setText(Long.toString(stats.received()));
        missing.setText(Long.toString(stats.missing()));
        setLossHighlight(missing, stats.missing() > 0);
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

    private void showImageStatus(boolean incomplete) {
        receiveInfo.setText(decodedRows.cardinality() + " / " + SstvCodec.HEIGHT + " líneas con datos · "
                + affectedRows.cardinality() + " afectadas" + (incomplete ? " · Incompleta" : ""));
        setLossHighlight(receiveInfo, !affectedRows.isEmpty());
    }

    private static void setLossHighlight(Label label, boolean highlighted) {
        var styles = label.getStyleClass();
        if (highlighted && !styles.contains(LOSS_STYLE)) {
            styles.add(LOSS_STYLE);
        } else if (!highlighted) {
            styles.remove(LOSS_STYLE);
        }
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
        private volatile ReceptionHistory receptionHistory;
        private volatile int imageFrame;
        private boolean sendingImage;
        private boolean playing;
        private SstvCodec.Decoder decoder;
        private int lastImageSequence;

        @Override
        public void onStateChanged(ChannelState state) {
            boolean finishedImageReception = state == ChannelState.IDLE && decoder != null;
            boolean incompleteImage = lastImageSequence < SstvCodec.IMAGE_FRAMES;
            if (state == ChannelState.RECEIVING) {
                audio.stopPlayback();
                playing = false;
            } else {
                decoder = null;
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
                if (finishedImageReception) {
                    showImageStatus(incompleteImage);
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
        public void onSessionStarted(UdpTransceiver.FrameType type, SignalModulator.Mode mode) {
            boolean image = type == UdpTransceiver.FrameType.SSTV_FRAME;
            receptionHistory = new ReceptionHistory(image ? "SSTV" : "Voz", image ? null : mode);
            if (image) {
                decoder = new SstvCodec.Decoder();
                lastImageSequence = 0;
                show(() -> {
                    decodedRows.clear();
                    affectedRows.clear();
                    incomingImage = new WritableImage(SstvCodec.WIDTH, SstvCodec.HEIGHT);
                    incomingPreview.setImage(incomingImage);
                    showImageStatus(false);
                });
            }
        }

        @Override
        public void onFrame(UdpTransceiver.FrameType type, int sequence, byte[] payload, SignalModulator.Mode mode) {
            receptionHistory.add(FrameStatus.VALID, 1);
            if (type == UdpTransceiver.FrameType.AUDIO_FRAME) {
                byte[] pcm = SignalModulator.demodulate(payload, mode);
                waveforms = new Waveforms(pcm, payload, mode);
                play(pcm);
            } else if (type == UdpTransceiver.FrameType.SSTV_FRAME) {
                waveforms = new Waveforms(null, payload, null);
                if (listenToTones) {
                    play(payload);
                } else if (playing) {
                    audio.stopPlayback();
                    playing = false;
                }
                lastImageSequence = sequence;
                List<SstvCodec.DecodedLine> lines = new ArrayList<>();
                decoder.accept(sequence - 1, payload, lines::add);
                showImageLines(lines);
            }
        }

        @Override
        public void onFrameGap(UdpTransceiver.FrameType type, int firstSequence, int frameCount, boolean corrupt) {
            receptionHistory.add(corrupt ? FrameStatus.CORRUPT : FrameStatus.LOST, frameCount);
            if (type == UdpTransceiver.FrameType.SSTV_FRAME) {
                lastImageSequence = firstSequence + frameCount - 1;
                List<SstvCodec.DecodedLine> lines = new ArrayList<>();
                decoder.markUnavailable(firstSequence - 1, frameCount, lines::add);
                showImageLines(lines);
            }
        }

        private void showImageLines(List<SstvCodec.DecodedLine> lines) {
            if (lines.isEmpty()) {
                return;
            }
            show(() -> {
                for (SstvCodec.DecodedLine line : lines) {
                    incomingImage.getPixelWriter().setPixels(0, line.row(), SstvCodec.WIDTH, 1,
                            PixelFormat.getIntArgbInstance(), line.argb(), 0, SstvCodec.WIDTH);
                    decodedRows.set(line.row(), line.decodedPixels() > 0);
                    affectedRows.set(line.row(), line.unavailablePixels() > 0 || line.missingSync());
                }
                showImageStatus(false);
            });
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

    private record Waveforms(byte[] baseband, byte[] carrier, SignalModulator.Mode mode) {}
    private record LogEntry(String time, String text, boolean warning) {}
    private record PreparedImage(String name, BufferedImage preview, List<byte[]> frames) {}

    private enum FrameStatus {
        VALID("#69dfbe"), LOST("#ff4ca8"), CORRUPT("#ffc45a");

        private final Color color;

        FrameStatus(String color) {
            this.color = Color.web(color);
        }
    }

    private record ReceptionSnapshot(String summary, long lost, List<FrameStatus> frames) {}

    private static final class ReceptionHistory {
        private static final int VISIBLE_FRAMES = 80;

        private final String media;
        private final SignalModulator.Mode mode;
        private final ArrayDeque<FrameStatus> frames = new ArrayDeque<>(VISIBLE_FRAMES);
        private long lost;
        private long corrupt;
        private ReceptionSnapshot snapshot;

        ReceptionHistory(String media, SignalModulator.Mode mode) {
            this.media = media;
            this.mode = mode;
        }

        synchronized void add(FrameStatus status, int count) {
            switch (status) {
                case LOST -> lost += count;
                case CORRUPT -> corrupt += count;
                case VALID -> { }
            }
            int visible = Math.min(VISIBLE_FRAMES, count);
            while (frames.size() + visible > VISIBLE_FRAMES) {
                frames.removeFirst();
            }
            for (int index = 0; index < visible; index++) {
                frames.addLast(status);
            }
            snapshot = null;
        }

        synchronized ReceptionSnapshot snapshot() {
            if (snapshot == null) {
                snapshot = new ReceptionSnapshot(media + " · " + lost + " perdidas · " + corrupt
                        + " con CRC incorrecto", lost, List.copyOf(frames));
            }
            return snapshot;
        }
    }

    private static final class ReceptionStrip extends Region {
        private static final Color EMPTY = Color.web("#263a3f");
        private final Canvas canvas = new Canvas();
        private ReceptionSnapshot reception;

        ReceptionStrip() {
            setMinHeight(14);
            setPrefHeight(14);
            getChildren().add(canvas);
        }

        void display(ReceptionSnapshot next) {
            if (reception != next) {
                reception = next;
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
            double cell = canvas.getWidth() / ReceptionHistory.VISIBLE_FRAMES;
            GraphicsContext graphics = canvas.getGraphicsContext2D();
            graphics.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
            List<FrameStatus> frames = reception == null ? List.of() : reception.frames();
            for (int index = 0; index < ReceptionHistory.VISIBLE_FRAMES; index++) {
                graphics.setFill(index < frames.size() ? frames.get(index).color : EMPTY);
                graphics.fillRoundRect(index * cell, 0, Math.max(0, cell - 1), canvas.getHeight(), 2, 2);
            }
        }
    }

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
