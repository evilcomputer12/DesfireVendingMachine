package com.midnightbrewer.ui;

import com.midnightbrewer.model.Drink;
import com.midnightbrewer.model.DrinkCatalog;
import com.midnightbrewer.payment.PaymentListener;
import com.midnightbrewer.payment.PaymentReceipt;
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
import javafx.scene.CacheHint;
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
import java.util.Locale;

/**
 * Drives the kiosk's screen flow and mediates between the UI and the
 * {@link PaymentTerminal}.
 *
 * <p>Note what this class does NOT contain: no cryptography, no SPI, no APDUs.
 * It depends only on the {@code PaymentTerminal} interface --- which is why
 * swapping the simulator for a real DESFire reader (Volume One, Chapter 12)
 * changes exactly one method here and nothing else.
 *
 * <p><b>THIS IS THE FILE YOU WRITE.</b> The {@code @FXML} fields and the small
 * helpers are given, because they are lookup rather than reasoning. Every
 * {@code TODO} is a behaviour to implement, and they are ordered so that each
 * one you finish is visibly working before you start the next.
 */
public class UIController {

    /** Columns in the drink grid. */
    private static final int GRID_COLUMNS = 3;

    private static final double GRID_HGAP = 14;
    private static final double GRID_VGAP = 12;
    private static final double GRID_PAD_H = 22;
    private static final double GRID_PAD_V = 14;

    /** How long the fake brewing cycle runs. */
    private static final Duration BREW_TIME = Duration.seconds(4.5);

    /**
     * The kiosk is a state machine. Making that explicit -- rather than juggling
     * booleans -- is what stops illegal transitions like "pay twice".
     */
    private enum KioskState {
        SELECTING, AWAITING_PAYMENT, DISPENSING, SHOWING_RESULT
    }

    // ── FXML-injected nodes ──────────────────────────────────────────
    // These names must match fx:id in MidnightBrewer.fxml exactly. Given to
    // you: it is transcription, not design.
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

    /**
     * Called by the FXML loader once every @FXML field above is populated.
     *
     * Given complete, so the app launches from the very first run. As you fill
     * in the methods it calls, more of the screen comes alive.
     */
    @FXML
    public void initialize() {
        terminal = createTerminal();
        buildDrinkGrid();
        buildPulseAnimation();
        updateStatusPill();
        showScreen(KioskState.SELECTING);

        // The simulate button is bench scaffolding; it hides itself once a
        // real reader is plugged in.
        devTapButton.setVisible(terminal instanceof SimulatedPaymentTerminal);
        System.out.println("[UI] ready -- " + DrinkCatalog.size() + " drinks on the menu");
    }

    /**
     * Picks the payment backend.
     *
     * In Volume One, Chapter 12 this grows a try/catch that attempts to open a
     * real RC522 and falls back to the simulator when there is none --- so the
     * same build runs on a laptop and on the kiosk with no flags.
     */
    private PaymentTerminal createTerminal() {
        return new SimulatedPaymentTerminal();
    }

    // ═════════════════════════════════════════════════════════════════
    // STEP 1 -- the drink grid.
    //
    // Fill this in first: it is the one that turns an empty screen into a
    // menu, so you see the result immediately.
    // ═════════════════════════════════════════════════════════════════

    private final List<Button> drinkTiles = new ArrayList<>();

    private void buildDrinkGrid() {
        drinkGrid.getChildren().clear();
        drinkTiles.clear();
        drinkGrid.setHgap(GRID_HGAP);
        drinkGrid.setVgap(GRID_VGAP);
        drinkGrid.setPadding(new Insets(GRID_PAD_V, GRID_PAD_H, GRID_PAD_V, GRID_PAD_H));

        // TODO 1: loop over DrinkCatalog.all() and add a tile per drink.
        //
        //   List<Drink> drinks = DrinkCatalog.all();
        //   for (int i = 0; i < drinks.size(); i++) {
        //       drinkGrid.add(createDrinkTile(drinks.get(i)),
        //                     i % GRID_COLUMNS,        // column
        //                     i / GRID_COLUMNS);       // row
        //   }
        //
        // Integer division for the row, modulo for the column, is the standard
        // way to lay a flat list into a grid.
    }

