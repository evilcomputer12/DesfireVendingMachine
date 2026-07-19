package com.midnightbrewer.reference.iso14443;

import com.midnightbrewer.reference.desfire.DesfireApduChannel;
import com.midnightbrewer.reference.desfire.DesfireVersion;
import com.midnightbrewer.reference.diag.ProtocolTrace;
import com.midnightbrewer.reference.error.NfcException;
import com.midnightbrewer.reference.error.ProtocolException;
import com.midnightbrewer.reference.pcd.Rc522Driver;
import com.midnightbrewer.reference.support.SimulatedDesfireCard;
import com.midnightbrewer.reference.support.SimulatedRc522;
import com.midnightbrewer.reference.support.VirtualTimebase;
import com.midnightbrewer.reference.util.Hex;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end ISO 14443-4, driven against a simulated chip and a simulated card
 * with no hardware present.
 *
 * <p>These are the tests the port exists for. Chaining, block numbering and WTX
 * are the three things that are hard to get right, impossible to observe from
 * the outside, and expensive to debug on real hardware -- a wrong chunk
 * boundary looks exactly like a card that has gone out of range.
 *
 * <p>The simulated card validates the CRC of every frame it receives and
 * enforces the chaining rules, so a driver that split an APDU wrongly or forgot
 * to advance the block number fails here rather than at the antenna.
 */
class IsoDepExchangeTest {

    private SimulatedDesfireCard card;
    private SimulatedRc522 chip;
    private VirtualTimebase clock;
    private Rc522IsoDepTransceiver transceiver;

    @BeforeEach
    void setUp() throws NfcException {
        card = new SimulatedDesfireCard();
        chip = new SimulatedRc522(card);
        clock = new VirtualTimebase();
        Rc522Driver driver = new Rc522Driver(chip, clock, ProtocolTrace.none());
        driver.initialise();
        transceiver = new Rc522IsoDepTransceiver(driver);
    }

    private ActivatedCard activate() throws NfcException {
        assertTrue(transceiver.isCardPresent(), "the simulated card must answer REQA");
        return transceiver.activate();
    }

    // ------------------------------------------------------------- activation

    @Test
    void activationWalksBothCascadeLevelsAndAssemblesTheSevenByteUid() throws NfcException {
        ActivatedCard activated = activate();

        assertEquals("04 11 22 33 44 55 66", activated.uid().toString(),
                "cascade tag and both BCCs must be dropped");
        assertEquals(7, activated.uid().length());
        assertTrue(activated.uid().isDoubleSize());
        assertEquals(0x20, activated.sak(), "the level 2 SAK is the one that counts");
        assertTrue(activated.selection().supportsIso14443Part4());
    }

    @Test
    void requestUsesSevenBitFramingForReqa() throws NfcException {
        transceiver.isCardPresent();

        // BitFramingReg holds TxLastBits in bits 2..0; REQA is a 7-bit frame and
        // a card ignores it entirely if eight bits go out.
        List<byte[]> frames = chip.transmittedFrames();
        assertEquals(1, frames.get(0).length, "REQA is a single byte");
        assertEquals(0x26, frames.get(0)[0] & 0xFF);
    }

    @Test
    void ratsNegotiatesTheFrameSizeAndResetsTheBlockNumber() throws NfcException {
        ActivatedCard activated = activate();

        // The simulated ATS is 06 75 77 81 02 80. FSCI is the LOW nibble of T0
        // (0x75 -> 5 -> FSC 64). The C reads the high nibble instead and would
        // get 128; that figure is kept for trace comparison only.
        assertEquals(64, activated.frameSize().frameBytes());
        assertEquals(128, activated.answerToSelect().legacyFrameSize().frameBytes(),
                "the C firmware's reading, kept for diagnosis only");

        // The first I-block after RATS must carry PCB 0x02.
        chip.clearTransmittedFrames();
        transceiver.transceive(Hex.decode("90 60 00 00 00"));
        assertEquals(0x02, chip.transmittedFrames().get(0)[0] & 0xFF);
    }

    // ---------------------------------------------------------------- exchange

    @Test
    void shortApduGoesOutAsASingleUnchainedIBlock() throws NfcException {
        activate();
        card.respondingWith(apdu -> Hex.decode("AA BB 91 00"));
        chip.clearTransmittedFrames();

        byte[] response = transceiver.transceive(Hex.decode("90 60 00 00 00"));

        assertEquals(1, chip.transmittedFrames().size(), "no chaining for a 5-byte APDU");
        byte[] sent = chip.transmittedFrames().get(0);
        assertEquals(0x02, sent[0] & 0xFF, "I-block, block number 0, chaining clear");
        assertEquals(8, sent.length, "PCB + 5 INF + 2 CRC");
        assertEquals("AA BB 91 00", Hex.encode(response));
    }

