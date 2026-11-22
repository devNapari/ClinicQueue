package com.clinicqueue;

import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.image.Image;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Screen;
import javafx.stage.Stage;
import netscape.javascript.JSObject;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * ClinicQueue in its own native window, with the web pages shown by JavaFX's built-in WebView (no browser).
 *
 * This is the only class that needs JavaFX. It is compiled by the desktop build (see
 * packaging/windows/build-desktop-installer.bat) and found by {@link DesktopWindow} by name, so the plain
 * JDK-only build keeps working without it.
 */
public final class FxWindow {

    private FxWindow() {}

    /** Kept in a field because JavaFX holds only a weak reference to objects handed to the page. */
    private static final Bridge BRIDGE = new Bridge();

    /** What the pages can call as window.cqDesktop. Must stay public for the page to reach it. */
    public static final class Bridge {
        /** The WebView has no clipboard API for pages, so the "Copy link" button asks us. */
        public void copy(String text) {
            ClipboardContent content = new ClipboardContent();
            content.putString(text == null ? "" : text);
            Clipboard.getSystemClipboard().setContent(content);
        }
    }

    /**
     * Opens the window and returns once it is on screen. Throws if JavaFX cannot start (no display, missing
     * native files), so the caller can fall back to something else.
     *
     * @param ownsServer true when this program runs the server: closing the window then asks to quit
     * @param onClosed   called after the window is closed (and, if ownsServer, quitting was confirmed)
     */
    public static void open(String url, Path userData, boolean ownsServer, Runnable onClosed) throws Exception {
        CountDownLatch shown = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Runnable build = () -> {
            try {
                show(url, userData, ownsServer, onClosed);
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                shown.countDown();
            }
        };
        Platform.setImplicitExit(false);
        try {
            Platform.startup(build);
        } catch (IllegalStateException alreadyStarted) {
            Platform.runLater(build);
        }
        if (!shown.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("The JavaFX window did not start in time");
        if (failure.get() != null) throw new Exception(failure.get());
    }

    private static void show(String url, Path userData, boolean ownsServer, Runnable onClosed) throws Exception {
        Stage stage = new Stage();
        stage.setTitle("ClinicQueue");
        stage.getIcons().add(new Image(new ByteArrayInputStream(DesktopWindow.iconPng())));

        WebView view = new WebView();
        view.setContextMenuEnabled(false);
        WebEngine engine = view.getEngine();

        // Keep what the pages store (for example a patient's ticket) between runs.
        Files.createDirectories(userData);
        engine.setUserDataDirectory(userData.toFile());

        // A WebView ignores alert() and confirm() unless the program answers them. The pages use both.
        engine.setOnAlert(e -> {
            Alert a = new Alert(Alert.AlertType.INFORMATION, e.getData(), ButtonType.OK);
            a.initOwner(stage);
            a.setTitle("ClinicQueue");
            a.setHeaderText(null);
            a.showAndWait();
        });
        engine.setConfirmHandler(message -> ask(stage, null, message));

        engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            if (state == javafx.concurrent.Worker.State.SUCCEEDED) {
                try {
                    ((JSObject) engine.executeScript("window")).setMember("cqDesktop", BRIDGE);
                } catch (RuntimeException ignored) {
                    // the page then falls back to selecting the text
                }
            }
        });
        engine.titleProperty().addListener((obs, old, title) ->
                stage.setTitle(title == null || title.isBlank() ? "ClinicQueue" : title));

        Scene scene = new Scene(view);
        scene.setOnKeyPressed(ev -> {
            if (ev.getCode() == KeyCode.F5) engine.reload();
            if (ev.isControlDown()) {
                switch (ev.getCode()) {
                    case PLUS, EQUALS, ADD -> view.setZoom(Math.min(3.0, view.getZoom() + 0.1));
                    case MINUS, SUBTRACT -> view.setZoom(Math.max(0.5, view.getZoom() - 0.1));
                    case DIGIT0, NUMPAD0 -> view.setZoom(1.0);
                    default -> { }
                }
            }
        });
        stage.setScene(scene);

        Rectangle2D screen = Screen.getPrimary().getVisualBounds();
        stage.setWidth(Math.min(1280, screen.getWidth() * 0.92));
        stage.setHeight(Math.min(860, screen.getHeight() * 0.92));
        stage.setMinWidth(Math.min(480, screen.getWidth()));
        stage.setMinHeight(Math.min(520, screen.getHeight()));
        stage.centerOnScreen();

        stage.setOnCloseRequest(ev -> {
            ev.consume();
            if (ownsServer && !ask(stage, "Quit ClinicQueue?",
                    "Patients will not be able to reach it until you start it again.")) {
                return;
            }
            stage.hide();
            Platform.exit();
            onClosed.run();
        });

        engine.load(url);
        stage.show();
    }

    private static boolean ask(Stage owner, String header, String text) {
        Alert a = new Alert(Alert.AlertType.CONFIRMATION, text, ButtonType.YES, ButtonType.NO);
        a.initOwner(owner);
        a.setTitle("ClinicQueue");
        a.setHeaderText(header);
        return a.showAndWait().filter(b -> b == ButtonType.YES).isPresent();
    }
}
