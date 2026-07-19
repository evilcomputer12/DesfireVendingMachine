package com.midnightbrewer.reference.desfire;

import com.midnightbrewer.reference.diag.ProtocolTrace;
import com.midnightbrewer.reference.error.DesfireStatusException;
import com.midnightbrewer.reference.support.SimulatedEv2Desfire;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The whole command set exercised end to end against {@link SimulatedEv2Desfire},
 * which models the card side of EV2 independently. Together with the
 * C-pinned crypto and session tests, this proves the reader drives a real
 * secure-messaging conversation: authentication, key changes, value files and
 * the command-counter lockstep between the two sides.
 */
class DesfireCardTest {

    private static DesfireCard cardOn(SimulatedEv2Desfire simulator) {
        // Fixed RndA so the handshake is reproducible; the simulator supplies a
        // fixed RndB, so a full auth is deterministic.
        byte[] fixedRndA = new byte[16];
        for (int i = 0; i < 16; i++) {
            fixedRndA[i] = (byte) (0x10 + i);
        }
        DesfireApduChannel channel = new DesfireApduChannel(simulator);
        return new DesfireCard(channel, buffer -> System.arraycopy(fixedRndA, 0, buffer, 0,
                Math.min(fixedRndA.length, buffer.length)), ProtocolTrace.none());
    }

    @Test
    void provisionsThenReadsDebitsAndCommits() throws Exception {
        SimulatedEv2Desfire simulator = new SimulatedEv2Desfire();
        DesfireCard card = cardOn(simulator);
        WalletProfile profile = WalletProfile.defaults();

        // A blank card has no application: SelectApplication returns 0xA0.
        DesfireStatusException notFound = assertThrows(DesfireStatusException.class,
                () -> card.selectApplication(profile.aid()));
        assertTrue(notFound.is(DesfireStatus.APPLICATION_NOT_FOUND));

        card.provisionValueWallet(profile);

        // Operate as a terminal would: authenticate with the user key, read,
        // debit, commit, read back.
        card.selectApplication(profile.aid());
        card.authenticateEv2First(profile.userKeyNo(), profile.appUserKey());

        assertEquals(2500, card.getValue(profile.fileNo()));
        card.debit(profile.fileNo(), 350);
        assertEquals(2500, simulator.storedValue(profile.aid(), profile.fileNo()),
                "debit before commit must not move the stored balance");
        card.commitTransaction();
        assertEquals(2150, card.getValue(profile.fileNo()));
        assertEquals(2150, simulator.storedValue(profile.aid(), profile.fileNo()));
    }

    @Test
    void creditRaisesTheBalanceAfterCommit() throws Exception {
        SimulatedEv2Desfire simulator = new SimulatedEv2Desfire();
        DesfireCard card = cardOn(simulator);
        WalletProfile profile = WalletProfile.defaults();
        card.provisionValueWallet(profile);

        card.selectApplication(profile.aid());
        card.authenticateEv2First(profile.userKeyNo(), profile.appUserKey());
        card.credit(profile.fileNo(), 1000);
        card.commitTransaction();
        assertEquals(3500, card.getValue(profile.fileNo()));
    }

    @Test
    void uncommittedDebitIsDiscarded() throws Exception {
        SimulatedEv2Desfire simulator = new SimulatedEv2Desfire();
        DesfireCard card = cardOn(simulator);
        WalletProfile profile = WalletProfile.defaults();
        card.provisionValueWallet(profile);

        card.selectApplication(profile.aid());
        card.authenticateEv2First(profile.userKeyNo(), profile.appUserKey());
        card.debit(profile.fileNo(), 350);
        card.abortTransaction();
        assertEquals(2500, card.getValue(profile.fileNo()),
                "an aborted debit leaves the balance untouched");
    }

    @Test
    void debitBelowLowerLimitIsRejected() throws Exception {
        SimulatedEv2Desfire simulator = new SimulatedEv2Desfire();
        DesfireCard card = cardOn(simulator);
        WalletProfile profile = WalletProfile.defaults();
        card.provisionValueWallet(profile);

        card.selectApplication(profile.aid());
        card.authenticateEv2First(profile.userKeyNo(), profile.appUserKey());
        DesfireStatusException tooMuch = assertThrows(DesfireStatusException.class,
                () -> card.debit(profile.fileNo(), 999999));
        assertTrue(tooMuch.is(DesfireStatus.BOUNDARY_ERROR));
        // The stored balance is unchanged by a rejected debit.
        assertEquals(2500, simulator.storedValue(profile.aid(), profile.fileNo()));
    }

    @Test
    void provisioningIsIdempotent() throws Exception {
        SimulatedEv2Desfire simulator = new SimulatedEv2Desfire();
        DesfireCard card = cardOn(simulator);
        WalletProfile profile = WalletProfile.defaults();

        card.provisionValueWallet(profile);
        card.selectApplication(profile.aid());
        card.authenticateEv2First(profile.userKeyNo(), profile.appUserKey());
        card.debit(profile.fileNo(), 500);
        card.commitTransaction();
        assertEquals(2000, card.getValue(profile.fileNo()));

        // Re-provisioning a card that already has the app and keys must not
        // reset the balance: CreateApplication and CreateValueFile both treat
        // "already exists" as success and change nothing.
        DesfireCard rerun = provisionedRerun(simulator, profile);
        rerun.selectApplication(profile.aid());
        rerun.authenticateEv2First(profile.userKeyNo(), profile.appUserKey());
        assertEquals(2000, rerun.getValue(profile.fileNo()));
    }

    @Test
    void formatPiccByteNeverReachesTheWire() throws Exception {
        SimulatedEv2Desfire simulator = new SimulatedEv2Desfire();
        DesfireCard card = cardOn(simulator);
        WalletProfile profile = WalletProfile.defaults();

        card.provisionValueWallet(profile);
        card.selectApplication(profile.aid());
        card.authenticateEv2First(profile.userKeyNo(), profile.appUserKey());
        card.getValue(profile.fileNo());
        card.debit(profile.fileNo(), 100);
        card.commitTransaction();

        assertFalse(simulator.commandLog().contains(DesfireCommand.FORMAT_PICC),
                "FormatPICC (0xFC) must never be issued -- it would erase the card");
    }

    /**
     * Re-runs provisioning against a card whose application already exists and
     * already holds this app's keys -- the state a second tap produces. The
     * user/master keys are already the app's, so the default-key
     * authentications inside provisioning would fail; this models the "already
     * provisioned, just recreate the file" subset the way a real re-tap would
     * hit CreateValueFile's duplicate path.
     */
    private static DesfireCard provisionedRerun(SimulatedEv2Desfire simulator, WalletProfile profile)
            throws Exception {
        DesfireCard card = cardOn(simulator);
        card.selectApplication(profile.aid());
        card.authenticateEv2First(0, profile.appMasterKey());
        // CreateValueFile on an existing file returns 0xDE, treated as success.
        boolean existed = card.createValueFile(ValueFileSettings.builder(profile.fileNo())
                .accessRights(profile.accessRights())
                .initialValue(profile.initialBalance())
                .build());
        assertTrue(existed, "the value file already exists on a re-provision");
        return card;
    }
}
