/*
 * Starter code supplied for Adelaide University COMP3011 Assignment 2.
 * Students are free to modify this file for assessment purposes.
 * 
 * Authors:
 *   1. Simon Ratcliffe, in collaboration with GPT-5.6 Terra
 *   2. Yuxuan Bai (a2993969), with OpenAI Codex AI assistance.
 *
 * Copyright 2026 Simon Ratcliffe
 */
package comp3011;

import java.util.Locale;
import java.util.function.LongConsumer;

import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.AccessibleRole;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Line;
import javafx.scene.shape.Polygon;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;

/**
 * The view part of the video player model-view-controller architecture.
 *
 * <p>
 * This is the JavaFX presentation layer for the video player. It builds the
 * scene graph containing the video surface, playback controls, status display,
 * and tooltips. It exposes controls and display-update methods for
 * {@link VideoPlayerController}, but contains no playback logic (which all lives
 * in the controller).
 * </p>
 */
public class VideoPlayerView {
    private static final int ICON_SIZE = 28;
    private static final long MICROS_PER_SECOND = 1_000_000L;

    private final ImageView imageView = new ImageView();
    private final Group subtitleOverlay = new Group();
    private final Text subtitleText = new Text();
    private final Text[] subtitleOutline = new Text[8];
    private final Label statusLabel = new Label("No video loaded");
    private final Label subtitleStatusLabel = new Label();
    private final Slider playhead = new Slider(0, 1, 0);
    private final Label playbackTime = new Label("0:00 / --:--");
    private final Label tooltipLabel = new Label();
    private final Pane tooltipLayer = new Pane();
    private final Button openVideoButton = new Button("Open video…");
    private final StackPane videoPane;
    private final StackPane root;
    private final Button startOverButton;
    private final Button backFiveButton;
    private final Button pausePlayButton;
    private final Button forwardFiveButton;
    private final Button stopButton;
    private final Button audioButton;
    private final Button subtitleButton;
    private long durationUs;
    private long displayedSecond = -1;
    private boolean updatingPlayhead;

    public VideoPlayerView() {
        imageView.setPreserveRatio(true);
        imageView.setSmooth(false);

        videoPane = new StackPane(imageView, subtitleOverlay);
        videoPane.setStyle("-fx-background-color: black;");
        videoPane.setMinSize(0, 0);
        videoPane.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        imageView.fitWidthProperty().bind(videoPane.widthProperty());
        imageView.fitHeightProperty().bind(videoPane.heightProperty());
        configureSubtitles();

        startOverButton = iconButton(createLoopIcon(false, null));
        backFiveButton = iconButton(createLoopIcon(false, "5"));
        pausePlayButton = iconButton(createPauseIcon());
        forwardFiveButton = iconButton(createLoopIcon(true, "5"));
        stopButton = iconButton(createStopIcon());
        audioButton = iconButton(createAudioIcon(false));
        subtitleButton = iconButton(new Label("CC"));
        openVideoButton.setAccessibleText("Open video with English subtitles");
        setPlaybackControlsDisabled(true);
        subtitleStatusLabel.setVisible(false);
        subtitleStatusLabel.managedProperty().bind(subtitleStatusLabel.visibleProperty());

        HBox controls = new HBox(
                10,
                openVideoButton,
                startOverButton,
                backFiveButton,
                pausePlayButton,
                forwardFiveButton,
                stopButton,
                audioButton,
                subtitleButton,
                statusLabel,
                subtitleStatusLabel);

        controls.setPadding(new Insets(10));
        controls.setAlignment(Pos.CENTER_LEFT);

        playhead.setAccessibleText("Playback position");
        playbackTime.setMinWidth(85);
        HBox timeline = new HBox(10, playhead, playbackTime);
        timeline.setPadding(new Insets(7, 10, 0, 10));
        timeline.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(playhead, Priority.ALWAYS);

        BorderPane content = new BorderPane();
        content.setCenter(videoPane);
        content.setBottom(new VBox(timeline, controls));

        configureTooltipLabel();

        root = new StackPane(content, tooltipLayer);
        root.setFocusTraversable(true);
        setButtonTooltips();
    }

    public Parent getRoot() {
        return root;
    }

    public Button getOpenVideoButton() {
        return openVideoButton;
    }

    public Button getStartOverButton() {
        return startOverButton;
    }

    public Button getBackFiveButton() {
        return backFiveButton;
    }

    public Button getPausePlayButton() {
        return pausePlayButton;
    }

    public Button getForwardFiveButton() {
        return forwardFiveButton;
    }

    public Button getStopButton() {
        return stopButton;
    }

    public Button getAudioButton() {
        return audioButton;
    }
    
    public Button getSubtitleButton(){
        return  subtitleButton;
    }

    public void setStatus(String status) {
        statusLabel.setText(status);
    }

