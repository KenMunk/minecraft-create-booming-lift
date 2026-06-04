package com.kenmunk.createboomingleft;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.mixinterface.plot.SubLevelContainerHolder;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.projectile.FireworkRocketEntity;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.FireworkExplosion;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Vector3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class CrashDetectionTracker {

    // Velocities are in RigidBodyHandle units (m/s, BepuPhysics2 SI).
    static final double STARTING_SPEED        = 4.0;
    static final double DELTA_V               = 4.0;
    static final int    WINDOW_TICKS          = 2;
    static final double MIN_IMPULSE_DV        = 1.5;
    static final int    SUSTAINED_SUBSTEPS    = 5;
    // Starting speed above which a qualifying collision fires one instant combined
    // explosion instead of the staggered batch-prime sequence.
    static final double INSTANT_DETONATE_SPEED = 8.0;

    private static final int PRIMES_PER_TICK      = 4;
    private static final int SMOKE_DURATION_TICKS = 1200; // 60 seconds
    private static final int SMOKE_EMIT_INTERVAL  = 20;   // once per second

    private final Map<UUID, Double>  preSubstepSpeed    = new HashMap<>();
    private final Map<UUID, Integer> fastSubstepStreak  = new HashMap<>();
    private final Map<UUID, Window>  windows            = new HashMap<>();
    private final Set<UUID>          chainPrimingActive = new HashSet<>();
    private final Map<ServerLevel, Deque<BatchEntry>>   batchQueue    = new IdentityHashMap<>();
    private final Map<UUID, Integer>                    batchCount    = new HashMap<>();
    private final Map<ServerLevel, Map<BlockPos, Long>> smokingBlocks = new IdentityHashMap<>();

    private record Window(double startSpeed, long startTick, double peakDeltaV) {
        Window update(double dv) {
            return new Window(startSpeed, startTick, Math.max(peakDeltaV, dv));
        }
    }

    private record BatchEntry(UUID sublevelId, BlockPos pos) {}

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
            if (!Double.isFinite(speed)) {
                fastSubstepStreak.remove(id);
                preSubstepSpeed.remove(id);
                continue;
            }
            preSubstepSpeed.put(id, speed);
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
                final BoundingBox3ic bounds = sublevel.getPlot().getBoundingBox();
                final int tntCount = countTnt(level, bounds);
                if (w.startSpeed() >= INSTANT_DETONATE_SPEED) {
                    detonateInstantCombined(level, bounds);
                } else {
                    scheduleAllTntPriming(level, sublevel);
                }
                spawnCriticalFireworks(level, bounds, tntCount);
                scheduleSmokeForSurvivors(level, bounds);
                windows.remove(id);
            }
        }
    }

    @SubscribeEvent
    public void onEntityJoinLevel(final EntityJoinLevelEvent event) {
        if (!(event.getEntity() instanceof PrimedTnt)) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        final BlockPos pos = BlockPos.containing(event.getEntity().position());
        final SubLevel sublevel = Sable.HELPER.getContaining(level, pos);
        if (!(sublevel instanceof ServerSubLevel serverSubLevel)) return;

        final UUID id = serverSubLevel.getUniqueId();
        if (!chainPrimingActive.add(id)) return;
        try {
            chainPrimeAllTnt(level, serverSubLevel);
        } finally {
            chainPrimingActive.remove(id);
        }
    }

    @SubscribeEvent
    public void onLevelTick(final LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        final long now = level.getGameTime();

        // Batch-prime TNT blocks from crash-detonation queue.
        final Deque<BatchEntry> queue = batchQueue.get(level);
        if (queue != null) {
            for (int i = 0; i < PRIMES_PER_TICK && !queue.isEmpty(); i++) {
                final BatchEntry entry = queue.poll();
                if (level.getBlockState(entry.pos()).is(Blocks.TNT)) {
                    Blocks.TNT.onCaughtFire(Blocks.TNT.defaultBlockState(), level, entry.pos(), null, null);
                    level.setBlock(entry.pos(), Blocks.AIR.defaultBlockState(), 11);
                }
                final int remaining = batchCount.merge(entry.sublevelId(), -1, Integer::sum);
                if (remaining <= 0) {
                    batchCount.remove(entry.sublevelId());
                    chainPrimingActive.remove(entry.sublevelId());
                }
            }
            if (queue.isEmpty()) batchQueue.remove(level);
        }

        // Emit smoke particles for surviving structure blocks once per second.
        if (now % SMOKE_EMIT_INTERVAL == 0) {
            final Map<BlockPos, Long> smokeMap = smokingBlocks.get(level);
            if (smokeMap != null) {
                smokeMap.entrySet().removeIf(e -> {
                    if (now >= e.getValue()) return true;
                    if (!level.getBlockState(e.getKey()).isAir()) {
                        level.sendParticles(ParticleTypes.LARGE_SMOKE,
                            e.getKey().getX() + 0.5, e.getKey().getY() + 1.0, e.getKey().getZ() + 0.5,
                            1, 0.3, 0.0, 0.3, 0.02);
                    }
                    return false;
                });
                if (smokeMap.isEmpty()) smokingBlocks.remove(level);
            }
        }
    }

    @SubscribeEvent
    public void onExplosionDetonate(final ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;

        final List<BlockPos> affectedBlocks = event.getAffectedBlocks();
        final Vec3 center = event.getExplosion().center();

        double maxDistSq = 0.0;
        for (final BlockPos pos : affectedBlocks) {
            final double dx = pos.getX() + 0.5 - center.x;
            final double dy = pos.getY() + 0.5 - center.y;
            final double dz = pos.getZ() + 0.5 - center.z;
            maxDistSq = Math.max(maxDistSq, dx * dx + dy * dy + dz * dz);
        }
        if (maxDistSq == 0.0) return;

        final SubLevelContainer container = ((SubLevelContainerHolder) level).sable$getPlotContainer();
        if (!(container instanceof ServerSubLevelContainer c)) return;

        final Map<ServerSubLevel, List<BlockPos>> tntBySublevel = new HashMap<>();
        for (final ServerSubLevel sublevel : c.getAllSubLevels()) {
            final BoundingBox3ic bounds = sublevel.getPlot().getBoundingBox();
            if (!aabbIntersectsSphere(bounds, center, maxDistSq)) continue;

            for (final BlockPos pos : BlockPos.betweenClosed(
                    bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
                if (!level.getBlockState(pos).is(Blocks.TNT)) continue;
                final double dx = pos.getX() + 0.5 - center.x;
                final double dy = pos.getY() + 0.5 - center.y;
                final double dz = pos.getZ() + 0.5 - center.z;
                if (dx * dx + dy * dy + dz * dz <= maxDistSq) {
                    tntBySublevel.computeIfAbsent(sublevel, k -> new ArrayList<>())
                                 .add(pos.immutable());
                }
            }
        }

        if (tntBySublevel.isEmpty()) return;

        final List<BlockPos> allTnt = new ArrayList<>();
        for (final List<BlockPos> positions : tntBySublevel.values()) {
            allTnt.addAll(positions);
        }
        affectedBlocks.removeAll(allTnt);

        for (final Map.Entry<ServerSubLevel, List<BlockPos>> entry : tntBySublevel.entrySet()) {
            final UUID id = entry.getKey().getUniqueId();
            for (final BlockPos pos : entry.getValue()) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 11);
            }
            chainPrimingActive.add(id);
            try {
                for (final BlockPos pos : entry.getValue()) {
                    Blocks.TNT.onCaughtFire(Blocks.TNT.defaultBlockState(), level, pos, null, null);
                }
            } finally {
                chainPrimingActive.remove(id);
            }
        }
    }

    // Instantly removes all TNT and fires a single combined explosion from the
    // sublevel centre whose radius equals the sum of all individual TNT radii.
    private static void detonateInstantCombined(final ServerLevel level, final BoundingBox3ic bounds) {
        int tntCount = 0;
        for (final BlockPos pos : BlockPos.betweenClosed(
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
            if (level.getBlockState(pos).is(Blocks.TNT)) {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 11);
                tntCount++;
            }
        }
        if (tntCount == 0) return;

        final double cx = (bounds.minX() + bounds.maxX() + 1.0) / 2.0;
        final double cy = (bounds.minY() + bounds.maxY() + 1.0) / 2.0;
        final double cz = (bounds.minZ() + bounds.maxZ() + 1.0) / 2.0;
        // Each TNT block contributes one vanilla-power (4.0) radius to the combined blast.
        final float radius = tntCount * 8.0f;
        level.explode(null, cx, cy, cz, radius, Level.ExplosionInteraction.TNT);
    }

    private void scheduleAllTntPriming(final ServerLevel level, final ServerSubLevel sublevel) {
        final BoundingBox3ic bounds = sublevel.getPlot().getBoundingBox();
        final List<BlockPos> tntPositions = new ArrayList<>();
        for (final BlockPos pos : BlockPos.betweenClosed(
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
            if (level.getBlockState(pos).is(Blocks.TNT)) {
                tntPositions.add(pos.immutable());
            }
        }
        if (tntPositions.isEmpty()) return;

        final UUID id = sublevel.getUniqueId();
        chainPrimingActive.add(id);
        batchCount.merge(id, tntPositions.size(), Integer::sum);
        final Deque<BatchEntry> queue = batchQueue.computeIfAbsent(level, k -> new ArrayDeque<>());
        for (final BlockPos pos : tntPositions) {
            queue.add(new BatchEntry(id, pos));
        }
    }

    private static void spawnCriticalFireworks(final ServerLevel level, final BoundingBox3ic bounds, final int tntCount) {
        final double cx = (bounds.minX() + bounds.maxX() + 1.0) / 2.0;
        final double cy = (bounds.minY() + bounds.maxY() + 1.0) / 2.0;
        final double cz = (bounds.minZ() + bounds.maxZ() + 1.0) / 2.0;
        final double spawnRadius = Math.max(10.0, tntCount);

        final IntArrayList colors = new IntArrayList(new int[]{
            DyeColor.BLACK.getFireworkColor(),
            DyeColor.GRAY.getFireworkColor()
        });
        final FireworkExplosion explosion = new FireworkExplosion(
            FireworkExplosion.Shape.BURST, colors, new IntArrayList(), false, true);
        final ItemStack rocket = new ItemStack(Items.FIREWORK_ROCKET);
        rocket.set(DataComponents.FIREWORKS, new Fireworks(0, List.of(explosion)));

        for (int i = 0; i < 3; i++) {
            final double angle = level.getRandom().nextDouble() * Math.PI * 2.0;
            final double dist  = level.getRandom().nextDouble() * spawnRadius;
            final double ox    = Math.cos(angle) * dist;
            final double oy    = level.getRandom().nextDouble() * 3.0;
            final double oz    = Math.sin(angle) * dist;
            level.addFreshEntity(new FireworkRocketEntity(level, cx + ox, cy + oy, cz + oz, rocket));
        }
    }

    private static int countTnt(final ServerLevel level, final BoundingBox3ic bounds) {
        int count = 0;
        for (final BlockPos pos : BlockPos.betweenClosed(
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
            if (level.getBlockState(pos).is(Blocks.TNT)) count++;
        }
        return count;
    }

    private void scheduleSmokeForSurvivors(final ServerLevel level, final BoundingBox3ic bounds) {
        final long expiry = level.getGameTime() + SMOKE_DURATION_TICKS;
        final Map<BlockPos, Long> smokeMap = smokingBlocks.computeIfAbsent(level, k -> new HashMap<>());
        for (final BlockPos pos : BlockPos.betweenClosed(
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
            final BlockState state = level.getBlockState(pos);
            if (!state.isAir() && !state.is(Blocks.TNT)) {
                smokeMap.put(pos.immutable(), expiry);
            }
        }
    }

    private static void chainPrimeAllTnt(final ServerLevel level, final ServerSubLevel sublevel) {
        final LevelPlot plot = sublevel.getPlot();
        final BoundingBox3ic bounds = plot.getBoundingBox();
        for (final BlockPos pos : BlockPos.betweenClosed(
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
            if (level.getBlockState(pos).is(Blocks.TNT)) {
                Blocks.TNT.onCaughtFire(Blocks.TNT.defaultBlockState(), level, pos, null, null);
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 11);
            }
        }
    }

    private static List<ServerSubLevel> getSubLevels(final SubLevelPhysicsSystem system) {
        final SubLevelContainer container =
            ((SubLevelContainerHolder) system.getLevel()).sable$getPlotContainer();
        return container instanceof ServerSubLevelContainer c ? c.getAllSubLevels() : List.of();
    }

    private static boolean aabbIntersectsSphere(
            final BoundingBox3ic bounds, final Vec3 center, final double radiusSq) {
        final double cx = Math.max(bounds.minX(), Math.min(center.x, bounds.maxX() + 1.0));
        final double cy = Math.max(bounds.minY(), Math.min(center.y, bounds.maxY() + 1.0));
        final double cz = Math.max(bounds.minZ(), Math.min(center.z, bounds.maxZ() + 1.0));
        final double dx = cx - center.x, dy = cy - center.y, dz = cz - center.z;
        return dx * dx + dy * dy + dz * dz <= radiusSq;
    }
}
