package com.kenmunk.createboomingleft;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Map;

/**
 * Injected onto ServerSubLevel via ServerSubLevelMixin.
 * Provides per-sublevel maps for each category of "core" block — blocks whose
 * presence materially affects the structure's behaviour — together with derived
 * health and peak-count values.
 *
 * Map key  = absolute world BlockPos.
 * Map value = Vec3 offset of that block's centre from the sublevel bounding-box
 *             centre (stored at scan time for use as a smoke spawn anchor).
 *
 * Scan cursor semantics (plain int — Java default 0 is meaningful):
 *   0      scan not yet started
 *   1..N   scan in progress; next block-index to check
 *  -1      scan complete
 *
 * Health model:
 *   maxHealth        — running sum of core-block masses; only ever increases.
 *   peakCoreBlocks   — historical peak of (tntBlocks.size + kineticBlocks.size);
 *                      only increases, updated whenever a block is added to either cache.
 *   maxCurrentHealth — maxHealth * (currentCoreBlockCount / peakCoreBlockCount);
 *                      shrinks as blocks are lost but can never exceed maxHealth.
 *   currentHealth    — reduced by collisions (fall-damage formula) and by non-player
 *                      core-block removals; recovers at 1 point per 200 ticks up to
 *                      maxCurrentHealth.
 */
public interface CoreBlockHolder {

    // -------------------------------------------------------------------------
    // Core-block caches
    // -------------------------------------------------------------------------

    /** Absolute position → relative-to-centre offset for each TNT block. */
    Map<BlockPos, Vec3> createBoomingLift$getTntBlocks();

    /** Absolute position → relative-to-centre offset for each Create kinetic block. */
    Map<BlockPos, Vec3> createBoomingLift$getKineticBlocks();

    // -------------------------------------------------------------------------
    // Scan cursor
    // -------------------------------------------------------------------------

    int  createBoomingLift$getScanCursor();
    void createBoomingLift$setScanCursor(int cursor);

    // -------------------------------------------------------------------------
    // Health
    // -------------------------------------------------------------------------

    float createBoomingLift$getMaxHealth();
    void  createBoomingLift$setMaxHealth(float health);

    float createBoomingLift$getCurrentHealth();
    void  createBoomingLift$setCurrentHealth(float health);

    int  createBoomingLift$getPeakCoreBlockCount();
    void createBoomingLift$setPeakCoreBlockCount(int count);

    /**
     * Dynamically computed: maxHealth scaled by the ratio of current core-block
     * count to peak core-block count. Returns maxHealth when peak is zero (before
     * the first block is cached) to avoid division by zero.
     */
    default float createBoomingLift$getMaxCurrentHealth() {
        final int peak = createBoomingLift$getPeakCoreBlockCount();
        if (peak == 0) return createBoomingLift$getMaxHealth();
        final int current = createBoomingLift$getTntBlocks().size()
                          + createBoomingLift$getKineticBlocks().size();
        return createBoomingLift$getMaxHealth() * ((float) current / peak);
    }

    /**
     * Call after any put to either core-block cache. Updates peakCoreBlockCount
     * if the current total exceeds the stored peak.
     */
    default void createBoomingLift$tryUpdatePeakCount() {
        final int current = createBoomingLift$getTntBlocks().size()
                          + createBoomingLift$getKineticBlocks().size();
        if (current > createBoomingLift$getPeakCoreBlockCount()) {
            createBoomingLift$setPeakCoreBlockCount(current);
        }
    }

    // -------------------------------------------------------------------------
    // Block mass cache
    // -------------------------------------------------------------------------

    /**
     * Positions of all blocks whose mass differs from the default (1.0f), mapped
     * to their configured mass. Populated at scan time and on block placements;
     * entries are removed when the block leaves the structure.
     * Used to look up the correct mass when a block is removed without knowing its old state.
     */
    Map<BlockPos, Float> createBoomingLift$getBlockMasses();

    // -------------------------------------------------------------------------
    // Mass lookup
    // -------------------------------------------------------------------------

    /**
     * Returns the Sable physics mass for the given block state.
     * Values match the project's physics_block_properties data files.
     * Extend as new core-block types are added.
     */
    static float blockMass(final BlockState state) {
        if (state.is(Blocks.TNT)) return 2.0f;
        // TODO: return Create kinetic block mass once Create is added as a dependency.
        return 1.0f;
    }
}
