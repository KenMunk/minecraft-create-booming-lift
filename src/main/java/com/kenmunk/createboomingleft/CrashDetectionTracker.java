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
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Vector3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class CrashDetectionTracker {

    // Velocities are in RigidBodyHandle units (m/s, BepuPhysics2 SI).
    static final double STARTING_SPEED         = 4.0;
    static final double DELTA_V                = 4.0;
    static final int    WINDOW_TICKS           = 2;
    static final double MIN_IMPULSE_DV         = 1.5;
    static final int    SUSTAINED_SUBSTEPS     = 5;
    static final double INSTANT_DETONATE_SPEED = 12.0;

    private static final int   SMOKE_DURATION_TICKS  = 1200; // 60 s
    private static final int   SMOKE_EMIT_INTERVAL   = 20;   // once per second
    // Probability per tick of igniting one random TNT block when health < 5 %.
    private static final float  LOW_HEALTH_TNT_CHANCE = 0.005f;
    // Health-based smoke thresholds (fraction of max health).
    private static final double SMOKE_HEALTH_START   = 0.75; // light smoke begins here
    private static final double BLINDNESS_HEALTH     = 0.10; // blindness applied below here
    private static final double HEAVY_SMOKE_HEALTH   = 0.01; // maximum smoke density here
    private static final double FIRE_HEALTH          = 0.02; // fire ignites below here
    private static final int    FIRE_SPREAD_INTERVAL = 200;  // ticks between fire-spread events
    private static final int    FIRE_SPREAD_COUNT    = 4;    // fire blocks placed per event

    private final Map<UUID, SubLevelPhysicsData>        subLevelData  = new HashMap<>();
    private final Map<ServerLevel, Map<BlockPos, Long>> smokingBlocks = new IdentityHashMap<>();

    private SubLevelPhysicsData data(UUID id) {
        return subLevelData.computeIfAbsent(id, k -> new SubLevelPhysicsData());
    }

    public void register() {
        NeoForge.EVENT_BUS.register(this);
    }

    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onPrePhysicsTick(final ForgeSablePrePhysicsTickEvent event) {
        final SubLevelPhysicsSystem system = event.getPhysicsSystem();
        for (final ServerSubLevel sublevel : getSubLevels(system)) {
            final UUID id = sublevel.getUniqueId();
            final SubLevelPhysicsData d = data(id);

            if (!d.hasScan()) {
                final BoundingBox3ic bounds = sublevel.getPlot().getBoundingBox();
                d.setScan(new SubLevelScan(
                    bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ(),
                    d.explosiveBlockCacheMutable()
                ));
            }

            final RigidBodyHandle handle = system.getPhysicsHandle(sublevel);
            if (handle == null) continue;
            final double speed = handle.getLinearVelocity(new Vector3d()).length();
            if (!Double.isFinite(speed)) {
                d.clearSpeedTracking();
                continue;
            }
            d.recordPreSubstepSpeed(speed);
            if (speed > STARTING_SPEED) d.incrementFastStreak();
            else                        d.clearFastStreak();
        }
    }

    @SubscribeEvent
    public void onPostPhysicsTick(final ForgeSablePostPhysicsTickEvent event) {
        final SubLevelPhysicsSystem system = event.getPhysicsSystem();
        final ServerLevel level = system.getLevel();
        final long now = level.getGameTime();

        for (final ServerSubLevel sublevel : getSubLevels(system)) {
            final UUID id = sublevel.getUniqueId();
            final SubLevelPhysicsData d = subLevelData.get(id);
            if (d == null || !d.hasPreSubstepSpeed()) continue;

            final RigidBodyHandle handle = system.getPhysicsHandle(sublevel);
            if (handle == null) continue;

            final double pre  = d.getPreSubstepSpeed();
            final double post = handle.getLinearVelocity(new Vector3d()).length();
            final double dv   = Math.max(0.0, pre - post);

            if (d.isWindowExpired(now, WINDOW_TICKS)) d.clearWindow();

            if (pre <= STARTING_SPEED || dv < MIN_IMPULSE_DV) continue;
            if (d.getFastSubstepStreak() < SUSTAINED_SUBSTEPS) continue;

            if (!d.hasWindow()) d.openWindow(pre, now, dv);
            else                d.updateWindow(dv);

            if (d.getWindowPeakDeltaV() >= DELTA_V) {
                final BoundingBox3ic bounds = sublevel.getPlot().getBoundingBox();

                // Apply fall-damage-equivalent health loss from the collision.
                d.applyCollisionDamage(d.getWindowPeakDeltaV());

                if (d.getWindowStartSpeed() >= INSTANT_DETONATE_SPEED) {
                    // High-speed crash: fire a single massive explosion whose radius
                    // is determined by the current core block count (8 × coreCount).
                    final int coreCount = d.getCurrentCoreBlockTotal();
                    final int tntCount  = countTnt(level, bounds); // capture before removal
                    detonateInstantCombined(level, bounds, d, coreCount);
                    spawnCriticalFireworks(level, bounds, tntCount);
                }

                d.recordCrash(d.getWindowPeakDeltaV());
                scheduleSmokeForSurvivors(level, bounds);
                d.clearWindow();
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
        final SubLevelPhysicsData d = data(id);
        if (d.isChainPrimingActive()) return;
        d.activateChainPriming();
        try {
            chainPrimeAllTnt(level, serverSubLevel, d);
        } finally {
            d.deactivateChainPriming();
        }
    }

    @SubscribeEvent
    public void onLevelTick(final LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        final long now = level.getGameTime();

        final SubLevelContainer container = ((SubLevelContainerHolder) level).sable$getPlotContainer();
        if (container instanceof ServerSubLevelContainer sc) {
            for (final ServerSubLevel sublevel : sc.getAllSubLevels()) {
                final SubLevelPhysicsData d = subLevelData.get(sublevel.getUniqueId());
                if (d == null) continue;

                // Advance the incremental block scan (10 blocks / tick).
                if (d.hasScan() && !d.getScan().isComplete()) {
                    d.getScan().advance(level, 10);
                    d.updatePeakCoreBlockCount();
                }

                // Passive health regeneration (1 point per 200 ticks).
                d.tickRegen(now);

                // Health-scaled smoke particles and blindness effect.
                tickHealthSmoke(level, sublevel.getPlot().getBoundingBox(), d, now);

                if (d.isDetonationTriggered()) continue;

                if (d.isDestroyed()) {
                    // Health reached zero — trigger a final massive explosion.
                    d.markDetonationTriggered();
                    final BoundingBox3ic bounds = sublevel.getPlot().getBoundingBox();
                    final int tntCount  = countTnt(level, bounds);
                    final int coreCount = d.getCurrentCoreBlockTotal();
                    detonateInstantCombined(level, bounds, d, coreCount);
                    spawnCriticalFireworks(level, bounds, tntCount);
                    scheduleSmokeForSurvivors(level, bounds);

                } else if (d.isLowHealth() && !d.getExplosiveBlockCache().isEmpty()) {
                    // Below 5 % health: 0.5 % chance per tick to ignite one random TNT block.
                    if (level.getRandom().nextFloat() < LOW_HEALTH_TNT_CHANCE) {
                        final List<BlockPos> cache = d.getExplosiveBlockCache();
                        final BlockPos tntPos = cache.get(level.getRandom().nextInt(cache.size()));
                        final BlockState tntState = level.getBlockState(tntPos);
                        if (tntState.is(Blocks.TNT)) {
                            Blocks.TNT.onCaughtFire(Blocks.TNT.defaultBlockState(), level, tntPos, null, null);
                            d.onBlockRemoved(tntPos, tntState, level, BlockBreakCause.EXPLOSION);
                            level.setBlock(tntPos, Blocks.AIR.defaultBlockState(), 11);
                        }
                    }
                }
            }
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
    public void onBlockPlace(final BlockEvent.EntityPlaceEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        final BlockPos pos = event.getPos();
        final SubLevel sublevel = Sable.HELPER.getContaining(level, pos);
        if (!(sublevel instanceof ServerSubLevel serverSubLevel)) return;

        final SubLevelPhysicsData d = subLevelData.get(serverSubLevel.getUniqueId());
        if (d == null) return;
        d.onBlockAdded(event.getPlacedBlock(), pos, level);
    }

    @SubscribeEvent
    public void onBlockBreak(final BlockEvent.BreakEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        final BlockPos pos = event.getPos();
        final SubLevel sublevel = Sable.HELPER.getContaining(level, pos);
        if (!(sublevel instanceof ServerSubLevel serverSubLevel)) return;

        final SubLevelPhysicsData d = subLevelData.get(serverSubLevel.getUniqueId());
        if (d == null) return;
        final BlockBreakCause cause = event.getPlayer() != null
            ? BlockBreakCause.PLAYER_MINING
            : BlockBreakCause.UNKNOWN;
        d.onBlockRemoved(pos, event.getState(), level, cause);
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
        for (final List<BlockPos> positions : tntBySublevel.values()) allTnt.addAll(positions);
        affectedBlocks.removeAll(allTnt);

        for (final Map.Entry<ServerSubLevel, List<BlockPos>> entry : tntBySublevel.entrySet()) {
            final UUID id = entry.getKey().getUniqueId();
            final SubLevelPhysicsData d = data(id);
            for (final BlockPos pos : entry.getValue()) {
                d.onBlockRemoved(pos, level.getBlockState(pos), level, BlockBreakCause.EXPLOSION);
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 11);
            }
            d.activateChainPriming();
            try {
                for (final BlockPos pos : entry.getValue()) {
                    Blocks.TNT.onCaughtFire(Blocks.TNT.defaultBlockState(), level, pos, null, null);
                }
            } finally {
                d.deactivateChainPriming();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers

    /**
     * Removes all TNT from the sublevel's plot area, notifies the data object,
     * then fires one combined explosion whose radius is {@code coreCount × 8}.
     * This is both the high-speed crash explosion and the health-death explosion.
     */
    private static void detonateInstantCombined(final ServerLevel level, final BoundingBox3ic bounds,
                                                 final SubLevelPhysicsData d, final int coreCount) {
        if (coreCount <= 0) return;
        for (final BlockPos pos : BlockPos.betweenClosed(
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
            final BlockState tntState = level.getBlockState(pos);
            if (tntState.is(Blocks.TNT)) {
                d.onBlockRemoved(pos.immutable(), tntState, level, BlockBreakCause.PHYSICS_CRASH);
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), 11);
            }
        }
        final double cx = (bounds.minX() + bounds.maxX() + 1.0) / 2.0;
        final double cy = (bounds.minY() + bounds.maxY() + 1.0) / 2.0;
        final double cz = (bounds.minZ() + bounds.maxZ() + 1.0) / 2.0;
        final float radius = coreCount * 8.0f;
        level.explode(null, cx, cy, cz, radius, Level.ExplosionInteraction.TNT);
    }

    /**
     * Emits health-scaled smoke particles from within the sublevel's bounds and applies
     * blindness to nearby players when health is critically low.
     *
     * Smoke begins at 75 % health (light, 1 particle/s) and scales quadratically to
     * extremely dense at 1 % health (~9 LARGE_SMOKE particles/tick). Blindness is
     * applied within 24 blocks of the sublevel centre at or below 10 % health.
     */
    private static void tickHealthSmoke(final ServerLevel level, final BoundingBox3ic bounds,
                                         final SubLevelPhysicsData d, final long now) {
        if (!d.isHealthInitialized()) return;
        final double maxHealth = d.getMaxHealth();
        if (maxHealth <= 0.0) return;

        final double healthPct = d.getCurrentHealth() / maxHealth;
        if (healthPct >= SMOKE_HEALTH_START) return;

        // Normalised intensity: 0.0 at SMOKE_HEALTH_START, 1.0 at HEAVY_SMOKE_HEALTH.
        final double intensity = Math.min(1.0,
            (SMOKE_HEALTH_START - healthPct) / (SMOKE_HEALTH_START - HEAVY_SMOKE_HEALTH));

        // Emission frequency scales from once every 20 ticks down to every tick.
        final int emitInterval = Math.max(1, (int)(20.0 * (1.0 - intensity)));
        if (now % emitInterval != 0) return;

        // Particle count per emission: 1 (light) → ~9 (extremely heavy), quadratic.
        final int count = (int)(intensity * intensity * 8.0) + 1;
        // Particle type: fine smoke below 50 % intensity, large smoke above.
        final var particle = intensity >= 0.5 ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE;

        final double sizeX = bounds.maxX() - bounds.minX() + 1.0;
        final double sizeY = bounds.maxY() - bounds.minY() + 1.0;
        final double sizeZ = bounds.maxZ() - bounds.minZ() + 1.0;

        for (int i = 0; i < count; i++) {
            final double px = bounds.minX() + level.getRandom().nextDouble() * sizeX;
            final double py = bounds.minY() + level.getRandom().nextDouble() * sizeY;
            final double pz = bounds.minZ() + level.getRandom().nextDouble() * sizeZ;
            level.sendParticles(particle, px, py, pz, 1, 0.3, 0.1, 0.3, 0.02);
        }

        // Apply blindness to players within 24 blocks of the sublevel centre.
        if (healthPct <= BLINDNESS_HEALTH) {
            final double cx = (bounds.minX() + bounds.maxX() + 1.0) / 2.0;
            final double cy = (bounds.minY() + bounds.maxY() + 1.0) / 2.0;
            final double cz = (bounds.minZ() + bounds.maxZ() + 1.0) / 2.0;
            final AABB searchBox = new AABB(cx - 24, cy - 24, cz - 24, cx + 24, cy + 24, cz + 24);
            for (final Player player : level.getEntitiesOfClass(Player.class, searchBox)) {
                player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0, false, false));
            }
        }

        // Spread fire onto structure blocks at 2 % health: 4 blocks per 200 ticks.
        if (healthPct <= FIRE_HEALTH && now % FIRE_SPREAD_INTERVAL == 0) {
            spreadFireInSublevel(level, bounds);
        }
    }

    /**
     * Places up to FIRE_SPREAD_COUNT fire blocks on randomly sampled solid positions within
     * the sublevel's plot bounds. Tries up to 15 candidates per target block; skips air
     * bases and positions that are already occupied above.
     */
    private static void spreadFireInSublevel(final ServerLevel level, final BoundingBox3ic bounds) {
        final int sizeX = bounds.maxX() - bounds.minX() + 1;
        final int sizeY = bounds.maxY() - bounds.minY() + 1;
        final int sizeZ = bounds.maxZ() - bounds.minZ() + 1;

        int placed   = 0;
        int attempts = FIRE_SPREAD_COUNT * 15;

        while (placed < FIRE_SPREAD_COUNT && attempts-- > 0) {
            final BlockPos base = new BlockPos(
                bounds.minX() + level.getRandom().nextInt(sizeX),
                bounds.minY() + level.getRandom().nextInt(sizeY),
                bounds.minZ() + level.getRandom().nextInt(sizeZ)
            );
            if (level.getBlockState(base).isAir()) continue;

            final BlockPos firePos = base.above();
            if (!level.getBlockState(firePos).isAir()) continue;

            level.setBlock(firePos, Blocks.FIRE.defaultBlockState(), 3);
            placed++;
        }
    }

    private static void spawnCriticalFireworks(final ServerLevel level, final BoundingBox3ic bounds, final int count) {
        final double cx = (bounds.minX() + bounds.maxX() + 1.0) / 2.0;
        final double cy = (bounds.minY() + bounds.maxY() + 1.0) / 2.0;
        final double cz = (bounds.minZ() + bounds.maxZ() + 1.0) / 2.0;
        final double spawnRadius = Math.max(10.0, count);

        final IntArrayList colors = new IntArrayList(new int[]{
            DyeColor.BLACK.getFireworkColor(), DyeColor.GRAY.getFireworkColor()
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

    private static void chainPrimeAllTnt(final ServerLevel level, final ServerSubLevel sublevel,
                                          final SubLevelPhysicsData d) {
        final LevelPlot plot = sublevel.getPlot();
        final BoundingBox3ic bounds = plot.getBoundingBox();
        for (final BlockPos pos : BlockPos.betweenClosed(
                bounds.minX(), bounds.minY(), bounds.minZ(),
                bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
            final BlockState chainState = level.getBlockState(pos);
            if (chainState.is(Blocks.TNT)) {
                Blocks.TNT.onCaughtFire(Blocks.TNT.defaultBlockState(), level, pos, null, null);
                d.onBlockRemoved(pos.immutable(), chainState, level, BlockBreakCause.EXPLOSION);
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
