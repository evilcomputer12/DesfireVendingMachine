package com.midnightbrewer.ui;

import com.midnightbrewer.card.CardException;
import com.midnightbrewer.model.Drink;
import com.midnightbrewer.model.DrinkCatalog;
import com.midnightbrewer.payment.PaymentListener;
import com.midnightbrewer.payment.PaymentReceipt;
import com.midnightbrewer.payment.DesfirePaymentTerminal;
import com.midnightbrewer.payment.PaymentTerminal;
import com.midnightbrewer.payment.SimulatedPaymentTerminal;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.List;

/**
 * Drives the kiosk's screen flow and mediates between the UI and the
 * {@link PaymentTerminal}.
 *
 * <p>Note what this class does <em>not</em> contain: no cryptography, no SPI,
 * no APDUs. It depends only on the {@code PaymentTerminal} interface, so
 * swapping the simulator for a real DESFire reader requires changing exactly
 * one line ({@link #createTerminal()}) and nothing else in the UI.
 */
public class UIController {

    /**
     * Columns in the drink grid. Three works in both orientations: it fills a
     * 1024px-wide landscape panel, and at 600px portrait it still leaves
     * ~180px per tile, which is wide enough for "Cappuccino" on one line.
     */
    private static final int GRID_COLUMNS = 3;

    // Grid metrics. Tiles are sized at runtime from the actual window, not
    // hardcoded, so the same build serves the 600x1024 portrait kiosk and a
    // 1024x600 landscape desktop window without a second layout.
    private static final double GRID_PAD_H = 22;
    private static final double GRID_PAD_V = 14;
    private static final double GRID_HGAP = 14;
    private static final double GRID_VGAP = 12;

    /** Vertical space consumed by the header bar and the "CHOOSE YOUR FUEL" line. */
    private static final double CHROME_HEIGHT = 118;

    /** Tiles taller than this multiple of their width start to look like columns. */
    private static final double MAX_TILE_ASPECT = 1.45;
    private static final double MIN_TILE_HEIGHT = 110;

    /** How long the fake brewing cycle runs. */
    private static final Duration BREW_TIME = Duration.seconds(4.5);

    /** The kiosk is a state machine; making that explicit prevents illegal transitions. */
    private enum KioskState {
        SELECTING, AWAITING_PAYMENT, DISPENSING, SHOWING_RESULT
    }

    // ── FXML-injected nodes ──────────────────────────────────────────
    @FXML private BorderPane rootPane;
    @FXML private VBox selectionScreen;
    @FXML private VBox paymentScreen;
    @FXML private VBox preparationScreen;
    @FXML private VBox resultScreen;

    @FXML private GridPane drinkGrid;

    @FXML private Circle statusDot;
    @FXML private Label statusLabel;

    @FXML private StackPane nfcPulse;
    @FXML private Circle ring1;
    @FXML private Circle ring2;
    @FXML private Circle ring3;
    @FXML private Label paymentItemLabel;
    @FXML private Label paymentAmountLabel;
    @FXML private Label paymentHintLabel;
    @FXML private ImageView paymentDrinkImage;
    @FXML private Button devTapButton;

    @FXML private ImageView prepDrinkImage;
    @FXML private Label prepHeadlineLabel;
    @FXML private Label prepStepLabel;
    @FXML private ProgressBar progressBar;

    @FXML private ImageView resultDrinkImage;
    @FXML private Label resultHeadlineLabel;
    @FXML private Label resultDetailLabel;
    @FXML private HBox balancePanel;
    @FXML private Label balanceBeforeLabel;
    @FXML private Label balanceAfterLabel;

    // ── state ────────────────────────────────────────────────────────
    private KioskState state = KioskState.SELECTING;
    private Drink selectedDrink;
    private PaymentTerminal terminal;
    private List<Animation> pulseParts = new ArrayList<>();
    private Timeline brewAnimation;

