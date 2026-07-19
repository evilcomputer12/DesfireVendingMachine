package com.midnightbrewer.ui;

import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.fxml.FXML;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

public class UIController {

    @FXML
    private VBox selectionScreen;

    @FXML
    private VBox paymentScreen;

    @FXML
    private VBox preparationScreen;

    @FXML
    private Label selectedItemLabel;

    @FXML
    private ProgressBar progressBar;

    private String selectedDrink = "";

    @FXML
    public void initialize() {
        // Initialization if needed
    }

    @FXML
    private void handleSelectEspresso() {
        selectDrink("Espresso - $1.50");
    }

    @FXML
    private void handleSelectLatte() {
        selectDrink("Latte - $2.50");
    }

    @FXML
    private void handleSelectAmericano() {
        selectDrink("Americano - $2.00");
    }

    @FXML
    private void handleSelectCappuccino() {
        selectDrink("Cappuccino - $3.00");
    }

    @FXML
    private void handleSelectMocha() {
        selectDrink("Mocha - $3.50");
    }

    @FXML
    private void handleSelectMacchiato() {
        selectDrink("Macchiato - $3.50");
    }

    @FXML
    private void handleSelectFlatWhite() {
        selectDrink("Flat White - $3.00");
    }

    @FXML
    private void handleSelectColdBrew() {
        selectDrink("Cold Brew - $3.50");
    }

    private void selectDrink(String drink) {
        this.selectedDrink = drink;
        selectedItemLabel.setText("Selected: " + drink);
        System.out.println("LOG: User selected " + drink);
        
        // Switch screens
        selectionScreen.setVisible(false);
        paymentScreen.setVisible(true);

        // TODO: Start the RC522 polling logic here!
        startNfcPolling();
    }

    @FXML
    private void handleCancel() {
        System.out.println("LOG: Transaction cancelled by user.");
        // TODO: Stop RC522 polling here
        paymentScreen.setVisible(false);
        selectionScreen.setVisible(true);
    }

    @FXML
    private void simulateSuccessfulPayment() {
        handleSuccessfulPayment();
    }

    // --------------------------------------------------------
    // THE LOGIC ZONE (Where you take over)
    // --------------------------------------------------------

    private void startNfcPolling() {
        // GUIDANCE NOTE: This is where we will hook up your translated C code.
        System.out.println("LOG: Hardware polling started...");
        // Call your logic controller here (e.g., LogicController.startPolling())
    }

    public void handleSuccessfulPayment() {
        System.out.println("LOG: Payment successful. Deducting credits for " + selectedDrink);
        paymentScreen.setVisible(false);
        preparationScreen.setVisible(true);
        progressBar.setProgress(0);

        // Simulate brewing time with a JavaFX animation, then reset the machine
        Timeline timeline = new Timeline(
            new KeyFrame(Duration.ZERO, new KeyValue(progressBar.progressProperty(), 0)),
            new KeyFrame(Duration.seconds(4), new KeyValue(progressBar.progressProperty(), 1))
        );
        
        timeline.setOnFinished(e -> {
            System.out.println("LOG: Drink finished. Resetting UI.");
            preparationScreen.setVisible(false);
            selectionScreen.setVisible(true);
        });
        
        timeline.play();
    }
}
