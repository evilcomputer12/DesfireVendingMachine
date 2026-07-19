package com.midnightbrewer.reference.iso14443;

import com.midnightbrewer.reference.picc.SelectedCard;
import com.midnightbrewer.reference.picc.Uid;

/**
 * A card that has completed both halves of activation: selected under
 * ISO 14443-3, then brought up to ISO 14443-4 by RATS.
 *
 * <p>It composes {@link SelectedCard} rather than restating it, so the part 3
 * result stays one object and part 4 adds only what it learned -- the ATS and
 * the frame size negotiated from it.
 *
 * <p>This is what {@code platform_activate_card} would return if it returned
 * anything other than a status code.
 */
public final class ActivatedCard {

    private final SelectedCard selected;
    private final AnswerToSelect ats;

    ActivatedCard(SelectedCard selected, AnswerToSelect ats) {
        this.selected = selected;
        this.ats = ats;
    }

    /** The 4- or 7-byte UID. */
    public Uid uid() {
        return selected.uid();
    }

    /** The SAK from the final cascade level. */
    public int sak() {
        return selected.sak();
    }

    /** The part 3 activation result in full. */
    public SelectedCard selection() {
        return selected;
    }

    /** The ATS returned by RATS. */
    public AnswerToSelect answerToSelect() {
        return ats;
    }

    /** The frame size in force for this session. */
    public FrameSize frameSize() {
        return ats.frameSize();
    }

    @Override
    public String toString() {
        return selected + " ATS=" + ats;
    }
}