    /** Tiles and their artwork, kept so a resize can re-measure them in place. */
    private final List<Button> drinkTiles = new ArrayList<>();
    private final List<ImageView> drinkTileImages = new ArrayList<>();

    @FXML
    public void initialize() {
        terminal = createTerminal();
        buildDrinkGrid();
        buildPulseAnimation();
        updateStatusPill();

        // Tiles cannot be measured yet -- the root has no size until it is in
        // a Scene. Re-measure on every resize instead, which also covers the
        // window being dragged around during desktop development.
        rootPane.widthProperty().addListener((o, was, now) -> applyTileMetrics());
        rootPane.heightProperty().addListener((o, was, now) -> applyTileMetrics());
        // The simulate button is bench scaffolding; it hides itself once a
        // real reader is plugged in.
        devTapButton.setVisible(terminal instanceof SimulatedPaymentTerminal);
        System.out.println("[UI] ready — " + DrinkCatalog.size() + " drinks on the menu");
    }

    /**
     * Picks a payment backend: the real RC522 reader if one is attached,
     * otherwise the in-memory simulator.
     *
     * <p>Detection is by attempting to open the reader. On a laptop, or a Pi
     * with nothing wired, opening SPI throws and we fall back to the simulator,
     * so the same build runs everywhere with no flag. Force the simulator on
     * the Pi (for UI work) with {@code -Dbrewer.reader=simulated}.
     */
    private PaymentTerminal createTerminal() {
        if ("simulated".equalsIgnoreCase(System.getProperty("brewer.reader", ""))) {
            System.out.println("[UI] reader forced to simulator by -Dbrewer.reader");
            return new SimulatedPaymentTerminal();
        }
        try {
            PaymentTerminal real = new DesfirePaymentTerminal();
            System.out.println("[UI] RC522 reader ready -- real card payment enabled");
            return real;
        } catch (Exception e) {
            System.out.println("[UI] no RC522 reader (" + e.getMessage()
                    + ") -- using the simulator");
            return new SimulatedPaymentTerminal();
        }
    }

    // ── selection screen ─────────────────────────────────────────────

    /**
     * Generates one tile per catalog entry.
     *
     * <p>Each tile is a {@link Button} rather than a clickable {@code VBox}:
     * buttons already handle press states, focus traversal and accessibility,
     * so there is no reason to reimplement any of that by hand.
     */
    private void buildDrinkGrid() {
        drinkGrid.getChildren().clear();
        drinkTiles.clear();
        drinkTileImages.clear();
        drinkGrid.setHgap(GRID_HGAP);
        drinkGrid.setVgap(GRID_VGAP);
        drinkGrid.setPadding(new Insets(GRID_PAD_V, GRID_PAD_H, GRID_PAD_V, GRID_PAD_H));
        List<Drink> drinks = DrinkCatalog.all();

        for (int i = 0; i < drinks.size(); i++) {
            Drink drink = drinks.get(i);
            drinkGrid.add(createDrinkTile(drink), i % GRID_COLUMNS, i / GRID_COLUMNS);
        }
    }

    /**
     * Measures the tiles against the current window size.
     *
     * <p>Called on every resize, which is what lets one build serve both the
     * 600x1024 portrait kiosk and a 1024x600 landscape desktop window. The
     * height cap matters in portrait: with 1024px of vertical room, three
     * evenly-divided rows would otherwise produce tall thin columns rather
     * than tiles.
     */
    private void applyTileMetrics() {
        double w = rootPane.getWidth();
        double h = rootPane.getHeight();
        if (w <= 0 || h <= 0 || drinkTiles.isEmpty()) {
            return;
        }
        int rows = (int) Math.ceil(DrinkCatalog.size() / (double) GRID_COLUMNS);

        double tileW = (w - GRID_PAD_H * 2 - GRID_HGAP * (GRID_COLUMNS - 1)) / GRID_COLUMNS;
        double availH = h - CHROME_HEIGHT - GRID_PAD_V * 2;
        double tileH = (availH - GRID_VGAP * (rows - 1)) / rows;
        tileH = Math.max(MIN_TILE_HEIGHT, Math.min(tileH, tileW * MAX_TILE_ASPECT));

        // Leave room under the art for the name and price lines.
        double img = Math.min(tileH - 52, tileW * 0.62);

        for (int i = 0; i < drinkTiles.size(); i++) {
            Button tile = drinkTiles.get(i);
            tile.setPrefSize(tileW, tileH);
            tile.setMinSize(tileW, tileH);
            tile.setMaxSize(tileW, tileH);
            ImageView art = drinkTileImages.get(i);
            art.setFitWidth(img);
            art.setFitHeight(img);
        }
    }

