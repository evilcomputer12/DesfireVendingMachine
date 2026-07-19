package com.midnightbrewer.reference.diag;

/**
 * DESFire command bytes to human names, for readable traces.
 *
 * <p>Purely diagnostic. Nothing on the protocol path depends on this; it only
 * turns {@code 0x71} into {@code AuthenticateEV2First} in a log.
 */
final class DesfireCommandNames {

    private DesfireCommandNames() {
    }

    static String of(int command) {
        switch (command) {
            case 0x60: return "GetVersion";
            case 0x5A: return "SelectApplication";
            case 0xCA: return "CreateApplication";
            case 0xC4: return "ChangeKey";
            case 0xCC: return "CreateValueFile";
            case 0x6C: return "GetValue";
            case 0x0C: return "Credit";
            case 0xDC: return "Debit";
            case 0x1C: return "LimitedCredit";
            case 0xC7: return "CommitTransaction";
            case 0xA7: return "AbortTransaction";
            case 0x0A: return "AuthenticateLegacy";
            case 0x1A: return "AuthenticateISO";
            case 0x71: return "AuthenticateEV2First";
            case 0x77: return "AuthenticateEV2NonFirst";
            case 0xAF: return "  └ continuation";
            case 0x45: return "GetKeySettings";
            case 0x64: return "GetKeyVersion";
            case 0xF5: return "GetFileSettings";
            case 0x6F: return "GetFileIDs";
            case -1:   return "(empty)";
            default:   return String.format("cmd 0x%02X", command);
        }
    }
}
