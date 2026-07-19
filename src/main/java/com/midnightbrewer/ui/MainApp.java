package com.midnightbrewer.ui;

import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

import java.net.URL;

public class MainApp extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        URL fxmlLocation = getClass().getResource("/MidnightBrewer.fxml");
        if (fxmlLocation == null) {
            System.err.println("Cannot find /MidnightBrewer.fxml. Make sure it is in src/main/resources/");
            System.exit(1);
        }
        Parent root = FXMLLoader.load(fxmlLocation);

        Scene scene = new Scene(root, 1024, 600);
        primaryStage.setTitle("The Midnight Brewer");
        
        // Strict Kiosk Mode
        primaryStage.initStyle(javafx.stage.StageStyle.UNDECORATED);
        primaryStage.setFullScreen(true);
        primaryStage.setFullScreenExitHint("");
        
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