    private Button createDrinkTile(Drink drink) {
        ImageView art = new ImageView(loadImage(drink.getImagePath()));
        art.setPreserveRatio(true);

        Label name = new Label(drink.getDisplayName());
        name.getStyleClass().add("drink-name");

        Label price = new Label(drink.getFormattedPrice());
        price.getStyleClass().add("drink-price");

        VBox content = new VBox(2, art, name, price);
        content.setAlignment(Pos.CENTER);

        Button tile = new Button();
        tile.setGraphic(content);
        tile.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        tile.getStyleClass().add("drink-tile");
        tile.setOnAction(e -> handleDrinkSelected(drink));

        drinkTiles.add(tile);
        drinkTileImages.add(art);
        return tile;
    }

    private Image loadImage(String path) {
        java.io.InputStream in = getClass().getResourceAsStream(path);
        if (in == null) {
            // Missing art should not take the kiosk down — log it and carry on.
            System.err.println("[UI] missing artwork: " + path
                    + "  (run: python3 tools/generate_coffee_art.py)");
            return null;
        }
        return new Image(in);
    }

    private void handleDrinkSelected(Drink drink) {
        if (state != KioskState.SELECTING) {
            return; // ignore stray taps while a transaction is running
        }
        selectedDrink = drink;
        System.out.println("[UI] selected: " + drink);

        paymentItemLabel.setText(drink.getDisplayName());
        paymentAmountLabel.setText(drink.getFormattedPrice());
        paymentHintLabel.setText("Hold your card flat against the reader");
        paymentDrinkImage.setImage(loadImage(drink.getImagePath()));

        showScreen(KioskState.AWAITING_PAYMENT);
        startPulse();

        terminal.startTransaction(drink, new UiPaymentListener());
        System.out.println("[UI] polling for card…");
    }

    // ── payment callbacks ────────────────────────────────────────────

    /**
     * Bridges terminal callbacks onto the JavaFX application thread.
     *
     * <p>Every method hops through {@code Platform.runLater} because the
     * terminal fires these from its own polling thread, and JavaFX nodes may
     * only be touched from the FX thread.
     */
    private final class UiPaymentListener implements PaymentListener {

        @Override
        public void onCardDetected(String uid) {
            Platform.runLater(() -> {
                System.out.println("[UI] card detected: " + uid);
                paymentHintLabel.setText("Card detected — authenticating…");
            });
        }

        @Override
        public void onBalanceRead(int balanceCents) {
            Platform.runLater(() -> {
                System.out.println("[UI] balance on card: " + Drink.formatCents(balanceCents));
                paymentHintLabel.setText("Balance " + Drink.formatCents(balanceCents)
                        + " — charging…");
            });
        }

        @Override
        public void onApproved(PaymentReceipt receipt) {
            Platform.runLater(() -> {
                System.out.println("[UI] approved: " + receipt);
                stopPulse();
                startBrewing(receipt);
            });
        }

        @Override
        public void onDeclined(CardException cause) {
            Platform.runLater(() -> {
                System.out.println("[UI] declined: " + cause.getMessage());
                stopPulse();
                showFailure(cause);
            });
        }
    }

