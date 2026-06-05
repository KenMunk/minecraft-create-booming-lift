package com.kenmunk.createboomingleft;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.mixinterface.plot.SubLevelContainerHolder;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePostPhysicsTickEvent;
import dev.ryanhcode.sable.neoforge.event.ForgeSablePrePhysicsTickEvent;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class CrashDetectionTracker {

    // Velocities are in RigidBodyHandle units (m/s, BepuPhysics2 SI).
    static final double STARTING_SPEED     = 4.0;
    static final double DELTA_V            = 4.0;
    static final int    WINDOW_TICKS       = 2;
    static final double MIN_IMPULSE_DV     = 1.5;
    static final int    SUSTAINED_SUBSTEPS = 5;

    private static final int    BLOCKS_PER_SCAN_TICK     = 10;
    private static final int    HEALTH_RECOVERY_INTERVAL = 200; // ticks between +1 recovery
    // Fall-damage safe distance analogue (mirrors Minecraft's 3-block safe fall).
    private static final double COLLISION_SAFE_DV        = 3.0;
    // Each core block (TNT or kinetic) contributes this many points to maxHealth.
    public  static final float  HEALTH_PER_CORE_BLOCK    = 20.0f;
    // Minimum ticks between two detonations of the same structure.
    private static final long   DETONATION_COOLDOWN      = 200L;

    /**
     * Positions being broken by a player this tick. Populated by onBlockBreak,
     * consumed by ServerLevelPlotMixin.onBlockChange, cleared at end of each tick.
     * Prevents non-player health reduction from firing on player-initiated breaks.
     */
    static final Set<BlockPos> PLAYER_BREAKING =
            Collections.synchronizedSet(new HashSet<>());

    public static boolean consumePlayerBreak(final BlockPos pos) {
        return PLAYER_BREAKING.remove(pos);
    }

    /**
     * Positions being removed by a player holding the Create wrench this tick.
     * Populated by onBlockBreak and onPlayerRightClickBlock, consumed by
     * ServerLevelPlotMixin.onBlockChange, cleared at end of each tick.
     * Wrench removal reduces both maxHealth and currentHealth without triggering
     * detonation (currentHealth is clamped to 1).
     */
    static final Set<BlockPos> WRENCH_BREAKING =
            Collections.synchronizedSet(new HashSet<>());

    public static boolean consumeWrenchBreak(final BlockPos pos) {
        return WRENCH_BREAKING.remove(pos);
    }

    private final Map<UUID, Double>  preSubstepSpeed    = new HashMap<>();
    private final Map<UUID, Integer> fastSubstepStreak  = new HashMap<>();
    private final Map<UUID, Window>  windows            = new HashMap<>();
    private final Map<UUID, Long>    lastDetonationTick = new HashMap<>();

    private record Window(double startSpeed, long startTick, double peakDeltaV) {
        Window update(double dv) {
            return new Window(startSpeed, startTick, Math.max(peakDeltaV, dv));
        }
    }

    private record ScheduledSound(ServerLevel level, long tick, double x, double y, double z) {}

    private final List<ScheduledSound> pendingSounds = new ArrayList<>();

    public void register() {
        NeoForge.EVENT_BUS.register(this);
    }

    // -------------------------------------------------------------------------
    // Player-break tracking
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onBlockBreak(final BlockEvent.BreakEvent event) {
        if (event.getPlayer() == null) return;
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        final SubLevelContainer container =
                ((SubLevelContainerHolder) level).sable$getPlotContainer();
        if (!container.inBounds(event.getPos())) return;
        if (isCreateWrench(event.getPlayer().getMainHandItem())) {
            WRENCH_BREAKING.add(event.getPos().immutable());
        } else {
            PLAYER_BREAKING.add(event.getPos().immutable());
        }
    }

    // Create's wrench sometimes removes blocks via level.setBlock() rather than the
    // normal break path, so we also mark the position on right-click to be safe.
    @SubscribeEvent
    public void onPlayerRightClickBlock(final PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        if (!isCreateWrench(event.getItemStack())) return;
        final SubLevelContainer container =
                ((SubLevelContainerHolder) level).sable$getPlotContainer();
        if (container.inBounds(event.getPos())) {
            WRENCH_BREAKING.add(event.getPos().immutable());
        }
    }

    // -------------------------------------------------------------------------
    // Physics ticks
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onPrePhysicsTick(final ForgeSablePrePhysicsTickEvent event) {
        final SubLevelPhysicsSystem system = event.getPhysicsSystem();
        final ServerLevel level = system.getLevel();
        for (final ServerSubLevel sublevel : getSubLevels(system)) {
            final RigidBodyHandle handle = system.getPhysicsHandle(sublevel);
            if (handle == null) continue;
            final UUID id = sublevel.getUniqueId();

            // Advance the throttled core-block scan at BLOCKS_PER_SCAN_TICK per tick.
            if (sublevel instanceof CoreBlockHolder holder) {
                final int cursor = holder.createBoomingLift$getScanCursor();
                if (cursor != -1) {
                    final BoundingBox3ic bounds = sublevel.getPlot().getBoundingBox();
                    final int xSize = bounds.maxX() - bounds.minX() + 1;
                    final int ySize = bounds.maxY() - bounds.minY() + 1;
                    final int zSize = bounds.maxZ() - bounds.minZ() + 1;
                    final int total = xSize * ySize * zSize;
                    final int end   = Math.min(cursor + BLOCKS_PER_SCAN_TICK, total);

                    final double cx = (bounds.minX() + bounds.maxX() + 1.0) / 2.0;
                    final double cy = (bounds.minY() + bounds.maxY() + 1.0) / 2.0;
                    final double cz = (bounds.minZ() + bounds.maxZ() + 1.0) / 2.0;

                    final Map<BlockPos, Vec3> tntMap     = holder.createBoomingLift$getTntBlocks();
                    final Map<BlockPos, Vec3> kineticMap = holder.createBoomingLift$getKineticBlocks();

                    for (int i = cursor; i < end; i++) {
                        final int lx = i % xSize;
                        final int ly = (i / xSize) % ySize;
                        final int lz = i / (xSize * ySize);
                        final BlockPos pos = new BlockPos(
                                bounds.minX() + lx, bounds.minY() + ly, bounds.minZ() + lz);
                        final BlockState blockState = level.getBlockState(pos);
                        final Vec3 relative = new Vec3(
                                pos.getX() + 0.5 - cx,
                                pos.getY() + 0.5 - cy,
                                pos.getZ() + 0.5 - cz);

                        if (blockState.is(Blocks.TNT)) {
                            if (tntMap.put(pos, relative) == null) {
                                final float health = HEALTH_PER_CORE_BLOCK;
                                holder.createBoomingLift$setMaxHealth(holder.createBoomingLift$getMaxHealth() + health);
                                holder.createBoomingLift$setCurrentHealth(holder.createBoomingLift$getCurrentHealth() + health);
                                holder.createBoomingLift$tryUpdatePeakCount();
                            }
                        } else if (isKineticBlock(level, pos)) {
                            if (kineticMap.put(pos, relative) == null) {
                                final float health = HEALTH_PER_CORE_BLOCK;
                                holder.createBoomingLift$setMaxHealth(holder.createBoomingLift$getMaxHealth() + health);
                                holder.createBoomingLift$setCurrentHealth(holder.createBoomingLift$getCurrentHealth() + health);
                                holder.createBoomingLift$tryUpdatePeakCount();
                            }
                        }

                        // Record non-default mass for any block so removal can look it up.
                        if (!blockState.isAir()) {
                            final float mass = CoreBlockHolder.blockMass(blockState);
                            if (mass != 1.0f) {
                                holder.createBoomingLift$getBlockMasses().put(pos, mass);
                            }
                        }
                    }

                    final int newCursor = end >= total ? -1 : end;
                    holder.createBoomingLift$setScanCursor(newCursor);
                    if (newCursor == -1) {
                        final long now = level.getGameTime();
                        for (int ring = 0; ring < 3; ring++) {
                            pendingSounds.add(new ScheduledSound(level, now + ring * 20L, cx, cy, cz));
                        }
                    }
                }
            }

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
                if (!(sublevel instanceof CoreBlockHolder holder)) continue;

                // Apply collision damage using the fall-damage formula.
                // peakDeltaV is treated as the fall-distance analogue;
                // COLLISION_SAFE_DV mirrors Minecraft's 3-block safe fall.
                final float damage = (float) Math.max(0.0, Math.ceil(w.peakDeltaV() - COLLISION_SAFE_DV));
                holder.createBoomingLift$setCurrentHealth(
                        Math.max(0, holder.createBoomingLift$getCurrentHealth() - damage));

                windows.remove(id);

                maybeDetonate(holder, sublevel, level, now);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Level tick
    // -------------------------------------------------------------------------

    @SubscribeEvent
    public void onLevelTick(final LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        final long now = level.getGameTime();

        // Fire pending bell rings (3 rings, 20 ticks apart, scheduled on scan completion).
        pendingSounds.removeIf(sound -> {
            if (sound.level() != level || now < sound.tick()) return false;
            level.playSound(null, sound.x(), sound.y(), sound.z(),
                    SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 2.0f, 1.0f);
            return true;
        });

        final SubLevelContainer container = ((SubLevelContainerHolder) level).sable$getPlotContainer();
        if (container instanceof ServerSubLevelContainer c) {
            // Health recovery: +1 per HEALTH_RECOVERY_INTERVAL ticks, capped at maxCurrentHealth.
            if (now % HEALTH_RECOVERY_INTERVAL == 0) {
                for (final ServerSubLevel sublevel : c.getAllSubLevels()) {
                    if (!(sublevel instanceof CoreBlockHolder holder)) continue;
                    final float maxCurrent = holder.createBoomingLift$getMaxCurrentHealth();
                    final float current    = holder.createBoomingLift$getCurrentHealth();
                    if (current < maxCurrent) {
                        holder.createBoomingLift$setCurrentHealth(Math.min(current + 1.0f, maxCurrent));
                    }
                }
            }

            // Detonation check: catches health reaching 0 via block removal
            // (onBlockChange path), which isn't covered by onPostPhysicsTick.
            for (final ServerSubLevel sublevel : c.getAllSubLevels()) {
                if (!(sublevel instanceof CoreBlockHolder holder)) continue;
                maybeDetonate(holder, sublevel, level, now);
            }

            // Progressive health-based smoke and blindness on cached TNT and kinetic blocks.
            // Smoke begins at 75% of maxCurrentHealth (light) and peaks at 1% (extremely heavy).
            // Blindness is applied to players on the contraption at ≤ 10%.
            for (final ServerSubLevel sublevel : c.getAllSubLevels()) {
                if (!(sublevel instanceof CoreBlockHolder holder)) continue;
                final float maxCurrent = holder.createBoomingLift$getMaxCurrentHealth();
                if (maxCurrent <= 0) continue;
                final float ratio = holder.createBoomingLift$getCurrentHealth() / maxCurrent;

                // Blindness — runs every tick, not interval-gated.
                if (ratio <= 0.10f) {
                    final BoundingBox3ic bounds = sublevel.getPlot().getBoundingBox();
                    for (final ServerPlayer player : level.players()) {
                        final BlockPos pp = player.blockPosition();
                        if (pp.getX() >= bounds.minX() && pp.getX() <= bounds.maxX()
                                && pp.getY() >= bounds.minY() && pp.getY() <= bounds.maxY() + 1
                                && pp.getZ() >= bounds.minZ() && pp.getZ() <= bounds.maxZ()) {
                            player.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, 60, 0));
                        }
                    }
                }

                // Smoke — interval-gated.
                if (ratio > 0.75f) continue;

                // 0.0 at 75% health → 1.0 at 1% health
                final float intensity = Math.min(1.0f, (0.75f - ratio) / 0.74f);
                // Emission interval: 40 ticks at minimum intensity → 1 tick at maximum
                final int interval = Math.max(1, Math.round(40.0f * (1.0f - intensity)));
                if (now % interval != 0) continue;

                final int    count    = 1 + Math.round(intensity * 4);  // 1 → 5 particles
                final double spread   = 0.2 + intensity * 0.4;          // 0.2 → 0.6 radius
                final var    particle = intensity < 0.33f ? ParticleTypes.SMOKE
                                      : intensity < 0.66f ? ParticleTypes.LARGE_SMOKE
                                      : ParticleTypes.CAMPFIRE_COSY_SMOKE;

                for (final BlockPos pos : holder.createBoomingLift$getTntBlocks().keySet()) {
                    level.sendParticles(particle,
                            pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                            count, spread, 0.3, spread, 0.02);
                }
                for (final BlockPos pos : holder.createBoomingLift$getKineticBlocks().keySet()) {
                    level.sendParticles(particle,
                            pos.getX() + 0.5, pos.getY() + 1.0, pos.getZ() + 0.5,
                            count, spread, 0.3, spread, 0.02);
                }
            }
        }

        PLAYER_BREAKING.clear();
        WRENCH_BREAKING.clear();
    }

    // -------------------------------------------------------------------------
    // Detonation
    // -------------------------------------------------------------------------

    private void maybeDetonate(final CoreBlockHolder holder, final ServerSubLevel sublevel,
            final ServerLevel level, final long now) {
        if (holder.createBoomingLift$getCurrentHealth() > 0) return;
        if (holder.createBoomingLift$getPeakCoreBlockCount() == 0) return;

        final UUID id = sublevel.getUniqueId();
        if (now - lastDetonationTick.getOrDefault(id, 0L) < DETONATION_COOLDOWN) return;
        lastDetonationTick.put(id, now);

        final BoundingBox3ic bounds = sublevel.getPlot().getBoundingBox();
        final double cx = (bounds.minX() + bounds.maxX() + 1.0) / 2.0;
        final double cy = (bounds.minY() + bounds.maxY() + 1.0) / 2.0;
        final double cz = (bounds.minZ() + bounds.maxZ() + 1.0) / 2.0;
        final float radius = holder.createBoomingLift$getPeakCoreBlockCount() * 8.0f;
        level.explode(null, cx, cy, cz, radius, Level.ExplosionInteraction.TNT);

        // Remove any TNT blocks the explosion didn't reach.
        final Map<BlockPos, Vec3> tntBlocks = holder.createBoomingLift$getTntBlocks();
        for (final BlockPos tntPos : List.copyOf(tntBlocks.keySet())) {
            level.setBlock(tntPos, Blocks.AIR.defaultBlockState(), 11);
        }
        tntBlocks.clear();
    }

    // -------------------------------------------------------------------------
    // Utilities
    // -------------------------------------------------------------------------

    private static boolean isCreateWrench(final ItemStack stack) {
        final ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return id != null && "create".equals(id.getNamespace()) && "wrench".equals(id.getPath());
    }

    /**
     * Returns true if the block at pos is a Create kinetic block entity.
     * Requires Create as a dependency.
     */
    private static boolean isKineticBlock(final ServerLevel level, final BlockPos pos) {
        // TODO: add Create as a dependency in build.gradle, then replace with:
        // return level.getBlockEntity(pos) instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity;
        return false;
    }

    private static List<ServerSubLevel> getSubLevels(final SubLevelPhysicsSystem system) {
        final SubLevelContainer container =
            ((SubLevelContainerHolder) system.getLevel()).sable$getPlotContainer();
        return container instanceof ServerSubLevelContainer c ? c.getAllSubLevels() : List.of();
    }
}
