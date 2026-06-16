package com.kenmunk.createboomingleft.callback;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.TntBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;

public class TntChainExplosionCallback implements BlockSubLevelCollisionCallback {

    public static final TntChainExplosionCallback INSTANCE = new TntChainExplosionCallback();

    // Sable's internal velocity unit is BepuPhysics2 m/s, not blocks/tick.
    // Sable's built-in TNT threshold is 5.0. Calibrate this value in-game.
    private static final double TRIGGER_VELOCITY = 10.0;

    private TntChainExplosionCallback() {}

    @Override
    public CollisionResult sable$onCollision(final BlockPos blockPos, final BlockPos otherBlockPos,
                                              final Vector3d pos, final double impactVelocity) {
        if (impactVelocity * impactVelocity < TRIGGER_VELOCITY * TRIGGER_VELOCITY) {
            return CollisionResult.NONE;
        }

        final SubLevelPhysicsSystem system = SubLevelPhysicsSystem.getCurrentlySteppingSystem();
        final ServerLevel level = system.getLevel();

        if (!(level.getBlockState(blockPos).getBlock() instanceof TntBlock)) {
            return CollisionResult.NONE;
        }

        final SubLevel sublevel = Sable.HELPER.getContaining(level, blockPos);

        if (sublevel instanceof ServerSubLevel serverSubLevel) {
            final LevelPlot plot = serverSubLevel.getPlot();
            final BoundingBox3ic bounds = plot.getBoundingBox();

            for (BlockPos tntPos : BlockPos.betweenClosed(
                    bounds.minX(), bounds.minY(), bounds.minZ(),
                    bounds.maxX(), bounds.maxY(), bounds.maxZ())) {
                if (level.getBlockState(tntPos).is(Blocks.TNT)) {
                    primeTnt(level, tntPos.immutable());
                }
            }
        } else {
            primeTnt(level, blockPos);
        }

        return new CollisionResult(new Vector3d(), true);
    }

    private static void primeTnt(final ServerLevel level, final BlockPos pos) {
        final PrimedTnt entity = new PrimedTnt(level, pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, null);
        entity.setFuse(4);
        level.addFreshEntity(entity);
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 11);
    }
}