    @FXML
    private void handleCancel() {
        System.out.println("[UI] cancelled by user");
        terminal.cancel();
        stopPulse();
        selectedDrink = null;
        showScreen(KioskState.SELECTING);
    }

    /** Bench affordance: pretend a card was presented. */
    @FXML
    private void handleSimulatedTap() {
        if (terminal instanceof SimulatedPaymentTerminal) {
            ((SimulatedPaymentTerminal) terminal).simulateTap();
        }
    }

    @FXML
    private void handleReturnToMenu() {
        showScreen(KioskState.SELECTING);
    }

    // ── dispensing ───────────────────────────────────────────────────

    private void startBrewing(PaymentReceipt receipt) {
        Drink drink = receipt.getDrink();
        prepDrinkImage.setImage(loadImage(drink.getImagePath()));
        prepHeadlineLabel.setText("Preparing your " + drink.getDisplayName());
        prepStepLabel.setText("Grinding beans");
        progressBar.setProgress(0);
        showScreen(KioskState.DISPENSING);

        // Step captions advance with the bar so the wait feels accounted for
        // rather than like a frozen screen.
        brewAnimation = new Timeline(
                new KeyFrame(Duration.ZERO,
                        new KeyValue(progressBar.progressProperty(), 0)),
                new KeyFrame(BREW_TIME.multiply(0.30),
                        e -> prepStepLabel.setText("Heating water to 93°C")),
                new KeyFrame(BREW_TIME.multiply(0.60),
                        e -> prepStepLabel.setText("Extracting espresso")),
                new KeyFrame(BREW_TIME.multiply(0.85),
                        e -> prepStepLabel.setText("Pouring")),
                new KeyFrame(BREW_TIME,
                        new KeyValue(progressBar.progressProperty(), 1,
                                Interpolator.EASE_BOTH)));

        brewAnimation.setOnFinished(e -> showSuccess(receipt));
        brewAnimation.play();
    }

    private void showSuccess(PaymentReceipt receipt) {
        System.out.println("[UI] dispensed " + receipt.getDrink().getDisplayName());
        resultHeadlineLabel.setText("Enjoy your " + receipt.getDrink().getDisplayName());
        resultHeadlineLabel.getStyleClass().setAll("result-headline");
        setImageVisible(resultDrinkImage, loadImage(receipt.getDrink().getImagePath()));
        resultDetailLabel.setText("Charged "
                + Drink.formatCents(receipt.getAmountChargedCents())
                + " to card " + shortUid(receipt.getCardUid()));

        balancePanel.setVisible(true);
        balancePanel.setManaged(true);
        balanceBeforeLabel.setText(Drink.formatCents(receipt.getBalanceBeforeCents()));
        balanceAfterLabel.setText(Drink.formatCents(receipt.getBalanceAfterCents()));

        showScreen(KioskState.SHOWING_RESULT);
        updateStatusPill();
        autoReturnToMenu(Duration.seconds(6));
    }

    private void showFailure(CardException cause) {
        resultHeadlineLabel.setText("Payment declined");
        resultHeadlineLabel.getStyleClass().setAll("result-headline-error");
        resultDetailLabel.setText(cause.getUserMessage());
        // No drink art on a decline — nothing was dispensed.
        setImageVisible(resultDrinkImage, null);

        // No receipt to show when nothing was charged.
        balancePanel.setVisible(false);
        balancePanel.setManaged(false);

        showScreen(KioskState.SHOWING_RESULT);
        autoReturnToMenu(Duration.seconds(5));
    }

    private void autoReturnToMenu(Duration after) {
        PauseTransition idle = new PauseTransition(after);
        idle.setOnFinished(e -> {
            if (state == KioskState.SHOWING_RESULT) {
                showScreen(KioskState.SELECTING);
            }
        });
        idle.play();
    }

    private static String shortUid(String uid) {
        return uid.length() <= 8 ? uid : "…" + uid.substring(uid.length() - 6);
    }

