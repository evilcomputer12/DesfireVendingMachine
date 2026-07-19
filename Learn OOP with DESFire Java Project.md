# **Learn OOP with DESFire Java Project**

[https://gemini.google.com/app/782f46f35e3ad0cb](https://gemini.google.com/app/782f46f35e3ad0cb)

*User prompt: how to learn oop all of the concepts covered in this book lik abstract classes, interfaces all the complex oop mambo jumbo by pratice by making desfire java project with gui maybe think of a fun project that would have a great use,*

Response: As you dive into your all-night study session to master the syllabus from your books, tackling a hands-on project is the perfect way to make all that complex OOP "mumbo jumbo" finally click. Reading about abstract classes and interfaces is one thing, but forcing yourself to use them to manage real cryptography and hardware communication will cement the knowledge permanently.

Here is a practical, fun, and highly useful Java GUI project idea that perfectly bridges the object-oriented programming concepts from your textbook with the DESFire EV1 communication examples.

### **Project Idea: The "Secure Campus Hub" (Access & E-Wallet System)**

**The Concept:** Build a Java desktop application with a GUI (using JavaFX or Swing) that simulates a secure campus terminal. It will interact with DESFire EV1 cards to perform two distinct functions: unlocking a specific door (Physical Access) and buying a coffee from a virtual vending machine (E-Wallet).

This project is fantastic because smart card systems natively beg for an object-oriented design. You have different types of cards, different types of readers, and complex security states that need to be encapsulated.

### **How to Map OOP Concepts to the Project**

Use the concepts covered in your OOP textbook to architect the system. Here is how you can directly apply them to your DESFire project:

| OOP Concept | Application in DESFire Project |
| :---- | :---- |
| **Encapsulation** | Hide the complex AES/3DES cryptography, IV resets, and CBC logic inside a CryptoEngine class. The rest of your app only needs to call engine.encrypt(data). |
| **Interfaces** | Create a SecureCommunicator interface with methods like authenticate(), calculateCMAC(), and validateCRC(). Any card type you add later must implement this contract. |
| **Abstract Classes** | Build an AbstractCardReader class that handles basic hardware connections and byte transmissions. Leave the specific protocol implementations (like EV1 vs. EV2) to child classes. |
| **Inheritance** | Create a base SmartCard class that holds the UID and connection status. Have a DESFireEV1Card class extend it, inheriting the basics but adding advanced application directory management. |
| **Polymorphism** | Use a processTransaction(SmartCard card) method in your GUI. Whether the user taps a StudentCard or a FacultyCard, the system dynamically calls the correct overridden methods. |
| **Containers (Maps)** | Use a HashMap (as seen in the OOP book examples) to store known Card UIDs as keys and UserAccount objects as values for quick GUI lookups. |
| **Exceptions** | Create custom exceptions like AuthenticationFailedException or CMACMismatchException to handle the specific DESFire integrity errors mentioned in the documentation. |

### **Step-by-Step Execution Plan**

**1\. Build the Foundation (Classes & Interfaces)** Start by defining your abstract classes and interfaces. Do not worry about the actual byte manipulation yet. Set up your Student, Faculty, and SmartCard hierarchies.

**2\. Implement the Cryptography (Encapsulation)** This is where the DESFire documentation becomes critical. Create utility classes to handle the AES or 3DES authentication. You will need to carefully implement Cipher Block Chaining (CBC) and ensure your initialization vectors (IVs) reset exactly as described in the documentation.

**3\. Command Handling & CMAC** Write the methods to send commands to the card (like SelectApplication or GetValue). Remember that for commands sending or receiving multiple frames, your CMAC must be calculated over the data of all frames, excluding the 0xAF status byte.

**4\. Build the GUI** Create a simple interface with two main tabs: "Access Control" and "Cafeteria".

* **Access Control:** A button that simulates a card tap. If the AES authentication succeeds and the UID is in your system, turn a label green ("Door Unlocked").  
* **Cafeteria:** Display the card's current balance, allow the user to input a coffee purchase, deduct the value securely using DESFire value files, and update the GUI.

What specific feature of the DESFire EV1 card—like setting up the AES authentication or managing the file directory—do you want to tackle first for your GUI?

---

*User prompt: Hmm how about we make a virtual vending machine system hmi android app that waits for another app hce with df protocol with credits debits or a real desfire card*

Response: That is a phenomenal evolution of the project idea\! Simulating a vending machine HMI that interacts with either a real card or an HCE (Host Card Emulation) app is a masterclass in full-stack architecture. It splits your project into two distinct Android roles: the **PCD (Proximity Coupling Device / Reader)** and the **PICC (Proximity Integrated Circuit Card / Emulator)**.

To pull this off seamlessly, your HMI app will act as the master reader using Android’s native IsoDep class, while the second app leverages HostApduService to emulate the DESFire card protocol.

Here is how you can architect this dual-app ecosystem while cleanly applying those core OOP blocks and the native protocol requirements.

## **🏗️ The Dual-App Architecture**

\[ HCE Android App \] \<--- NFC (ISO 14443-4) \---\> \[ Vending Machine HMI App \]  
  (Card Emulator)                                     (NFC Reader/PCD)

### **1\. The Vending Machine HMI (Reader)**

This app displays the inventory (snacks, drinks) and waits for a secure tap.

* **OOP Pattern — Command Pattern & Polymorphism:** Define a generic NfcCard interface with methods like authenticate() and processTransaction(). Whether the app detects a real plastic card or your HCE app, the HMI interaction loop remains identical.  
* **State Management:** Implement a state machine using encapsulation to ensure that a debit cannot be requested until a product is selected and the card is fully authenticated.

### **2\. The HCE App (Wallet Emulator)**

This app runs in the background or foreground, registering a specific **AID (Application Identifier)** in its Android manifest to intercept the reader's commands.

* **OOP Pattern — Composite Architecture:** Mirror NXP's architecture by creating an Application class that holds a list of ValueFile objects. The ValueFile class handles credit, debit, and transaction limits, mimicking the exact rules of a standard BankAccount object.  
* **Robust Error Handling:** Write a user-defined InsufficientFundsException to gracefully abort the HCE APDU communication flow if a user attempts to buy a drink that costs more than their card's current balance.

## **🔒 Implementing the DESFire Protocol Flow**

To make the HCE app indistinguishable from a real card to your reader, your transceive loop needs to follow the strict protocol mechanics captured in the debug logs:

### **Step 1: Selection & Interception**

When the HCE app is tapped, the reader issues a SelectApplication command (0x5A). The HCE app must catch this command byte, parse the target 3-byte Application ID, and return a success status code (0x00).

### **Step 2: Secret Handshake (Authentication)**

The reader app executes an Authenticate request (0x1A for 3DES or 0xAA for AES).

* The HCE app generates a random block (*RndB*), enciphers it, and transmits it back alongside a pending status byte (0xAF).  
* Both apps exchange rotated keys (*RndA* and *RndB*), decrypting each other's tokens through Cipher Block Chaining (CBC) to establish a fresh Session Key.

### **Step 3: Transaction (Credit / Debit)**

Instead of standard file writes (0x3D) or file reads (0xBD), your vending machine will use native DESFire value operations:

* **Debit (Decrease):** The reader sends a command to subtract tokens. The HCE app validates the request, updates its internal file value, and returns a verified status.  
* **Integrity Check:** For security, every response must have an attached **CMAC (Cipher-based Message Authentication Code)** calculated over the response bytes plus the final status byte.

## **🛠️ Where to Start Building**

To make rapid progress without getting stuck in a massive debugging loop, decouple your components:

1. **Write the Core Logic in Plain Java first:** Implement the cryptography, APDU parser, and ValueFile logic as standard Java classes. You can even write short unit tests using standard array structures to simulate the byte arrays passing back and forth.  
2. **Build the HCE Service:** Get the HCE app to successfully respond to a basic, unencrypted text APDU from the reader app before introducing the complex DESFire cryptography.  
3. **Assemble the HMI:** Once the communication bridge is stable, wrap the reader logic into a clean Android UI layout with selection buttons and status indicators.

Would you prefer to start by setting up the Android HostApduService configuration for the HCE wallet app, or would you rather map out the cryptographic state machine for the HMI reader side first?

---

*User prompt: Hmm, i also have also a raspberry pi and 7" touchscreen display and rc522 module*

Response: Bringing a Raspberry Pi and a 7" touchscreen into the mix elevates this from a software simulation to a tangible, physical prototype\! It is the perfect platform to build a rich Java GUI for your virtual vending machine.

However, there is a significant hardware reality we need to address regarding the RC522 module. Powering through an all-night study session to conquer the syllabus from both of your books requires a setup that works with you, not against you. Wasting hours fighting hardware limitations will only cause frustration.

### **The RC522 Limitation**

The RC522 chip is fantastic for basic MIFARE Classic cards, but it lacks native support for the ISO/IEC 14443-4 protocol required by DESFire. Its small 64-byte hardware buffer makes handling the complex, multi-frame APDU (Application Protocol Data Unit) exchanges of DESFire EV1 incredibly unreliable, and in most cases, practically impossible without writing a massive, custom software stack.

To make this work smoothly with DESFire or your Android HCE app, you will want to swap the RC522 for a **PN532** module. The DESFire EV1 communication examples in your documentation were specifically generated using a PN532 board. The PN532 manages the lower-level framing for you and provides a dedicated INDATAEXCHANGE command (0x40) that handles sending and receiving APDUs perfectly.

### **The Updated Architecture**

With a Pi and a proper module, your architecture becomes incredibly professional:

* **Raspberry Pi (Vending Machine HMI):** Runs your Java OOP application natively. It displays the interactive vending interface on the 7" touchscreen and uses a library like Pi4J to talk to the PN532 module via SPI or I2C.  
* **Android Device (HCE Wallet):** Runs your Host Card Emulation app. It waits in your pocket until you tap it against the Pi's connected RFID module to buy a virtual coffee.  
* **The Bridge (OOP Design):** Your Java code on the Pi implements your SecureCommunicator interface, formatting the byte arrays, managing the AES/3DES session keys, and sending them through the serial port to the module.

Does swapping to a PN532 module sound like a feasible adjustment for your hardware setup, or would you prefer to pivot the cryptographic logic to use the standard MIFARE Classic mechanics that your current RC522 can natively handle?

---

*User prompt: Https://github.com/evilcomputer12/plainc-desfire-rc522/blob/main/nucleof411re/Core/Src/ i did something prrviously maybe i can use this as a basus rewritten in java*

Response: That is a seriously impressive achievement\! I stand completely corrected on the hardware front—writing a custom C stack to force the RC522 to handle ISO-14443-4 block framing in software is a hardcore engineering flex. Since you have already conquered the grueling timing and framing logic in C, translating your STM32 Nucleo project into Java for the Raspberry Pi is the ultimate exercise to master Object-Oriented Programming.

Taking procedural C code and refactoring it into a clean, modern Java architecture is exactly how you bridge the gap between low-level hardware hacking and high-level software design.

Here is how you can map your existing C repository into a beautiful OOP Java architecture on your Raspberry Pi:

### **1\. The Hardware Abstraction Layer (Encapsulation)**

In your C code, you likely have loose functions passing structs around to read and write SPI registers. In Java, you will use **Encapsulation** to lock this down.

* Create an RC522Driver class.  
* Make the SPI connection instance (using a library like **Pi4J** for the Raspberry Pi) a private field.  
* Expose only public methods like init(), writeRegister(), and readRegister(). This hides the messy byte-shifting logic from the rest of your application.

### **2\. The ISO-DEP Protocol Stack (Inheritance & Interfaces)**

Your C code had to manually handle I-blocks, R-blocks, and S-blocks to keep the DESFire card talking.

* Create a generic Iso14443Card interface that defines a transceive(byte\[\] apdu) method.  
* Build an RC522SoftwareIsoDep class that implements this interface. All of your complex C while-loops and block-chaining logic go here.  
* By programming to an interface, your vending machine GUI will just call card.transceive(), completely oblivious to the fact that the RC522 is sweating underneath to handle the framing.

### **3\. The DESFire EV1 Layer (Composition)**

Instead of giant switch-statements for commands, use **Composition** to build your DESFire logic.

* Create a DesfireApplication class. Inside it, maintain a reference (a "has-a" relationship) to your Iso14443Card object.  
* Translate your C cryptography functions into a Java CryptoEngine class. When your code needs to execute the Authenticate command (0x1A or 0xAA), the CryptoEngine will handle rotating *RndA* and *RndB*, and generating the session key.  
* When issuing commands like ChangeKeySettings or GetApplicationIDs, you will utilize this engine to calculate the TX and RX CMAC over the command and parameter bytes.

### **4\. Replacing Error Codes with Exceptions**

In C, you likely returned \-1 or 0 to indicate if a DESFire command failed or if the RC522 timed out. In Java, you will throw and catch **Exceptions**.

* Create custom exception classes like RC522TimeoutException, IntegrityErrorException, or AuthenticationFailedException.  
* If the card returns a status other than 0x00 (Success) or 0xAF (Additional Frame), your communication layer will throw the exception, and your Vending Machine GUI will catch it to display a friendly "Tap Card Again" message on the 7" touchscreen.

Since you already have the working C logic for the low-level RC522 manipulation, which piece of the repository do you want to tackle translating into a Java class first: the raw SPI register driver, or the software-based APDU block framing?

---

*User prompt: Can i transfer this convo to gemini cli*

Response: Yes, but it takes a quick manual step. The web interface and the local gemini-cli don't automatically sync active sessions, so you will need to export the context as a file and inject it into your terminal.

Here is how to port this setup over:

### **Step 1: Export the Conversation**

1. Click the **Share & export** icon (the three dots) at the bottom of this response.  
2. Select **Export to Docs** or use a browser extension to copy the conversation as Markdown.  
3. Save it locally in your Raspberry Pi or computer's project directory as a Markdown file (e.g., vending-arch.md).

### **Step 2: Inject the Context into the CLI**

Open your terminal, navigate to the directory where you saved the file, and launch the CLI:

gemini

Use the CLI's native @ command to pull the file directly into your prompt. This feeds the CLI agent everything we have mapped out so far:

\> @vending-arch.md Read this file to understand the current Java, DESFire, and RC522 architecture. Let's start translating the C hardware abstraction layer.

### **Step 3: Save Your CLI Session**

Once the CLI processes the file and responds, save the session state locally so you don't have to re-ingest the file later. Use the CLI's chat management commands:

\> /chat save vending-init

Whenever you close the terminal, you can jump right back into the flow by running /chat resume vending-init.

---

*User prompt: hmm how about we start with making a beautiful and rich ui and log print which item is selected and also payment screen and preparation screen what kind of vending machine should we use and also it but how to tell it instead of vibe coding the logic to guide me to do the logic but i dont know how to make beautifull designs gemini should do it*

Response: A premium coffee vending machine is the perfect theme for this project. When you are up all night following the syllabus from both books, a steady virtual supply of caffeine is practically a requirement to keep the code flowing. We can call it **"The Midnight Brewer."**

Here is the deal we will make: **I will write the "beautiful design" UI code** using JavaFX (which runs wonderfully on a Raspberry Pi 7" touchscreen and looks incredibly modern). **For the logic, I will act as your senior developer.** I will leave empty methods in the UI code, and instead of giving you the answers, I will ask you architectural questions to guide you in translating your C code into Java classes.

### **The UI Architecture (JavaFX)**

For a 7" touchscreen, we want a dark mode theme, large touch targets, and a clean flow between three screens:

1. **Selection Screen:** Large buttons for Espresso, Latte, and Americano.  
2. **Payment Screen:** A pulsing "Tap DESFire Card" animation.  
3. **Preparation Screen:** A simulated progress bar for the brewing process.

Here is your complete, ready-to-run JavaFX UI foundation. It handles all the screen switching, beautiful CSS styling, and logs the selected items to the console.

import javafx.animation.PauseTransition;  
import javafx.application.Application;  
import javafx.geometry.Pos;  
import javafx.scene.Scene;  
import javafx.scene.control.Button;  
import javafx.scene.control.Label;  
import javafx.scene.control.ProgressBar;  
import javafx.scene.layout.HBox;  
import javafx.scene.layout.StackPane;  
import javafx.scene.layout.VBox;  
import javafx.scene.paint.Color;  
import javafx.scene.text.Font;  
import javafx.stage.Stage;  
import javafx.util.Duration;

public class MidnightBrewerUI extends Application {

    private StackPane rootPane;  
    private VBox selectionScreen, paymentScreen, preparationScreen;  
    private Label paymentStatusLabel;  
      
    // The currently selected drink  
    private String selectedItem \= "";

    @Override  
    public void start(Stage primaryStage) {  
        rootPane \= new StackPane();  
        rootPane.setStyle("-fx-background-color: \#121212;"); // Sleek dark mode

        buildSelectionScreen();  
        buildPaymentScreen();  
        buildPreparationScreen();

        // Start on the selection screen  
        rootPane.getChildren().add(selectionScreen);

        Scene scene \= new Scene(rootPane, 800, 480); // Standard 7" Pi resolution  
        primaryStage.setTitle("The Midnight Brewer");  
        primaryStage.setScene(scene);  
        primaryStage.setFullScreen(true); // Great for Raspberry Pi  
        primaryStage.show();  
    }

    private void buildSelectionScreen() {  
        selectionScreen \= new VBox(30);  
        selectionScreen.setAlignment(Pos.CENTER);

        Label title \= new Label("Select Your Fuel");  
        title.setStyle("-fx-text-fill: \#E0E0E0; \-fx-font-size: 36px; \-fx-font-weight: bold;");

        HBox buttonLayout \= new HBox(20);  
        buttonLayout.setAlignment(Pos.CENTER);

        Button btnEspresso \= createDrinkButton("Espresso", "$1.50");  
        Button btnLatte \= createDrinkButton("Latte", "$2.50");  
        Button btnAmericano \= createDrinkButton("Americano", "$2.00");

        buttonLayout.getChildren().addAll(btnEspresso, btnLatte, btnAmericano);  
        selectionScreen.getChildren().addAll(title, buttonLayout);  
    }

    private Button createDrinkButton(String name, String price) {  
        Button btn \= new Button(name \+ "\\n" \+ price);  
        btn.setStyle(  
            "-fx-background-color: \#1E1E1E; " \+  
            "-fx-text-fill: \#BB86FC; " \+  
            "-fx-font-size: 24px; " \+  
            "-fx-font-weight: bold; " \+  
            "-fx-border-color: \#BB86FC; " \+  
            "-fx-border-radius: 15; " \+  
            "-fx-background-radius: 15; " \+  
            "-fx-padding: 30 40;"  
        );  
          
        btn.setOnAction(e \-\> {  
            selectedItem \= name;  
            System.out.println("LOG: User selected " \+ selectedItem);  
            switchToScreen(paymentScreen);  
              
            // TODO: Start the RC522 polling logic here\!  
            startNfcPolling();   
        });  
        return btn;  
    }

    private void buildPaymentScreen() {  
        paymentScreen \= new VBox(40);  
        paymentScreen.setAlignment(Pos.CENTER);

        paymentStatusLabel \= new Label("Please tap your DESFire Card...");  
        paymentStatusLabel.setStyle("-fx-text-fill: \#03DAC6; \-fx-font-size: 28px;");

        Button btnCancel \= new Button("Cancel");  
        btnCancel.setStyle("-fx-background-color: \#CF6679; \-fx-text-fill: white; \-fx-font-size: 20px; \-fx-background-radius: 10;");  
        btnCancel.setOnAction(e \-\> {  
            System.out.println("LOG: Transaction cancelled by user.");  
            // TODO: Stop RC522 polling here  
            switchToScreen(selectionScreen);  
        });

        // HIDDEN BUTTON to simulate a successful card tap for now  
        Button btnSimulateTap \= new Button("Simulate Card Tap");  
        btnSimulateTap.setStyle("-fx-background-color: transparent; \-fx-text-fill: \#444444;");  
        btnSimulateTap.setOnAction(e \-\> handleSuccessfulPayment());

        paymentScreen.getChildren().addAll(paymentStatusLabel, btnCancel, btnSimulateTap);  
    }

    private void buildPreparationScreen() {  
        preparationScreen \= new VBox(30);  
        preparationScreen.setAlignment(Pos.CENTER);

        Label prepLabel \= new Label("Brewing your coffee...");  
        prepLabel.setStyle("-fx-text-fill: \#E0E0E0; \-fx-font-size: 32px;");

        ProgressBar progressBar \= new ProgressBar();  
        progressBar.setPrefSize(400, 30);  
        progressBar.setStyle("-fx-accent: \#BB86FC;");

        preparationScreen.getChildren().addAll(prepLabel, progressBar);  
    }

    private void switchToScreen(VBox screen) {  
        rootPane.getChildren().clear();  
        rootPane.getChildren().add(screen);  
    }

    // \--------------------------------------------------------  
    // THE LOGIC ZONE (Where you take over)  
    // \--------------------------------------------------------

    private void startNfcPolling() {  
        // GUIDANCE NOTE: This is where we will hook up your translated C code.  
        System.out.println("LOG: Hardware polling started...");  
    }

    public void handleSuccessfulPayment() {  
        System.out.println("LOG: Payment successful. Deducting credits for " \+ selectedItem);  
        switchToScreen(preparationScreen);

        // Simulate brewing time, then reset the machine  
        PauseTransition delay \= new PauseTransition(Duration.seconds(4));  
        delay.setOnFinished(e \-\> {  
            System.out.println("LOG: Drink finished. Resetting UI.");  
            switchToScreen(selectionScreen);  
        });  
        delay.play();  
    }

    public static void main(String\[\] args) {  
        launch(args);  
    }  
}

### **Your Turn: Guiding the Logic**

Now we have a beautiful shell. Let's look at the hardware connection logic you wrote in C for the RC522 and Nucleo. In C, you likely had raw functions to initialize SPI and pull pins HIGH/LOW.

To translate this cleanly into Java Object-Oriented Programming, we need to encapsulate it.

**My question to you as your guide:** How should we design the very first Java class that touches the hardware? Should we pass the SPI interface directly into the constructor of an RC522 class, or should we create a HardwareBus interface first so we can easily swap between SPI on the Raspberry Pi and a simulated software mock for testing on your computer? What fields and methods do you think this hardware class needs to expose to the rest of our app?

---

*User prompt: give me a claude.md*

Response: \# Project: The Midnight Brewer (Virtual Vending Machine)

\#\# Overview  
"The Midnight Brewer" is a Java-based virtual vending machine system designed to run on a Raspberry Pi with a 7" touchscreen. It bridges high-level Object-Oriented Programming (OOP) UI design (JavaFX) with low-level hardware communication (SPI) to interact with DESFire EV1 smart cards or an Android Host Card Emulation (HCE) app.

\#\# Hardware Stack  
\*   \*\*Host:\*\* Raspberry Pi with a 7" Touchscreen Display.  
\*   \*\*NFC/RFID Module:\*\* RC522. (Note: The RC522 does not natively support ISO-14443-4 APDU framing. A custom software stack translated from a Nucleo C repository is being used to manually handle I-blocks, R-blocks, and S-blocks).  
\*   \*\*Client:\*\* Physical DESFire EV1 Cards OR Android phone running an HCE wallet application.

\#\# Software Architecture

\#\#\# 1\. User Interface (JavaFX)  
\*   \*\*State Machine Flow:\*\*   
    1\. \`Selection Screen\` (Choose Espresso, Latte, Americano).  
    2\. \`Payment Screen\` (Awaits NFC tap, handles hardware polling).  
    3\. \`Preparation Screen\` (Simulates brewing progress).  
\*   \*\*Styling:\*\* Dark mode UI with large touch targets optimized for the 7" Pi display.

\#\#\# 2\. Hardware Abstraction Layer (HAL)  
Encapsulating the raw SPI communication to isolate hardware logic from the rest of the application.  
\*   \*\*\`HardwareBus\` (Interface):\*\* Defines the contract for read/write operations, allowing the system to swap between real Pi4J SPI drivers and mocked software drivers for desktop testing.  
\*   \*\*\`RC522Driver\` (Class):\*\* Implements the raw register reading/writing and manages the physical connection state.

\#\#\# 3\. Protocol Stack & DESFire EV1 Logic  
\*   \*\*ISO-DEP Layer:\*\* An \`Iso14443Card\` interface defining \`transceive(byte\[\] apdu)\`. The \`RC522SoftwareIsoDep\` implementation handles the complex software-side APDU block chaining required because of the RC522's limitations.  
\*   \*\*Cryptography (\`CryptoEngine\`):\*\* Handles AES/3DES authentication, key rotation ($RndA$ / $RndB$), and Cipher Block Chaining (CBC)\[cite: 2\].  
\*   \*\*Integrity:\*\* Calculates and verifies CMAC (Cipher-based Message Authentication Code) over command and parameter bytes\[cite: 2\].

\#\#\# 4\. OOP Error Handling  
Replacing C-style integer return codes with a robust Java Exception hierarchy.  
\*   \*\*Hardware Exceptions:\*\* \`RC522TimeoutException\`, \`IsoDepFramingException\`.  
\*   \*\*Application Exceptions:\*\* \`QuantityLimitExceededException\`\[cite: 1\], \`InsufficientFundsException\`\[cite: 1\], \`AuthenticationFailedException\`, \`CMACMismatchException\`.

\#\# Current Project State & Next Steps  
1\.  \*\*UI:\*\* JavaFX shell is complete with state switching and simulated payment handling.  
2\.  \*\*Hardware/C-to-Java Translation:\*\* Currently architecting the first Java class to touch the hardware.   
3\.  \*\*Immediate Goal:\*\* Design the \`HardwareBus\` interface and translate the C-based RC522 SPI initialization logic into the encapsulated \`RC522Driver\` class.

---

