package com.midnightbrewer.reference.iso14443.block;

/**
 * A PCB whose top two bits are {@code 0b01} -- a bit pattern ISO 14443-4 does
 * not define.
 *
 * <p>It exists so that classification is total. Without it, {@link ProtocolBlock#of}
 * would have to return null or throw for a value that arrives from the air and
 * is therefore entirely outside this code's control, and every caller would
 * carry the handling.
 *
 * <p>In practice it means a corrupted frame or a card that is not speaking the
 * protocol, and the ISO-DEP layer treats it the way the C does: the exchange
 * ends. The C's {@code p4_pcb_kind} prints {@code "?"} for the same case.
 */
public final class UnrecognisedBlock extends ProtocolBlock {

    UnrecognisedBlock(int pcb) {
        super(pcb);
    }

    @Override
    public String describe() {
        return "?";
    }
}