    public void setImage(Image image) {
        imageView.setImage(image);
    }

    void setSeekHandler(LongConsumer seek) {
        playhead.valueProperty().addListener((_, _, current) -> {
            updatePlaybackTime(current.doubleValue());
            if (!updatingPlayhead && !playhead.isValueChanging() && !playhead.isDisabled()) {
                seek.accept(Math.round(current.doubleValue() * MICROS_PER_SECOND));
            }
        });
        playhead.valueChangingProperty().addListener((_, _, dragging) -> {
            if (!dragging && !playhead.isDisabled()) {
                seek.accept(Math.round(playhead.getValue() * MICROS_PER_SECOND));
            }
        });
    }

    void setDuration(long durationUs) {
        this.durationUs = Math.max(0, durationUs);
        updatingPlayhead = true;
        playhead.setMax(durationUs > 0 ? durationUs / (double) MICROS_PER_SECOND : 1);
        if (durationUs <= 0) {
            playhead.setValue(0);
        }
        updatingPlayhead = false;
        playhead.setDisable(durationUs <= 0 || stopButton.isDisabled());
        displayedSecond = -1;
        updatePlaybackTime(playhead.getValue());
    }

    void setPlayhead(long timestampUs) {
        double position = Math.max(0, timestampUs) / (double) MICROS_PER_SECOND;
        if (durationUs == 0) {
            updatePlaybackTime(position);
            return;
        }
        if (!playhead.isValueChanging()) {
            updatingPlayhead = true;
            playhead.setValue(position);
            updatingPlayhead = false;
        }
    }

    private void updatePlaybackTime(double position) {
        long seconds = (long) position;
        if (seconds != displayedSecond) {
            displayedSecond = seconds;
            String total = durationUs > 0 ? formatTime(durationUs / MICROS_PER_SECOND) : "--:--";
            playbackTime.setText(formatTime(seconds) + " / " + total);
        }
    }

