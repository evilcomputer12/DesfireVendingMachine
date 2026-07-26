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
 * The kiosk entry point. Given to you complete --- it is boilerplate, and the
 * interesting work is all in {@link UIController}.
 *
 * <p>Run it in a window while developing:
 * <pre>
 *   mvn javafx:run -Dbrewer.windowed=true
 * </pre>
 * Without that flag it goes fullscreen and undecorated, which is what you want
 * on the real panel and emphatically not what you want on a laptop.
 */
public class MainApp extends Application {

    /*
     * Portrait. The kiosk panel is a 1024x600 screen mounted rotated, with the
     * compositor applying a 90-degree transform, so the app sees 600x1024.
     * These are only starting dimensions -- the layout measures itself at
     * runtime, so the same build serves a desktop window too.
     */
    private static final double WIDTH = 600;
    private static final double HEIGHT = 1024;

    /** Set -Dbrewer.windowed=true to develop on a laptop. */
    private static final boolean WINDOWED = Boolean.getBoolean("brewer.windowed");

    private UIController controller;

    @Override
    public void start(Stage stage) throws Exception {
        URL fxml = getClass().getResource("/MidnightBrewer.fxml");
        if (fxml == null) {
            throw new IllegalStateException(
                    "MidnightBrewer.fxml not found on the classpath -- "
                    + "expected it in src/main/resources/");
        }

        // Instance loader (not the static FXMLLoader.load) so we can keep hold
        // of the controller and shut it down cleanly on exit.
        FXMLLoader loader = new FXMLLoader(fxml);
        Parent root = loader.load();
        controller = loader.getController();

        Scene scene = new Scene(root, WIDTH, HEIGHT, Color.web("#0D0D11"));

        /*
         * Hover styling is scoped to `.desktop` in the stylesheet. On a
         * touchscreen there is no hover state to represent -- and worse, the
         * Xwayland pointer parks at some coordinate and leaves whatever tile it
         * lands on stuck in :hover, which reads as a pre-selected drink.
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
            scene.setCursor(Cursor.NONE);
        }

        stage.show();
    }

    @Override
    public void stop() {
        // Called when the window closes. Without this the reader's RF field
        // could be left energised, which quietly cooks the module overnight.
        if (controller != null) {
            controller.shutdown();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