    // ── screen switching ─────────────────────────────────────────────

    private void showScreen(KioskState next) {
        state = next;
        setVisible(selectionScreen, next == KioskState.SELECTING);
        setVisible(paymentScreen, next == KioskState.AWAITING_PAYMENT);
        setVisible(preparationScreen, next == KioskState.DISPENSING);
        setVisible(resultScreen, next == KioskState.SHOWING_RESULT);

        if (next == KioskState.SELECTING) {
            selectedDrink = null;
        }
        fadeIn(activeScreen(next));
        updateStatusPill();
    }

    private Node activeScreen(KioskState s) {
        switch (s) {
            case AWAITING_PAYMENT: return paymentScreen;
            case DISPENSING:       return preparationScreen;
            case SHOWING_RESULT:   return resultScreen;
            case SELECTING:
            default:               return selectionScreen;
        }
    }

    /** Shows an image, or collapses the view entirely so it takes no layout space. */
    private static void setImageVisible(ImageView view, Image image) {
        view.setImage(image);
        view.setVisible(image != null);
        view.setManaged(image != null);
    }

    private static void setVisible(Node node, boolean visible) {
        node.setVisible(visible);
        // Keep invisible screens out of hit-testing entirely.
        node.setMouseTransparent(!visible);
    }

    private static void fadeIn(Node node) {
        FadeTransition fade = new FadeTransition(Duration.millis(180), node);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.play();
    }

    private void updateStatusPill() {
        // Locale.ROOT, not the default locale: in a Turkish locale the default
        // toUpperCase turns "i" into "İ" and the pill renders wrong.
        statusLabel.setText(terminal.getStatusText().toUpperCase(java.util.Locale.ROOT));
        boolean live = state == KioskState.AWAITING_PAYMENT;
        statusDot.getStyleClass().setAll(live ? "status-dot" : "status-dot-idle");
    }

    // ── animation ────────────────────────────────────────────────────

    /**
     * Three rings expanding and fading outward, staggered, on a loop — the
     * visual vocabulary people already associate with "tap here".
     */
    private void buildPulseAnimation() {
        List<Animation> parts = new ArrayList<>();
        Circle[] rings = {ring1, ring2, ring3};

        for (int i = 0; i < rings.length; i++) {
            Circle ring = rings[i];
            Duration offset = Duration.millis(i * 400L);

            ScaleTransition grow = new ScaleTransition(Duration.millis(1800), ring);
            grow.setFromX(0.65);
            grow.setFromY(0.65);
            grow.setToX(1.25);
            grow.setToY(1.25);
            grow.setDelay(offset);
            grow.setCycleCount(Animation.INDEFINITE);
            grow.setInterpolator(Interpolator.EASE_OUT);

            FadeTransition fade = new FadeTransition(Duration.millis(1800), ring);
            fade.setFromValue(0.55);
            fade.setToValue(0.0);
            fade.setDelay(offset);
            fade.setCycleCount(Animation.INDEFINITE);

            parts.add(grow);
            parts.add(fade);
        }

        pulseParts = parts;
    }

    private void startPulse() {
        pulseParts.forEach(Animation::play);
    }

    private void stopPulse() {
        pulseParts.forEach(Animation::stop);
        // Leave the rings in a tidy resting state.
        for (Circle ring : new Circle[]{ring1, ring2, ring3}) {
            ring.setOpacity(0.0);
            ring.setScaleX(1.0);
            ring.setScaleY(1.0);
        }
    }

    // ── lifecycle ────────────────────────────────────────────────────

    /** Called from {@code MainApp.stop()} so the RF field is never left energised. */
    public void shutdown() {
        if (brewAnimation != null) {
            brewAnimation.stop();
        }
        stopPulse();
        if (terminal != null) {
            terminal.close();
        }
        System.out.println("[UI] shut down cleanly");
    }
}
