package com.midnightbrewer;

/**
 * Proves the toolchain works. No hardware required.
 *
 * <p>If this runs and prints sensible values, then: Java is installed, Maven
 * compiled your code, the classpath is correct, and you can run a program.
 * That is four things that can go wrong, eliminated in one step.
 *
 * <p>Run it with:
 * <pre>
 *   mvn -q compile
 *   java -cp target/classes com.midnightbrewer.ToolchainCheck
 * </pre>
 *
 * <p>You can delete this class once you are past Chapter 1 of the main book.
 */
public final class ToolchainCheck {

    private ToolchainCheck() {
        // Utility class: never instantiated. A private constructor is how you
        // say that in Java.
    }

    public static void main(String[] args) {
        System.out.println("=== toolchain check ===");
        System.out.printf("java.version    = %s%n", System.getProperty("java.version"));
        System.out.printf("java.vendor     = %s%n", System.getProperty("java.vendor"));
        System.out.printf("os.name         = %s%n", System.getProperty("os.name"));
        System.out.printf("os.arch         = %s%n", System.getProperty("os.arch"));
        System.out.printf("user.dir        = %s%n", System.getProperty("user.dir"));

        int major = Runtime.version().feature();
        System.out.printf("major version   = %d%n", major);

        if (major < 21) {
            System.out.println();
            System.out.println("  PROBLEM: this project needs Java 21 or newer.");
            System.out.println("  See section 4.3 of the setup book.");
            return;
        }

        // A trivial computation, to prove the JIT and the standard library work.
        System.out.printf("sanity          = %s%n", describe(0x92));

        System.out.println();
        System.out.println("  -> toolchain OK. Nothing above needed hardware.");
    }

    /**
     * Formats a byte value the way the rest of this project will.
     *
     * <p>Note the {@code & 0xFF}: Java's {@code byte} is signed, so 0x92 read
     * as a byte is -110, not 146. You will meet this again in Chapter 1 of the
     * main book, and it will cost you an hour if you have not seen it before.
     */
    static String describe(int value) {
        return String.format("0x%02X (%d)", value & 0xFF, value & 0xFF);
    }
}
