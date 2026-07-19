package com.midnightbrewer.ui;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.scene.Cursor;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.input.KeyCode;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import java.net.URL;

/**
 * Kiosk entry point.
 *
 * <p>Sized for a 1024x600 panel. Runs undecorated and fullscreen on the Pi;
 * press <kbd>Esc</kbd> to quit, which matters because an undecorated
 * fullscreen window with no exit path is genuinely hard to get out of on a
 * touchscreen with no keyboard shortcuts bound.
 */
public class MainApp extends Application {

    /*
     * Portrait. The panel is a 1024x600 MPI7010 mounted rotated, with labwc
     * applying `--transform 90`, so the logical screen the app sees is
     * 600x1024. The layout is measured at runtime rather than hardcoded, so
     * these are starting dimensions for a desktop window, not an assumption
     * the UI depends on.
     */
    private static final double WIDTH = 600;
    private static final double HEIGHT = 1024;

    /**
     * Set {@code -Dbrewer.windowed=true} to run in a normal window while
     * developing on a laptop.
     */
    private static final boolean WINDOWED =
            Boolean.getBoolean("brewer.windowed");

    private UIController controller;

    @Override
    public void start(Stage stage) throws Exception {
        URL fxml = getClass().getResource("/MidnightBrewer.fxml");
        if (fxml == null) {
            throw new IllegalStateException(
                    "MidnightBrewer.fxml not found on the classpath — "
                    + "expected it in src/main/resources/");
        }

        // Instance loader (not the static FXMLLoader.load) so we can keep the
        // controller and shut the reader down cleanly on exit.
        FXMLLoader loader = new FXMLLoader(fxml);
        Parent root = loader.load();
        controller = loader.getController();

        Scene scene = new Scene(root, WIDTH, HEIGHT, Color.web("#0D0D11"));

        /*
         * Hover styling is scoped to `.desktop` in the stylesheet and only
         * enabled here. Hiding the cursor is not enough on the kiosk: the
         * Xwayland pointer still exists at some coordinate and leaves whatever
         * tile it happens to sit on stuck in :hover, which reads as a
         * pre-selected drink. A touchscreen has no hover state to represent.
         */
        root.getStyleClass().add(WINDOWED ? "desktop" : "kiosk");

        scene.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                Platform.exit();
            }
        });

        stage.setTitle("The Midnight Brewer");
        stage.setScene(scene);

        if (!WINDOWED) {
            stage.initStyle(StageStyle.UNDECORATED);
            stage.setFullScreen(true);
            stage.setFullScreenExitHint("");
            // Xwayland parks a pointer in the middle of the screen, which sat
            // on a tile and left it stuck in :hover on the real panel. A
            // touchscreen kiosk has no cursor to show.
            scene.setCursor(Cursor.NONE);
        }

        stage.show();
    }

    @Override
    public void stop() {
        if (controller != null) {
            controller.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
