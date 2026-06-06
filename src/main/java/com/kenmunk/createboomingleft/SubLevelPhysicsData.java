package com.kenmunk.createboomingleft;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;

/**
 * Per-sublevel state and lifetime statistics for a Sable physics structure.
 * One instance lives in CrashDetectionTracker per active ServerSubLevel UUID.
 */
public class SubLevelPhysicsData {

    // --- velocity / streak tracking ---
    private boolean hasPreSubstepSpeed = false;
    private double  preSubstepSpeed    = 0.0;
    private int     fastSubstepStreak  = 0;

    // --- crash detection window ---
    private Window crashWindow = null;

    // --- chain priming deduplication ---
    private boolean chainPrimingActive = false;

    // --- lifetime statistics ---
    private int    crashCount = 0;
    private double peakSpeed  = 0.0;
    private double peakImpact = 0.0;

    private record Window(double startSpeed, long startTick, double peakDeltaV) {
        Window update(double dv) {
            return new Window(startSpeed, startTick, Math.max(peakDeltaV, dv));
        }
    }

    // -------------------------------------------------------------------------
    // Velocity / streak

    void recordPreSubstepSpeed(double speed) {
        preSubstepSpeed = speed;
        hasPreSubstepSpeed = true;
        if (speed > peakSpeed) peakSpeed = speed;
    }

    void clearSpeedTracking() {
        preSubstepSpeed = 0.0;
        hasPreSubstepSpeed = false;
        fastSubstepStreak = 0;
    }

    boolean hasPreSubstepSpeed() { return hasPreSubstepSpeed; }
    double  getPreSubstepSpeed() { return preSubstepSpeed; }

    void incrementFastStreak()  { fastSubstepStreak++; }
    void clearFastStreak()      { fastSubstepStreak = 0; }
    int  getFastSubstepStreak() { return fastSubstepStreak; }

    // -------------------------------------------------------------------------
    // Crash window

    void openWindow(double startSpeed, long tick, double dv) {
        crashWindow = new Window(startSpeed, tick, dv);
    }

    void updateWindow(double dv) {
        if (crashWindow != null) crashWindow = crashWindow.update(dv);
    }

    void clearWindow() { crashWindow = null; }

    boolean hasWindow() { return crashWindow != null; }
    boolean isWindowExpired(long now, int maxAgeTicks) {
        return crashWindow != null && now - crashWindow.startTick() > maxAgeTicks;
    }
    double getWindowPeakDeltaV() { return crashWindow == null ? 0.0 : crashWindow.peakDeltaV(); }
    double getWindowStartSpeed() { return crashWindow == null ? 0.0 : crashWindow.startSpeed(); }

    // -------------------------------------------------------------------------
    // Chain priming deduplication

    boolean isChainPrimingActive() { return chainPrimingActive; }
    void activateChainPriming()    { chainPrimingActive = true; }
    void deactivateChainPriming()  { chainPrimingActive = false; }

    // -------------------------------------------------------------------------
    // Lifetime statistics

    void recordCrash(double impactDeltaV) {
        crashCount++;
        if (impactDeltaV > peakImpact) peakImpact = impactDeltaV;
    }

    public int    getCrashCount() { return crashCount; }
    public double getPeakSpeed()  { return peakSpeed; }
    public double getPeakImpact() { return peakImpact; }

    // -------------------------------------------------------------------------
    // Block inventory scan

    private SubLevelScan scan = null;

    public boolean     hasScan()              { return scan != null; }
    public SubLevelScan getScan()             { return scan; }
    void setScan(final SubLevelScan scan)     { this.scan = scan; }

    // -------------------------------------------------------------------------
    // Explosive block position cache

    private final ArrayList<BlockPos> explosiveBlockCache = new ArrayList<>();

    public List<BlockPos> getExplosiveBlockCache() {
        return Collections.unmodifiableList(explosiveBlockCache);
    }

    ArrayList<BlockPos> explosiveBlockCacheMutable() { return explosiveBlockCache; }

    // -------------------------------------------------------------------------
    // Live core block count deltas (net change from initial scan)

    private final EnumMap<CoreBlockType, Integer> coreCountDeltas = new EnumMap<>(CoreBlockType.class);

    public int getEffectiveCoreBlockCount(final CoreBlockType type) {
        final int base = (scan != null) ? scan.getCount(type) : 0;
        return base + coreCountDeltas.getOrDefault(type, 0);
    }

    public int getCurrentCoreBlockTotal() {
        int total = 0;
        for (final CoreBlockType type : CoreBlockType.values()) {
            total += Math.max(0, getEffectiveCoreBlockCount(type));
        }
        return total;
    }

    // -------------------------------------------------------------------------
    // Structural damage (raw block-loss diagnostic)

    private int structuralDamage = 0;

    public int getStructuralDamage() { return structuralDamage; }

    // -------------------------------------------------------------------------
    // Health system

    // One point of damage per REGEN_INTERVAL ticks recovered passively.
    static  final int    MAX_HEALTH_MULTIPLIER = 20;
    private static final double FALL_GRACE     = 3.0;   // delta-V free before collision damage
    private static final double LOW_HEALTH_PCT = 0.05;  // 5 % of max health
    private static final int    REGEN_INTERVAL = 200;   // ticks per 1-point regen

