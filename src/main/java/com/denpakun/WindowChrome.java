package com.denpakun;

import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polyline;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.Shape;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;

final class WindowChrome extends HBox {
    private static final double RESIZE_MARGIN = 6;

    private final Stage stage;
    private final Group maximizeIcon = icon(new Rectangle(10, 10));
    private final Group restoreIcon = icon(new Rectangle(0, 3, 8, 8),
            new Polyline(3, 3, 3, 0, 11, 0, 11, 8, 8, 8));
    private final Button maximize = windowButton("Maximizar", maximizeIcon);
    private double dragX;
    private double dragY;
    private Resize resize;
    private Rectangle2D restoreBounds;

    WindowChrome(Stage stage) {
        this.stage = stage;
        getStyleClass().add("title-bar");
        setAlignment(Pos.CENTER_LEFT);

        Label title = new Label(stage.getTitle());
        title.getStyleClass().add("window-title");
        StackPane handle = new StackPane(title);
        handle.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(handle, Priority.ALWAYS);
        handle.setOnMousePressed(this::startMove);
        handle.setOnMouseDragged(this::move);

        Button minimize = windowButton("Minimizar", icon(new Line(0, 0, 10, 0)));
        minimize.setOnAction(event -> stage.setIconified(true));
        maximize.setOnAction(event -> toggleMaximized());
        Button close = windowButton("Cerrar", icon(new Line(0, 0, 10, 10), new Line(10, 0, 0, 10)));
        close.getStyleClass().add("window-close");
        close.setOnAction(event -> stage.fireEvent(new WindowEvent(stage, WindowEvent.WINDOW_CLOSE_REQUEST)));
        getChildren().addAll(handle, minimize, maximize, close);
    }

    static void sizeForScreen(Stage stage) {
        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        double width = Math.min(1_480, screen.getWidth() * 0.97);
        double height = Math.min(690, screen.getHeight() * 0.93);
        stage.setMinWidth(Math.min(850, width));
        stage.setMinHeight(Math.min(430, height));
        stage.setWidth(width);
        stage.setHeight(height);
        stage.setX(screen.getMinX() + (screen.getWidth() - width) / 2);
        stage.setY(screen.getMinY() + (screen.getHeight() - height) / 2);
    }

    void installResizeHandlers(Scene scene) {
        scene.addEventFilter(MouseEvent.MOUSE_MOVED, event -> {
            Cursor cursor = restoreBounds == null ? edgeAt(scene, event).cursor() : null;
            scene.setCursor(cursor);
        });
        scene.addEventHandler(MouseEvent.MOUSE_EXITED, event -> scene.setCursor(null));
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.PRIMARY && restoreBounds == null) {
                Edge edge = edgeAt(scene, event);
                if (edge.cursor() != null) {
                    resize = new Resize(edge, event.getScreenX(), event.getScreenY(), stage.getX(), stage.getY(),
                            stage.getWidth(), stage.getHeight());
                    event.consume();
                }
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            if (resize != null) {
                resize.apply(stage, event);
                event.consume();
            }
        });
        scene.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (resize != null && event.getButton() == MouseButton.PRIMARY) {
                resize = null;
                scene.setCursor(edgeAt(scene, event).cursor());
                event.consume();
            }
        });
    }

    private void startMove(MouseEvent event) {
        if (event.getButton() == MouseButton.PRIMARY) {
            if (event.getClickCount() == 2) {
                toggleMaximized();
            }
            dragX = event.getSceneX();
            dragY = event.getSceneY();
        }
    }

    private void move(MouseEvent event) {
        if (event.isPrimaryButtonDown() && !event.isStillSincePress()) {
            if (restoreBounds != null) {
                double pointerRatio = dragX / stage.getWidth();
                toggleMaximized();
                dragX = pointerRatio * stage.getWidth();
            }
            stage.setX(event.getScreenX() - dragX);
            stage.setY(event.getScreenY() - dragY);
        }
    }

    private void toggleMaximized() {
        Rectangle2D bounds = restoreBounds;
        boolean maximizing = bounds == null;
        if (maximizing) {
            restoreBounds = new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
            double centerX = stage.getX() + stage.getWidth() / 2;
            double centerY = stage.getY() + stage.getHeight() / 2;
            bounds = Screen.getScreensForRectangle(centerX, centerY, 1, 1).stream()
                    .findFirst().orElse(Screen.getPrimary()).getVisualBounds();
        } else {
            restoreBounds = null;
        }
        stage.setX(bounds.getMinX());
        stage.setY(bounds.getMinY());
        stage.setWidth(bounds.getWidth());
        stage.setHeight(bounds.getHeight());
        maximize.setGraphic(maximizing ? restoreIcon : maximizeIcon);
        String text = maximizing ? "Restaurar" : "Maximizar";
        maximize.setAccessibleText(text);
        maximize.getTooltip().setText(text);
    }

    private static Edge edgeAt(Scene scene, MouseEvent event) {
        double x = event.getSceneX();
        double y = event.getSceneY();
        int horizontal = x < RESIZE_MARGIN ? -1 : x >= scene.getWidth() - RESIZE_MARGIN ? 1 : 0;
        int vertical = y < RESIZE_MARGIN ? -1 : y >= scene.getHeight() - RESIZE_MARGIN ? 1 : 0;
        return new Edge(horizontal, vertical);
    }

    private static Group icon(Shape... shapes) {
        for (Shape shape : shapes) {
            shape.getStyleClass().add("window-icon");
        }
        return new Group(shapes);
    }

    private static Button windowButton(String name, Node icon) {
        Button button = new Button("", icon);
        button.getStyleClass().add("window-button");
        button.setAccessibleText(name);
        button.setTooltip(new Tooltip(name));
        return button;
    }

    private record Edge(int horizontal, int vertical) {
        Cursor cursor() {
            return switch (horizontal) {
                case -1 -> switch (vertical) {
                    case -1 -> Cursor.NW_RESIZE;
                    case 1 -> Cursor.SW_RESIZE;
                    default -> Cursor.W_RESIZE;
                };
                case 1 -> switch (vertical) {
                    case -1 -> Cursor.NE_RESIZE;
                    case 1 -> Cursor.SE_RESIZE;
                    default -> Cursor.E_RESIZE;
                };
                default -> switch (vertical) {
                    case -1 -> Cursor.N_RESIZE;
                    case 1 -> Cursor.S_RESIZE;
                    default -> null;
                };
            };
        }
    }

    private record Resize(Edge edge, double mouseX, double mouseY, double x, double y, double width, double height) {
        void apply(Stage stage, MouseEvent event) {
            if (edge.horizontal() != 0) {
                double newWidth = Math.max(stage.getMinWidth(), width + edge.horizontal() * (event.getScreenX() - mouseX));
                stage.setWidth(newWidth);
                if (edge.horizontal() < 0) {
                    stage.setX(x + width - newWidth);
                }
            }
            if (edge.vertical() != 0) {
                double newHeight = Math.max(stage.getMinHeight(), height + edge.vertical() * (event.getScreenY() - mouseY));
                stage.setHeight(newHeight);
                if (edge.vertical() < 0) {
                    stage.setY(y + height - newHeight);
                }
            }
        }
    }
}