    @Test
    void blockNumberAlternatesAcrossConsecutiveApdus() throws NfcException {
        activate();
        card.respondingWith(apdu -> Hex.decode("91 00"));
        chip.clearTransmittedFrames();

        transceiver.transceive(Hex.decode("90 60 00 00 00"));
        transceiver.transceive(Hex.decode("90 AF 00 00 00"));
        transceiver.transceive(Hex.decode("90 AF 00 00 00"));

        List<byte[]> frames = chip.transmittedFrames();
        assertEquals(0x02, frames.get(0)[0] & 0xFF);
        assertEquals(0x03, frames.get(1)[0] & 0xFF);
        assertEquals(0x02, frames.get(2)[0] & 0xFF);
    }

    // ---------------------------------------------------------------- chaining

    @Test
    void longApduIsSplitAtFortyBytesAndReassembledByTheCard() throws NfcException {
        activate();
        card.respondingWith(apdu -> Hex.decode("91 00"));
        chip.clearTransmittedFrames();

        // 100 bytes: the transmit cap is 40, so this must go as 40 + 40 + 20.
        byte[] apdu = new byte[100];
        for (int i = 0; i < apdu.length; i++) {
            apdu[i] = (byte) i;
        }

        transceiver.transceive(apdu);

        List<byte[]> frames = chip.transmittedFrames();
        assertEquals(3, frames.size(), "100 bytes at 40 per frame is three blocks");

        assertEquals(43, frames.get(0).length, "PCB + 40 INF + 2 CRC");
        assertEquals(43, frames.get(1).length);
        assertEquals(23, frames.get(2).length, "PCB + 20 INF + 2 CRC");

        // Chaining set on all but the last, and the block number alternating.
        assertEquals(0x12, frames.get(0)[0] & 0xFF, "block 0, chaining");
        assertEquals(0x13, frames.get(1)[0] & 0xFF, "block 1, chaining");
        assertEquals(0x02, frames.get(2)[0] & 0xFF, "block 0, final");

        assertArrayEquals(apdu, card.receivedApdus().get(0),
                "the card must reassemble exactly what was sent");
    }

    @Test
    void anApduExactlyAtTheLimitIsNotChained() throws NfcException {
        activate();
        card.respondingWith(apdu -> Hex.decode("91 00"));
        chip.clearTransmittedFrames();

        byte[] apdu = new byte[40];

        transceiver.transceive(apdu);

        assertEquals(1, chip.transmittedFrames().size(), "40 fits, 41 would not");
        assertEquals(0x02, chip.transmittedFrames().get(0)[0] & 0xFF, "chaining bit clear");
    }

    @Test
    void oneByteOverTheLimitChainsIntoTwoBlocks() throws NfcException {
        activate();
        card.respondingWith(apdu -> Hex.decode("91 00"));
        chip.clearTransmittedFrames();

        byte[] apdu = new byte[41];

        transceiver.transceive(apdu);

        List<byte[]> frames = chip.transmittedFrames();
        assertEquals(2, frames.size());
        assertEquals(0x12, frames.get(0)[0] & 0xFF, "first block chains");
        assertEquals(43, frames.get(0).length, "PCB + 40 INF + 2 CRC");
        assertEquals(0x03, frames.get(1)[0] & 0xFF, "second block is final");
        assertEquals(4, frames.get(1).length, "PCB + 1 INF + 2 CRC");
        assertArrayEquals(apdu, card.receivedApdus().get(0));
    }

    @Test
    void aCardThatBreaksTheChainIsReportedAsAProtocolError() throws NfcException {
        activate();
        // A card that answers a chained block with an I-block instead of R(ACK).
        chip.setPicc((request, txLastBits) -> {
            if (request.length > 3 && (request[0] & 0xC0) == 0x00) {
                return Hex.decode("02 91 00 3E 9D");   // wrong: an I-block
            }
            return null;
        });

        ProtocolException error = assertThrows(ProtocolException.class,
                () -> transceiver.transceive(new byte[100]));

        assertTrue(error.getMessage().contains("R(ACK)"), error.getMessage());
    }

    // --------------------------------------------------------------------- WTX

    @Test
    void waitingTimeExtensionIsAnsweredAndWaitedOut() throws NfcException {
        activate();
        card.respondingWith(apdu -> Hex.decode("91 00")).requestingWtx(1, 3);
        chip.clearTransmittedFrames();
        clock.resetCounters();

        byte[] response = transceiver.transceive(Hex.decode("90 60 00 00 00"));

        assertEquals("91 00", Hex.encode(response), "the response still arrives");

        List<byte[]> frames = chip.transmittedFrames();
        assertEquals(2, frames.size(), "the I-block, then the S(WTX) reply");
        assertEquals(0xF2, frames.get(1)[0] & 0xFF, "S-block, RFU bit set, WTX");
        assertEquals(3, frames.get(1)[1] & 0xFF, "the card's multiplier echoed back");

        assertTrue(clock.totalSlept() >= 3 * WaitingTimeExtension.MILLIS_PER_UNIT,
                "the reader must actually wait 80 ms per unit");
    }