    private int     peakCoreBlockCount   = 0;
    private double  currentHealth        = 0.0;
    private boolean healthInitialized    = false;
    private boolean detonationTriggered  = false;
    private long    lastRegenTick        = Long.MIN_VALUE;

    /** Max health = peak core block count × MAX_HEALTH_MULTIPLIER. */
    public double getMaxHealth() { return peakCoreBlockCount * MAX_HEALTH_MULTIPLIER; }

    /**
     * Current health ceiling = maxHealth × (liveTotal / peakTotal).
     * Shrinks proportionally as core blocks are lost; never exceeds maxHealth.
     */
    public double getCurrentMaxHealth() {
        if (peakCoreBlockCount == 0) return 0.0;
        return getMaxHealth() * ((double) getCurrentCoreBlockTotal() / peakCoreBlockCount);
    }

    public double  getCurrentHealth()      { return currentHealth; }
    public int     getPeakCoreBlockCount() { return peakCoreBlockCount; }

    /** True when health has been initialised and has reached zero. */
    public boolean isDestroyed() { return healthInitialized && currentHealth <= 0.0; }

    /** True when current health is below LOW_HEALTH_PCT of the maximum. */
    public boolean isLowHealth() {
        return healthInitialized && peakCoreBlockCount > 0
            && currentHealth < getMaxHealth() * LOW_HEALTH_PCT;
    }

    public boolean isHealthInitialized()    { return healthInitialized; }
    public boolean isDetonationTriggered()  { return detonationTriggered; }
    public void    markDetonationTriggered() { detonationTriggered = true; }

    /**
     * Updates the peak core block count. Once the scan is complete and the peak
     * is above zero, health is initialised to the full max. After that, current
     * health is always capped at getCurrentMaxHealth() (which shrinks as blocks
     * are lost).
     */
    void updatePeakCoreBlockCount() {
        final int total = getCurrentCoreBlockTotal();
        if (total > peakCoreBlockCount) peakCoreBlockCount = total;

        if (!healthInitialized && peakCoreBlockCount > 0
                && scan != null && scan.isComplete()) {
            healthInitialized = true;
            currentHealth = getMaxHealth();
        }
        if (healthInitialized) {
            currentHealth = Math.min(currentHealth, getCurrentMaxHealth());
        }
    }

    /** Reduces current health by {@code amount}, floored at zero. No-op before initialisation. */
    void applyDamage(final double amount) {
        if (!healthInitialized) return;
        currentHealth = Math.max(0.0, currentHealth - amount);
    }

    /**
     * Applies fall-damage-equivalent health loss.
     * Formula: max(0, deltaV − FALL_GRACE). Mirrors player fall damage with a 3-unit grace period.
     */
    void applyCollisionDamage(final double deltaV) {
        final double damage = Math.max(0.0, deltaV - FALL_GRACE);
        if (damage > 0.0) applyDamage(damage);
    }

    /**
     * Subtracts the block's hardness as its "mass value" from current health.
     * Minimum contribution is 1.0 per block. Call while the block is still in
     * the world (before setBlock(AIR)) so getDestroySpeed returns the real value.
     */
    void applyBlockBreakDamage(final BlockState state, final BlockPos pos, final ServerLevel level) {
        final float hardness = state.getDestroySpeed(level, pos);
        final double mass = (hardness < 0f) ? 1.0 : Math.max(1.0, hardness);
        applyDamage(mass);
    }

    /**
     * Regenerates 1 point of health per REGEN_INTERVAL ticks, up to getCurrentMaxHealth().
     * No-op before health is initialised or when health is full.
     */
    void tickRegen(final long gameTime) {
        if (!healthInitialized) return;
        if (lastRegenTick == Long.MIN_VALUE) { lastRegenTick = gameTime; return; }
        final long elapsed = gameTime - lastRegenTick;
        if (elapsed < REGEN_INTERVAL) return;
        final long points = elapsed / REGEN_INTERVAL;
        lastRegenTick += points * REGEN_INTERVAL;
        currentHealth = Math.min(getCurrentMaxHealth(), currentHealth + points);
    }

    // -------------------------------------------------------------------------
    // Live cache updates (called from CrashDetectionTracker event handlers)

    void onBlockAdded(final BlockState state, final BlockPos pos, final ServerLevel level) {
        for (final CoreBlockType type : CoreBlockType.values()) {
            if (type.matches(state, pos, level)) {
                coreCountDeltas.merge(type, 1, Integer::sum);
                if (type == CoreBlockType.EXPLOSIVE) explosiveBlockCache.add(pos.immutable());
            }
        }
        updatePeakCoreBlockCount();
    }

    /**
     * Call BEFORE the block is set to air.
     * Removes the block from caches, applies mass-based health damage for
     * health-affecting causes, and updates the peak / health ceiling.
     */
    void onBlockRemoved(final BlockPos pos, final BlockState state,
                        final ServerLevel level, final BlockBreakCause cause) {
        for (final CoreBlockType type : CoreBlockType.values()) {
            if (type.matches(state, pos, level)) {
                coreCountDeltas.merge(type, -1, Integer::sum);
                if (type == CoreBlockType.EXPLOSIVE) explosiveBlockCache.remove(pos);
            }
        }
        if (cause.affectsHealth()) {
            structuralDamage++;
            applyBlockBreakDamage(state, pos, level);
        }
        updatePeakCoreBlockCount();
    }
}
