package com.imin.iminapi.audienceplan.engine;

import com.imin.iminapi.audienceplan.config.AudiencePlanLogic.Modes;

/** Plan mode from how many members the organizer may lawfully email (logic bank "three modes"). */
public final class ModeSelector {

    public enum Mode { COLD, WARM, HOT }

    private ModeSelector() {}

    public static Mode select(int mailable, Modes modes) {
        if (mailable < 0) throw new IllegalArgumentException("mailable must be >= 0: " + mailable);
        if (mailable >= modes.hotMinMailable()) return Mode.HOT;
        if (mailable >= modes.warmMinMailable()) return Mode.WARM;
        return Mode.COLD;
    }
}