    private static String formatTime(long seconds) {
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60);
    }

    void setSubtitleProgress(SubtitleLoader.Progress progress) {
        String parts = progress.total() > 0 ? " (" + progress.completed() + "/" + progress.total() + ")" : "";
        String status = switch (progress.state()) {
            case OFF, READY -> "";
            case LOADING -> "Preparing subtitles" + parts + "…";
            case NO_AUDIO -> "No audio track";
            case RETRYING -> "Retrying subtitles" + parts + "…";
            case FAILED -> "Subtitles incomplete" + parts;
        };
        subtitleStatusLabel.setText(status);
        subtitleStatusLabel.setVisible(!status.isEmpty());
    }

    public void setSubtitleText(String text) {
        subtitleText.setText(text);
        layoutSubtitle();
    }

    private void configureSubtitles() {
        subtitleOverlay.setManaged(false);
        subtitleOverlay.setMouseTransparent(true);
        subtitleOverlay.setVisible(false);
        subtitleText.setId("subtitle-text");
        subtitleText.setFill(Color.WHITE);
        subtitleText.setTextAlignment(TextAlignment.CENTER);

        // Filled glyphs can use JavaFX's glyph cache, including when a cue changes.
        for (int index = 0; index < subtitleOutline.length; index++) {
            Text outline = new Text();
            outline.setAccessibleRole(AccessibleRole.NODE);
            outline.setFill(Color.BLACK);
            outline.setTextAlignment(TextAlignment.CENTER);
            outline.fontProperty().bind(subtitleText.fontProperty());
            outline.textProperty().bind(subtitleText.textProperty());
            subtitleOutline[index] = outline;
            subtitleOverlay.getChildren().add(outline);
        }
        subtitleOverlay.getChildren().add(subtitleText);
        resizeSubtitle(12);
        imageView.boundsInParentProperty().addListener((_, _, _) -> layoutSubtitle());
    }

    private void layoutSubtitle() {
        Bounds video = imageView.getBoundsInParent();
        if (subtitleText.getText().isEmpty() || imageView.getImage() == null
                || video.getWidth() <= 0 || video.getHeight() <= 0) {
            subtitleOverlay.setVisible(false);
            return;
        }

        double size = Math.clamp(video.getHeight() * 0.052, 12, 60);
        if (size != subtitleText.getFont().getSize()) {
            resizeSubtitle(size);
        }
        Bounds text = subtitleOverlay.getLayoutBounds();
        double scale = Math.min(1, video.getWidth() * 0.9 / text.getWidth());
        subtitleOverlay.setScaleX(scale);
        subtitleOverlay.setScaleY(scale);
        subtitleOverlay.relocate(video.getMinX() + (video.getWidth() - text.getWidth()) / 2,
                video.getMaxY() - video.getHeight() * 0.07 - text.getHeight() * (1 + scale) / 2);
        subtitleOverlay.setVisible(true);
    }

    private void resizeSubtitle(double size) {
        subtitleText.setFont(Font.font("SansSerif", FontWeight.BOLD, size));
        double radius = size / 12;
        for (int index = 0; index < subtitleOutline.length; index++) {
            double angle = index * Math.PI / 4;
            subtitleOutline[index].setTranslateX(radius * Math.cos(angle));
            subtitleOutline[index].setTranslateY(radius * Math.sin(angle));
        }
    }

    public void setVideoDisplaySize(double width, double height) {
        videoPane.setPrefSize(width, height);
    }

    public void showPauseAction() {
        pausePlayButton.setGraphic(createPauseIcon());
    }

    public void showPlayAction() {
        pausePlayButton.setGraphic(createPlayIcon());
    }

    public void showAudioEnabled(boolean audioEnabled) {
        audioButton.setGraphic(createAudioIcon(audioEnabled));
    }

    public void setPlaybackControlsDisabled(boolean disabled) {
        startOverButton.setDisable(disabled);
        backFiveButton.setDisable(disabled);
        pausePlayButton.setDisable(disabled);
        forwardFiveButton.setDisable(disabled);
        stopButton.setDisable(disabled);
        audioButton.setDisable(disabled);
        subtitleButton.setDisable(disabled);
        playhead.setDisable(disabled || durationUs <= 0);
    }

    // There are a bunch of tooltip handling methods starting from here. We want to use tooltips so that people with
    // mouse/trackpads can learn what the hotkeys are and hopefully just use them instead, because they are easy/fast.
    private void setButtonTooltips() {
        setButtonTooltip(startOverButton, "[HOME]");
        setButtonTooltip(backFiveButton, "[LEFT]");
        setButtonTooltip(pausePlayButton, "[SPACE]");
        setButtonTooltip(forwardFiveButton, "[RIGHT]");
        setButtonTooltip(stopButton, "[ESC]");
        setButtonTooltip(audioButton, "[A]");
        setButtonTooltip(openVideoButton, "[CTRL+O]");
        setButtonTooltip(subtitleButton, "[S]");
    }

    private void setButtonTooltip(Button button, String text) {
        button.setOnMouseEntered(event -> {
            showTooltip(text, event.getSceneX(), event.getSceneY());
        });
        button.setOnMouseMoved(event -> {
            if (tooltipLabel.isVisible()) {
                moveTooltip(event.getSceneX(), event.getSceneY());
            }
        });
        button.setOnMouseExited(_ -> hideTooltip());
        button.setOnMousePressed(_ -> hideTooltip());
    }

    private void configureTooltipLabel() {
        tooltipLayer.setMouseTransparent(true);
        tooltipLayer.getChildren().add(tooltipLabel);

        tooltipLabel.setVisible(false);
        tooltipLabel.setMouseTransparent(true);
        tooltipLabel.setStyle(
                "-fx-background-color: #ffffdc;"
                        + "-fx-border-color: #8a8a8a;"
                        + "-fx-border-width: 1;"
                        + "-fx-padding: 4 7 4 7;"
                        + "-fx-font-size: 12px;"
                        + "-fx-text-fill: black;");
    }

    private void showTooltip(String text, double sceneX, double sceneY) {
        tooltipLabel.setText(text);
        tooltipLabel.setVisible(true);
        tooltipLabel.applyCss();
        tooltipLabel.autosize();
        tooltipLayer.toFront();
        tooltipLabel.toFront();
        moveTooltip(sceneX, sceneY);
    }

    private void moveTooltip(double sceneX, double sceneY) {
        Point2D point = tooltipLayer.sceneToLocal(sceneX + 12, sceneY + 16);
        double maxX = Math.max(0, tooltipLayer.getWidth() - tooltipLabel.getWidth() - 2);
        double maxY = Math.max(0, tooltipLayer.getHeight() - tooltipLabel.getHeight() - 2);
        tooltipLabel.relocate(
                Math.max(0, Math.min(point.getX(), maxX)),
                Math.max(0, Math.min(point.getY(), maxY)));
    }

    private void hideTooltip() {
        tooltipLabel.setVisible(false);
    }

    private Button iconButton(Node icon) {
        Button button = new Button();
        icon.setMouseTransparent(true);
        button.setGraphic(icon);
        button.setPickOnBounds(true);
        button.setFocusTraversable(false);
        button.setMinSize(42, 36);
        button.setPrefSize(42, 36);
        return button;
    }

    // Decided to hand code icons here instead of using pre-drawn assets/resources, since the icons are simple and
    // universally recognised and this one they will look better on different DPI displays.
    private Node createLoopIcon(boolean clockwise, String text) {
        Pane pane = new Pane();
        pane.setMinSize(ICON_SIZE, ICON_SIZE);
        pane.setPrefSize(ICON_SIZE, ICON_SIZE);
        pane.setMaxSize(ICON_SIZE, ICON_SIZE);

        SVGPath loop = new SVGPath();
        loop.setContent(clockwise
                ? "M 10 6 "
                        + "C 7 7.5 5 10.5 5 14 "
                        + "C 5 19 9 23 14 23 "
                        + "C 19 23 23 19 23 14 " // ~3/4 circle bezier curve empty top right
                : "M 5 14 "
                        + "C 5 19 9 23 14 23 "
                        + "C 19 23 23 19 23 14 "
                        + "C 23 10.5 21 7.5 18 6"); // ~3/4 circle bezier curve empty top right
        loop.setFill(null);
        loop.setStroke(Color.BLACK);
        loop.setStrokeWidth(2.25);
        loop.setStrokeLineCap(StrokeLineCap.ROUND);

        Polygon arrowHead = clockwise
                ? new Polygon(18, 5, 11.5, 1.5, 11.5, 8.5)
                : new Polygon(10, 5, 16.5, 1.5, 16.5, 8.5);
        arrowHead.setFill(Color.BLACK);

        pane.getChildren().addAll(loop, arrowHead);

        if (text != null) {
            Label label = new Label(text);
            label.setAlignment(Pos.CENTER);
            label.setMinSize(ICON_SIZE, ICON_SIZE);
            label.setPrefSize(ICON_SIZE, ICON_SIZE);
            label.setMaxSize(ICON_SIZE, ICON_SIZE);
            label.setStyle("-fx-font-size: 11px; -fx-font-weight: bold; -fx-text-fill: black;");
            label.setMouseTransparent(true);
            pane.getChildren().add(label);
        }

        return pane;
    }

    // Simple two vertical bars 'pause' icon.
    private Canvas createPauseIcon() {
        Canvas canvas = new Canvas(ICON_SIZE, ICON_SIZE);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.BLACK);
        gc.fillRect(9, 7, 4, 14);
        gc.fillRect(16, 7, 4, 14);
        return canvas;
    }

    // Simple right facing equilateral triangle 'play' icon.
    private Canvas createPlayIcon() {
        Canvas canvas = new Canvas(ICON_SIZE, ICON_SIZE);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.BLACK);
        gc.fillPolygon(
                new double[] { 10, 10, 21 },
                new double[] { 7, 21, 14 },
                3);
        return canvas;
    }

    // Simple square 'stop' icon.
    private Canvas createStopIcon() {
        Canvas canvas = new Canvas(ICON_SIZE, ICON_SIZE);
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.setFill(Color.BLACK);
        gc.fillRect(9, 9, 11, 11);
        return canvas;
    }

    // Kind of speaker looking thing ...
    private Node createAudioIcon(boolean audioEnabled) {
        Pane pane = new Pane();
        pane.setMinSize(ICON_SIZE, ICON_SIZE);
        pane.setPrefSize(ICON_SIZE, ICON_SIZE);
        pane.setMaxSize(ICON_SIZE, ICON_SIZE);

        Polygon speaker = new Polygon(
                5, 11,
                10, 11,
                16, 6,
                16, 22,
                10, 17,
                5, 17);
        speaker.setFill(Color.BLACK);
        pane.getChildren().add(speaker);

        if (audioEnabled) {
            SVGPath innerWave = audioWave("M 18 11 C 20 12.5 20 15.5 18 17");
            SVGPath outerWave = audioWave("M 20.5 8 C 24 11.25 24 16.75 20.5 20");
            pane.getChildren().addAll(innerWave, outerWave);
        } else {
            Line slashDown = audioLine(19, 9, 25, 19);
            Line slashUp = audioLine(25, 9, 19, 19);
            pane.getChildren().addAll(slashDown, slashUp);
        }

        return pane;
    }

    // Little helper for different radius waves coming out of the speaker.
    private SVGPath audioWave(String content) {
        SVGPath wave = new SVGPath();
        wave.setContent(content);
        wave.setFill(null);
        wave.setStroke(Color.BLACK);
        wave.setStrokeWidth(2.0);
        wave.setStrokeLineCap(StrokeLineCap.ROUND);
        return wave;
    }

    // Helper to draw a cross over the speaker indicating that we're muted.
    private Line audioLine(double startX, double startY, double endX, double endY) {
        Line line = new Line(startX, startY, endX, endY);
        line.setStroke(Color.BLACK);
        line.setStrokeWidth(2.0);
        line.setStrokeLineCap(StrokeLineCap.ROUND);
        return line;
    }

    public void showSubtitlesEnabled(boolean enabled) {
        Label icon = new Label(enabled ? "CC" : "CC×");
        icon.setMouseTransparent(true);
        subtitleButton.setGraphic(icon);
        subtitleButton.setAccessibleText(enabled ? "Subtitles on" : "Subtitles off");
    }
}
