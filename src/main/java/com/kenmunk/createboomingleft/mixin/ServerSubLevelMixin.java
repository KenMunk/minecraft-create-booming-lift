package com.kenmunk.createboomingleft.mixin;

import com.kenmunk.createboomingleft.CoreBlockHolder;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.HashMap;
import java.util.Map;

// remap = false because ServerSubLevel is a Sable class, not an obfuscated Minecraft class.
@Mixin(value = ServerSubLevel.class, remap = false)
public abstract class ServerSubLevelMixin implements CoreBlockHolder {

    @Unique
    private final Map<BlockPos, Vec3> createBoomingLift$tntBlocks = new HashMap<>();

    @Unique
    private final Map<BlockPos, Vec3> createBoomingLift$kineticBlocks = new HashMap<>();

    /** Positions of all blocks with a non-default mass (blockMass != 1.0f), mapped to their mass. */
    @Unique
    private final Map<BlockPos, Float> createBoomingLift$blockMasses = new HashMap<>();

    /**
     * Scan cursor. Java initialises int fields to 0, so every sublevel — including
     * those that existed before this mod was installed — starts as "not yet scanned".
     *
     *   0      not yet started
     *   1..N   in progress, next block-index is this value
     *  -1      complete
     */
    @Unique
    private int createBoomingLift$scanCursor;

    /** Running sum of core-block masses; only ever increases. */
    @Unique
    private float createBoomingLift$maxHealth;

    /** Reduced by collisions and non-player core-block removals; recovers over time. */
    @Unique
    private float createBoomingLift$currentHealth;

    /**
     * Historical peak of (tntBlocks.size + kineticBlocks.size).
     * Only increases; used to scale maxCurrentHealth as blocks are lost.
     */
    @Unique
    private int createBoomingLift$peakCoreBlockCount;

    @Override public Map<BlockPos, Vec3>  createBoomingLift$getTntBlocks()     { return createBoomingLift$tntBlocks; }
    @Override public Map<BlockPos, Vec3>  createBoomingLift$getKineticBlocks() { return createBoomingLift$kineticBlocks; }
    @Override public Map<BlockPos, Float> createBoomingLift$getBlockMasses()   { return createBoomingLift$blockMasses; }

    @Override public int  createBoomingLift$getScanCursor()          { return createBoomingLift$scanCursor; }
    @Override public void createBoomingLift$setScanCursor(int c)     { createBoomingLift$scanCursor = c; }

    @Override public float createBoomingLift$getMaxHealth()          { return createBoomingLift$maxHealth; }
    @Override public void  createBoomingLift$setMaxHealth(float h)   { createBoomingLift$maxHealth = h; }

    @Override public float createBoomingLift$getCurrentHealth()      { return createBoomingLift$currentHealth; }
    @Override public void  createBoomingLift$setCurrentHealth(float h){ createBoomingLift$currentHealth = h; }

    @Override public int  createBoomingLift$getPeakCoreBlockCount()         { return createBoomingLift$peakCoreBlockCount; }
    @Override public void createBoomingLift$setPeakCoreBlockCount(int count){ createBoomingLift$peakCoreBlockCount = count; }
}
