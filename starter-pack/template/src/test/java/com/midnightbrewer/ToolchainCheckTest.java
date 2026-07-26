package com.midnightbrewer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the TEST toolchain works: JUnit is on the classpath, Surefire finds
 * tests, and assertions actually fail when they should.
 *
 * <p>That last point is not a joke. A test framework that silently runs zero
 * tests reports success, and "BUILD SUCCESS" with no tests run looks identical
 * to "BUILD SUCCESS with everything passing" unless you read the numbers.
 * Always check the count: this file should report <em>Tests run: 3</em>.
 */
class ToolchainCheckTest {

    @Test
    void junitIsWired() {
        assertTrue(true, "if this fails, reality has bigger problems");
    }

    @Test
    void signedByteMaskingWorks() {
        // The single most common Java-from-C mistake, encoded as a test.
        byte b = (byte) 0x92;

        assertEquals(-110, b, "a signed byte holding 0x92 reads as -110");
        assertEquals(146, b & 0xFF, "masked with 0xFF it reads as 146");
    }

    @Test
    void describeFormatsLikeTheRestOfTheProject() {
        assertEquals("0x92 (146)", ToolchainCheck.describe(0x92));
        assertEquals("0x00 (0)", ToolchainCheck.describe(0));
    }
}
