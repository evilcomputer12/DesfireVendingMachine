package com.midnightbrewer.card;

/**
 * The card completed the exchange but returned a non-success status byte.
 *
 * <p>Maps to {@code DF_ERR_CMD}, and mirrors {@code df_sw_describe()} from
 * your C library. Keeping the raw byte on the exception means the log can
 * show exactly what the card said while the screen shows something readable.
 */
public class CardStatusException extends CardException {

    private static final long serialVersionUID = 1L;

    private final byte status;

    public CardStatusException(byte status, String context) {
        super(String.format("%s: card returned 0x%02X (%s)",
                context, status, describe(status)));
        this.status = status;
    }

    public byte getStatus() {
        return status;
    }

    /**
     * DESFire status byte to human text. Same table as {@code df_sw_describe},
     * restricted to the codes a value-file transaction can actually produce.
     */
    public static String describe(byte sw) {
        switch (sw & 0xFF) {
            case 0x00: return "OPERATION_OK";
            case 0x0C: return "NO_CHANGES";
            case 0x0E: return "OUT_OF_EEPROM_ERROR";
            case 0x1C: return "ILLEGAL_COMMAND_CODE";
            case 0x1E: return "INTEGRITY_ERROR";
            case 0x40: return "NO_SUCH_KEY";
            case 0x7E: return "LENGTH_ERROR";
            case 0x9D: return "PERMISSION_DENIED";
            case 0x9E: return "PARAMETER_ERROR";
            case 0xA0: return "APPLICATION_NOT_FOUND";
            case 0xA1: return "APPL_INTEGRITY_ERROR";
            case 0xAE: return "AUTHENTICATION_ERROR";
            case 0xAF: return "ADDITIONAL_FRAME";
            case 0xBE: return "BOUNDARY_ERROR";
            case 0xC1: return "PICC_INTEGRITY_ERROR";
            case 0xCA: return "COMMAND_ABORTED";
            case 0xCD: return "PICC_DISABLED_ERROR";
            case 0xCE: return "COUNT_ERROR";
            case 0xDE: return "DUPLICATE_ERROR";
            case 0xEE: return "EEPROM_ERROR";
            case 0xF0: return "FILE_NOT_FOUND";
            case 0xF1: return "FILE_INTEGRITY_ERROR";
            default:   return "UNKNOWN";
        }
    }

    @Override
    public String getUserMessage() {
        if ((status & 0xFF) == 0xBE) {
            return "Card limit reached. Transaction cancelled.";
        }
        if ((status & 0xFF) == 0x9D) {
            return "This card is not permitted to pay here.";
        }
        return "Card error. Please try again.";
    }
}
