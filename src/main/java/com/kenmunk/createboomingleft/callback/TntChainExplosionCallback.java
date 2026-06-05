package com.kenmunk.createboomingleft.callback;

import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import net.minecraft.core.BlockPos;
import org.joml.Vector3d;

public class TntChainExplosionCallback implements BlockSubLevelCollisionCallback {

    public static final TntChainExplosionCallback INSTANCE = new TntChainExplosionCallback();

    private TntChainExplosionCallback() {}

    @Override
    public CollisionResult sable$onCollision(final BlockPos blockPos, final Vector3d pos, final double impactVelocity) {
        // Explosions are now triggered solely by the structure's health reaching 0
        // (see CrashDetectionTracker.maybeDetonate). Block-level collision no longer
        // primes TNT directly.
        return CollisionResult.NONE;
    }
}
