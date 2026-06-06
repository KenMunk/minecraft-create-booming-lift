package com.kenmunk.createboomingleft;

public enum BlockBreakCause {
    /** Block destroyed by any explosion. Damages sublevel health. */
    EXPLOSION,
    /** Block intentionally removed by a player. Does not damage health. */
    PLAYER_MINING,
    /** Block removed as a direct result of our crash-detonation logic. Damages sublevel health. */
    PHYSICS_CRASH,
    /** Cause unknown or not categorised (fire, gravity, modded mechanics, etc.). Does not damage health. */
    UNKNOWN;

    public boolean affectsHealth() {
        return this == EXPLOSION || this == PHYSICS_CRASH;
    }
}