    /**
     * One tile: artwork, name, price, and a click handler.
     *
     * A Button rather than a clickable VBox, because buttons already handle
     * press states, focus traversal and accessibility. There is no reason to
     * reimplement any of that by hand.
     */
    private Button createDrinkTile(Drink drink) {
        // TODO 2: build the tile.
        //
        //   ImageView art = new ImageView(loadImage(drink.getImagePath()));
        //   art.setPreserveRatio(true);
        //   art.setFitWidth(120);
        //   art.setFitHeight(120);
        //
        //   Label name  = new Label(drink.getDisplayName());
        //   name.getStyleClass().add("drink-name");
        //   Label price = new Label(drink.getFormattedPrice());
        //   price.getStyleClass().add("drink-price");
        //
        //   VBox content = new VBox(2, art, name, price);
        //   content.setAlignment(Pos.CENTER);
        //
        //   Button tile = new Button();
        //   tile.setGraphic(content);
        //   tile.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        //   tile.getStyleClass().add("drink-tile");
        //   tile.setOnAction(e -> handleDrinkSelected(drink));
        //   tile.setPrefSize(170, 200);
        //
        //   drinkTiles.add(tile);
        //   return tile;
        //
        // The style class names ("drink-tile", "drink-name", "drink-price")
        // are already defined in styles.css -- that is the design half you
        // were given.
        return new Button();
    }

    // ═════════════════════════════════════════════════════════════════
    // STEP 2 -- screen switching.
    //
    // Four screens live stacked on top of each other in the FXML; exactly one
    // is visible at a time.
    // ═════════════════════════════════════════════════════════════════