    @Test
    void severalConsecutiveExtensionsAreAllGranted() throws NfcException {
        activate();
        card.respondingWith(apdu -> Hex.decode("91 00")).requestingWtx(5, 1);
        chip.clearTransmittedFrames();

        byte[] response = transceiver.transceive(Hex.decode("90 60 00 00 00"));

        assertEquals("91 00", Hex.encode(response));
        assertEquals(6, chip.transmittedFrames().size(), "one I-block plus five S(WTX) replies");
    }

    @Test
    void multiplierIsMaskedToSixBitsAndZeroBecomesOne() {
        // The C keeps only the low 6 bits and substitutes 1 for a zero, because
        // a zero multiplier would produce a reply the card rejects.
        assertEquals(1, WaitingTimeExtension.parse(new byte[] {(byte) 0xF2, 0x00}, 2).multiplier());
        assertEquals(3, WaitingTimeExtension.parse(new byte[] {(byte) 0xF2, (byte) 0xC3}, 2)
                .multiplier());
        assertEquals(1, WaitingTimeExtension.parse(new byte[] {(byte) 0xF2}, 1).multiplier(),
                "a frame too short to carry one defaults to 1");
        assertEquals(240, WaitingTimeExtension.parse(new byte[] {(byte) 0xF2, 0x03}, 2)
                .delayMillis());
    }

    // ------------------------------------------------------- response trimming

    @Test
    void desfireResponseIsTrimmedAtTheLastStatusMarker() throws NfcException {
        activate();
        // Encrypted payload containing a stray 0x91: the real status marker is
        // the last one, so scanning backwards is what keeps the payload intact.
        card.respondingWith(apdu -> Hex.decode("91 AA BB 91 00"));

        byte[] response = transceiver.transceive(Hex.decode("90 60 00 00 00"));

        assertEquals("91 AA BB 91 00", Hex.encode(response));
    }

    // ------------------------------------------------------------- full stack

    @Test
    void getVersionFollowsTheContinuationChainAndDecodes() throws NfcException {
        activate();

        // A DESFire EV1 answering GetVersion in three frames.
        card.respondingWith(apdu -> {
            int command = apdu[1] & 0xFF;
            if (command == 0x60) {
                return Hex.decode("04 01 01 01 00 18 05 91 AF");
            }
            if (command == 0xAF) {
                return nextVersionFrame();
            }
            return Hex.decode("91 00");
        });

        DesfireVersion version = new DesfireApduChannel(transceiver).getVersion();

        assertTrue(version.isComplete(), "all three frames must be collected");
        assertEquals("MIFARE DESFire EV1", version.productName());
        assertEquals(4096, version.storageSizeBytes());
        assertEquals("04 11 22 33 44 55 66", Hex.encode(version.uid()));
        assertEquals(2019, version.productionYear());
    }

    private int versionFrame;

    private byte[] nextVersionFrame() {
        versionFrame++;
        if (versionFrame == 1) {
            return Hex.decode("04 01 01 01 03 18 05 91 AF");
        }
        return Hex.decode("04 11 22 33 44 55 66 AA BB CC DD EE 12 19 91 00");
    }

    @Test
    void deselectSendsTheSupervisoryBlockTheCSends() throws NfcException {
        activate();
        chip.clearTransmittedFrames();

        transceiver.deselect();

        byte[] frame = chip.transmittedFrames().get(0);
        assertEquals(3, frame.length, "PCB + CRC");
        assertEquals(0xC2, frame[0] & 0xFF);
    }

    @Test
    void fieldResetClearsProtocolStateSoTheNextSessionStartsClean() throws NfcException {
        ActivatedCard activated = activate();
        assertEquals(64, activated.frameSize().frameBytes());

        transceiver.resetField(5);

        assertEquals(FrameSize.DEFAULT, transceiver.frameSize(),
                "frame size returns to the 64-byte assumption");

        card.respondingWith(apdu -> Hex.decode("91 00"));
        transceiver.activate();
        chip.clearTransmittedFrames();
        transceiver.transceive(Hex.decode("90 60 00 00 00"));

        assertEquals(0x02, chip.transmittedFrames().get(0)[0] & 0xFF,
                "the block number restarts, matching the freshly-woken card");
    }
}
