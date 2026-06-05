package com.kenmunk.createboomingleft.mixin;

import com.kenmunk.createboomingleft.CoreBlockHolder;
import com.kenmunk.createboomingleft.CrashDetectionTracker;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

// remap = false because ServerLevelPlot is a Sable class, not an obfuscated Minecraft class.
@Mixin(value = ServerLevelPlot.class, remap = false)
public abstract class ServerLevelPlotMixin {

    @Shadow
    public abstract ServerSubLevel getSubLevel();

    @Shadow
    public abstract BoundingBox3ic getBoundingBox();

    @Inject(method = "onBlockChange", at = @At("RETURN"), remap = false)
    private void createBoomingLift$onBlockChange(
            final BlockPos pos, final BlockState newState, final CallbackInfo ci) {
        final ServerSubLevel sublevel = getSubLevel();
        if (!(sublevel instanceof CoreBlockHolder holder)) return;

        final BoundingBox3ic bounds = getBoundingBox();
        final double cx = (bounds.minX() + bounds.maxX() + 1.0) / 2.0;
        final double cy = (bounds.minY() + bounds.maxY() + 1.0) / 2.0;
        final double cz = (bounds.minZ() + bounds.maxZ() + 1.0) / 2.0;
        final Vec3 relative = new Vec3(
                pos.getX() + 0.5 - cx,
                pos.getY() + 0.5 - cy,
                pos.getZ() + 0.5 - cz);

        final float newMass = CoreBlockHolder.blockMass(newState);

        // TNT cache — add if the new state is TNT, remove otherwise.
        final Map<BlockPos, Vec3> tntMap = holder.createBoomingLift$getTntBlocks();
        if (newState.is(Blocks.TNT)) {
            if (tntMap.put(pos.immutable(), relative) == null) {
                final float health = CrashDetectionTracker.HEALTH_PER_CORE_BLOCK;
                holder.createBoomingLift$setMaxHealth(holder.createBoomingLift$getMaxHealth() + health);
                holder.createBoomingLift$setCurrentHealth(holder.createBoomingLift$getCurrentHealth() + health);
                holder.createBoomingLift$tryUpdatePeakCount();
            }
            if (newMass != 1.0f) {
                holder.createBoomingLift$getBlockMasses().put(pos.immutable(), newMass);
            }
        } else {
            final boolean wasTnt     = tntMap.remove(pos) != null;
            final boolean wasKinetic = holder.createBoomingLift$getKineticBlocks().remove(pos) != null;

            if (newState.isAir()) {
                if (CrashDetectionTracker.consumeWrenchBreak(pos.immutable())) {
                    // Wrench removal: undo the block's health contribution from both
                    // maxHealth and currentHealth, but clamp currentHealth to 1 so
                    // wrench removal cannot trigger detonation.
                    if (wasTnt || wasKinetic) {
                        final float c = CrashDetectionTracker.HEALTH_PER_CORE_BLOCK;
                        holder.createBoomingLift$setMaxHealth(
                                Math.max(0.0f, holder.createBoomingLift$getMaxHealth() - c));
                        holder.createBoomingLift$setCurrentHealth(
                                Math.max(1.0f, holder.createBoomingLift$getCurrentHealth() - c));
                    }
                } else if (!CrashDetectionTracker.consumePlayerBreak(pos.immutable())) {
                    // Non-player break: reduce currentHealth by the broken block's mass.
                    final Float stored = holder.createBoomingLift$getBlockMasses().get(pos);
                    final float mass = stored != null ? stored : 1.0f;
                    holder.createBoomingLift$setCurrentHealth(
                            Math.max(0.0f, holder.createBoomingLift$getCurrentHealth() - mass));
                }
                // Normal player break: no health change.
            }
            holder.createBoomingLift$getBlockMasses().remove(pos);
        }

        // TODO: kinetic block handling — uncomment once Create is added to build.gradle.
        // When adding to kinetic cache, also call tryUpdatePeakCount().
        // When a kinetic block is removed non-player, reduce currentHealth by its mass.
        // final Map<BlockPos, Vec3> kineticMap = holder.createBoomingLift$getKineticBlocks();
        // final net.minecraft.server.level.ServerLevel level = sublevel.getLevel();
        // if (level.getBlockEntity(pos) instanceof com.simibubi.create.content.kinetics.base.KineticBlockEntity) {
        //     if (kineticMap.put(pos.immutable(), relative) == null) {
        //         final float mass = CoreBlockHolder.blockMass(newState);
        //         holder.createBoomingLift$setMaxHealth(holder.createBoomingLift$getMaxHealth() + mass);
        //         holder.createBoomingLift$setCurrentHealth(holder.createBoomingLift$getCurrentHealth() + mass);
        //         holder.createBoomingLift$tryUpdatePeakCount();
        //     }
        // } else if (kineticMap.remove(pos) != null && newState.isAir()
        //         && !CrashDetectionTracker.consumePlayerBreak(pos.immutable())) {
        //     holder.createBoomingLift$setCurrentHealth(
        //             Math.max(0.0f, holder.createBoomingLift$getCurrentHealth() - 1.0f));
        // }
    }
}