    private void showScreen(KioskState next) {
        state = next;

        // TODO 3: show exactly one screen.
        //
        //   setVisible(selectionScreen,   next == KioskState.SELECTING);
        //   setVisible(paymentScreen,     next == KioskState.AWAITING_PAYMENT);
        //   setVisible(preparationScreen, next == KioskState.DISPENSING);
        //   setVisible(resultScreen,      next == KioskState.SHOWING_RESULT);
        //
        //   if (next == KioskState.SELECTING) selectedDrink = null;
        //   fadeIn(activeScreen(next));
        //   updateStatusPill();
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

    // ═════════════════════════════════════════════════════════════════
    // STEP 3 -- selecting a drink starts a transaction.
    // ═════════════════════════════════════════════════════════════════

    private void handleDrinkSelected(Drink drink) {
        // TODO 4: guard, then start.
        //
        //   if (state != KioskState.SELECTING) return;   // ignore stray taps
        //   selectedDrink = drink;
        //
        //   paymentItemLabel.setText(drink.getDisplayName());
        //   paymentAmountLabel.setText(drink.getFormattedPrice());
        //   paymentHintLabel.setText("Hold your card flat against the reader");
        //   paymentDrinkImage.setImage(loadImage(drink.getImagePath()));
        //
        //   showScreen(KioskState.AWAITING_PAYMENT);
        //   startPulse();
        //   terminal.startTransaction(drink, new UiPaymentListener());
        //
        // That guard matters: without it, a double-tap starts two transactions
        // and the second one's callbacks arrive into a UI that has moved on.
    }

    // ═════════════════════════════════════════════════════════════════
    // STEP 4 -- the payment callbacks. THE THREADING RULE LIVES HERE.
    //
    // The terminal calls these from ITS OWN thread. JavaFX has exactly one
    // thread allowed to touch the UI, so every single one of these must hop
    // via Platform.runLater.
    //
    // Getting this wrong does not reliably throw. It corrupts rendering in
    // ways that appear once an hour and never under a debugger.
    // ═════════════════════════════════════════════════════════════════

    private final class UiPaymentListener implements PaymentListener {

        @Override
        public void onCardDetected(String uid) {
            // TODO 5: Platform.runLater(() ->
            //             paymentHintLabel.setText("Card detected -- authenticating..."));
        }

        @Override
        public void onBalanceRead(int balanceCents) {
            // TODO 6: Platform.runLater(() -> paymentHintLabel.setText(
            //             "Balance " + Drink.formatCents(balanceCents) + " -- charging..."));
        }

        @Override
        public void onApproved(PaymentReceipt receipt) {
            // TODO 7: Platform.runLater(() -> { stopPulse(); startBrewing(receipt); });
        }

        @Override
        public void onDeclined(String reason) {
            // TODO 8: Platform.runLater(() -> { stopPulse(); showFailure(reason); });
        }
    }

    // ── FXML button handlers (wired by onAction= in the FXML) ─────────

    @FXML
    private void handleCancel() {
        // TODO 9: terminal.cancel(); stopPulse(); showScreen(KioskState.SELECTING);
    }

    /** Bench affordance: pretend a card was presented. */
    @FXML
    private void handleSimulatedTap() {
        if (terminal instanceof SimulatedPaymentTerminal sim) {
            sim.simulateTap();
        }
    }

    @FXML
    private void handleReturnToMenu() {
        showScreen(KioskState.SELECTING);
    }

    // ═════════════════════════════════════════════════════════════════
    // STEP 5 -- dispensing and the result screen.
    // ═════════════════════════════════════════════════════════════════

    private void startBrewing(PaymentReceipt receipt) {
        Drink drink = receipt.getDrink();
        prepDrinkImage.setImage(loadImage(drink.getImagePath()));
        prepHeadlineLabel.setText("Preparing your " + drink.getDisplayName());
        prepStepLabel.setText("Grinding beans");
        progressBar.setProgress(0);
        showScreen(KioskState.DISPENSING);

        // TODO 10: animate the progress bar, with captions that advance.
        //
        //   brewAnimation = new Timeline(
        //       new KeyFrame(Duration.ZERO, new KeyValue(progressBar.progressProperty(), 0)),
        //       new KeyFrame(BREW_TIME.multiply(0.30),
        //                    e -> prepStepLabel.setText("Heating water to 93C")),
        //       new KeyFrame(BREW_TIME.multiply(0.60),
        //                    e -> prepStepLabel.setText("Extracting espresso")),
        //       new KeyFrame(BREW_TIME.multiply(0.85),
        //                    e -> prepStepLabel.setText("Pouring")),
        //       new KeyFrame(BREW_TIME,
        //                    new KeyValue(progressBar.progressProperty(), 1,
        //                                 Interpolator.EASE_BOTH)));
        //   brewAnimation.setOnFinished(e -> showSuccess(receipt));
        //   brewAnimation.play();
        //
        // The captions are not decoration: a progress bar with no narration
        // reads as "frozen" to a waiting customer.
    }

    private void showSuccess(PaymentReceipt receipt) {
        // TODO 11:
        //   resultHeadlineLabel.setText("Enjoy your " + receipt.getDrink().getDisplayName());
        //   resultHeadlineLabel.getStyleClass().setAll("result-headline");
        //   setImageVisible(resultDrinkImage, loadImage(receipt.getDrink().getImagePath()));
        //   resultDetailLabel.setText("Charged "
        //       + Drink.formatCents(receipt.getAmountChargedCents())
        //       + " to card " + shortUid(receipt.getCardUid()));
        //
        //   balancePanel.setVisible(true);
        //   balancePanel.setManaged(true);
        //   balanceBeforeLabel.setText(Drink.formatCents(receipt.getBalanceBeforeCents()));
        //   balanceAfterLabel.setText(Drink.formatCents(receipt.getBalanceAfterCents()));
        //
        //   showScreen(KioskState.SHOWING_RESULT);
        //   autoReturnToMenu(Duration.seconds(6));
    }

    private void showFailure(String reason) {
        // TODO 12:
        //   resultHeadlineLabel.setText("Payment declined");
        //   resultHeadlineLabel.getStyleClass().setAll("result-headline-error");
        //   resultDetailLabel.setText(reason);
        //   setImageVisible(resultDrinkImage, null);   // nothing was dispensed
        //
        //   balancePanel.setVisible(false);
        //   balancePanel.setManaged(false);            // no receipt to show
        //
        //   showScreen(KioskState.SHOWING_RESULT);
        //   autoReturnToMenu(Duration.seconds(5));
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

    // ═════════════════════════════════════════════════════════════════
    // STEP 6 -- the tap animation. Pure polish, and worth it.
    // ═════════════════════════════════════════════════════════════════

    private void buildPulseAnimation() {
        List<Animation> parts = new ArrayList<>();
        Circle[] rings = { ring1, ring2, ring3 };

        for (int i = 0; i < rings.length; i++) {
            Circle ring = rings[i];
            Duration offset = Duration.millis(i * 400L);

            // Render each ring to a bitmap once and animate the bitmap. Without
            // this the Pi re-rasterises the vector circle every frame while it
            // scales and fades; with it, scaling a cached image is nearly free.
            ring.setCache(true);
            ring.setCacheHint(CacheHint.SPEED);

            // TODO 13: three rings expanding and fading outward, staggered, on
            // a loop -- the visual vocabulary people already read as "tap here".
            //
            //   ScaleTransition grow = new ScaleTransition(Duration.millis(1800), ring);
            //   grow.setFromX(0.65); grow.setFromY(0.65);
            //   grow.setToX(1.25);   grow.setToY(1.25);
            //   grow.setDelay(offset);
            //   grow.setCycleCount(Animation.INDEFINITE);
            //   grow.setInterpolator(Interpolator.EASE_OUT);
            //
            //   FadeTransition fade = new FadeTransition(Duration.millis(1800), ring);
            //   fade.setFromValue(0.55); fade.setToValue(0.0);
            //   fade.setDelay(offset);
            //   fade.setCycleCount(Animation.INDEFINITE);
            //
            //   parts.add(grow); parts.add(fade);
        }
        pulseParts = parts;
    }

    private void startPulse() {
        pulseParts.forEach(Animation::play);
    }

    private void stopPulse() {
        pulseParts.forEach(Animation::stop);
        for (Circle ring : new Circle[]{ ring1, ring2, ring3 }) {
            ring.setOpacity(0.0);
            ring.setScaleX(1.0);
            ring.setScaleY(1.0);
        }
    }

    // ── helpers, given complete (boilerplate) ────────────────────────

    private void updateStatusPill() {
        // Locale.ROOT, not the default: in a Turkish locale the default
        // toUpperCase turns "i" into a dotted capital and the pill renders wrong.
        statusLabel.setText(terminal.getStatusText().toUpperCase(Locale.ROOT));
        boolean live = state == KioskState.AWAITING_PAYMENT;
        statusDot.getStyleClass().setAll(live ? "status-dot" : "status-dot-idle");
    }

    private Image loadImage(String path) {
        java.io.InputStream in = getClass().getResourceAsStream(path);
        if (in == null) {
            // Missing art should not take the kiosk down.
            System.err.println("[UI] missing artwork: " + path);
            return null;
        }
        return new Image(in);
    }

    private static String shortUid(String uid) {
        return uid.length() <= 8 ? uid : "..." + uid.substring(uid.length() - 6);
    }

    /** Shows an image, or collapses the view so it takes no layout space. */
    private static void setImageVisible(ImageView view, Image image) {
        view.setImage(image);
        view.setVisible(image != null);
        view.setManaged(image != null);
    }

    private static void setVisible(Node node, boolean visible) {
        node.setVisible(visible);
        node.setMouseTransparent(!visible);   // keep hidden screens out of hit-testing
    }

    private static void fadeIn(Node node) {
        FadeTransition fade = new FadeTransition(Duration.millis(180), node);
        fade.setFromValue(0.0);
        fade.setToValue(1.0);
        fade.play();
    }

    /** Called from MainApp.stop() so the RF field is never left energised. */
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
