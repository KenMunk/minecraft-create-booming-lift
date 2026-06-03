package com.kenmunk.createboomingleft;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinterface.plot.SubLevelContainerHolder;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.joml.Vector3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CrashDetectionTracker {

    // Velocities are in RigidBodyHandle units (m/s, BepuPhysics2 SI).
    // Sable's built-in fragile/TNT thresholds are 4.0 and 5.0 for reference.
    // Calibrate these values in-game; they are NOT directly in blocks/tick.
    static final double STARTING_SPEED  = 10.0;
    static final double DELTA_V         = 8.0;
    static final int    WINDOW_TICKS    = 2;
    // Minimum delta-v in a single substep to count as a real collision impulse.
    // Filters out gradual deceleration from drag, friction, and gravity so that
    // glancing grazes cannot accumulate to trigger the explosion.
    static final double MIN_IMPULSE_DV  = 1.5;
    // How many consecutive substeps above STARTING_SPEED are required before crash
    // detection can trigger. Filters out the 1-2 substep physics spikes that occur
    // when a heavy/negative-mass block is placed on a structure at rest.
    static final int    SUSTAINED_SUBSTEPS = 5;

    private final Map<UUID, Double>  preSubstepSpeed   = new HashMap<>();
    private final Map<UUID, Integer> fastSubstepStreak = new HashMap<>();
    private final Map<UUID, Window>  windows            = new HashMap<>();

    // peakDeltaV tracks the LARGEST single-substep impulse seen in the window,
    // not a running sum. This prevents multiple small bumps from adding up.
    private record Window(double startSpeed, long startTick, double peakDeltaV) {
        Window update(double dv) {
            return new Window(startSpeed, startTick, Math.max(peakDeltaV, dv));
        }
    }

    public void register() {
        NeoForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onPrePhysicsTick(final ForgeSablePrePhysicsTickEvent event) {
        final SubLevelPhysicsSystem system = event.getPhysicsSystem();
        for (final ServerSubLevel sublevel : getSubLevels(system)) {
            final RigidBodyHandle handle = system.getPhysicsHandle(sublevel);
            if (handle == null) continue;
            final UUID id = sublevel.getUniqueId();
            final double speed = handle.getLinearVelocity(new Vector3d()).length();
            // Non-finite velocity means the physics body is in an invalid/unstable
            // state (e.g. net-negative mass). Never arm the crash window for it.
            if (!Double.isFinite(speed)) {
                fastSubstepStreak.remove(id);
                preSubstepSpeed.remove(id);
                continue;
            }
            preSubstepSpeed.put(id, speed);
            // Maintain a streak of how many consecutive substeps this sublevel has
            // been above STARTING_SPEED. Resets to 0 the moment it drops below.
            if (speed > STARTING_SPEED) {
                fastSubstepStreak.merge(id, 1, Integer::sum);
            } else {
                fastSubstepStreak.remove(id);
            }
        }
    }

    @SubscribeEvent
    public void onPostPhysicsTick(final ForgeSablePostPhysicsTickEvent event) {
        final SubLevelPhysicsSystem system = event.getPhysicsSystem();
        final ServerLevel level = system.getLevel();
        final long now = level.getGameTime();

        for (final ServerSubLevel sublevel : getSubLevels(system)) {
            final UUID id = sublevel.getUniqueId();
            final Double pre = preSubstepSpeed.get(id);
            if (pre == null) continue;

            final RigidBodyHandle handle = system.getPhysicsHandle(sublevel);
            if (handle == null) continue;

            final double post = handle.getLinearVelocity(new Vector3d()).length();
            final double dv   = Math.max(0.0, pre - post);

            Window w = windows.get(id);

            if (w != null && now - w.startTick() > WINDOW_TICKS) {
                windows.remove(id);
                w = null;
            }

            // Only track substeps where the structure was fast AND the impulse is large
            // enough to be a real collision (not drag, friction, or gravity noise).
            // Also require the structure to have been sustainably fast for several
            // consecutive substeps — this rejects the 1-2 substep velocity spikes
            // that occur when a negative-mass block is placed on a resting structure.
            if (pre <= STARTING_SPEED || dv < MIN_IMPULSE_DV) continue;
            if (fastSubstepStreak.getOrDefault(id, 0) < SUSTAINED_SUBSTEPS) continue;

            if (w == null) {
                w = new Window(pre, now, dv);
                windows.put(id, w);
            } else {
                w = w.update(dv);
                windows.put(id, w);
            }

            if (w.peakDeltaV() >= DELTA_V) {
                detonateAllTnt(level, sublevel);
                windows.remove(id);
            }
        }
    }

    private static List<ServerSubLevel> getSubLevels(final SubLevelPhysicsSystem system) {
        final SubLevelContainer container =
            ((SubLevelContainerHolder) system.getLevel()).sable$getPlotContainer();
        return container instanceof ServerSubLevelContainer c ? c.getAllSubLevels() : List.of();
    }

    private static void detonateAllTnt(final ServerLevel level, final ServerSubLevel sublevel) {
        final LevelPlot plot = sublevel.getPlot();
        final BoundingBox3ic bounds = plot.getBoundingBox();

        // Collect TNT positions first so removal and detonation happen in one pass
        final List<BlockPos> tntPositions = new ArrayList<>();
        for (final BlockPos pos : BlockPos.betweenClosed(
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
            if (level.getBlockState(pos).is(Blocks.TNT)) {
                tntPositions.add(pos.immutable());
            }
        }

        // Radius scales with the number of TNT blocks in the structure:
        //   radius = BASE_RADIUS * (1 + 0.1 * count)
        // e.g. 1 TNT → 8.8, 10 TNT → 16.0, 20 TNT → 24.0
        final float radius = 8.0f * (1.0f + 0.1f * tntPositions.size());

        // Remove all TNT blocks at once, then fire all explosions in the same tick.
        for (final BlockPos pos : tntPositions) {
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), 11);
        }
        for (final BlockPos pos : tntPositions) {
            level.explode(null,
                pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                radius, Level.ExplosionInteraction.TNT);
        }
    }
}
