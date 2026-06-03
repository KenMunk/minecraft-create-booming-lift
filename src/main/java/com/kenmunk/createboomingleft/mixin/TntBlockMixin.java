package com.kenmunk.createboomingleft.mixin;

import com.kenmunk.createboomingleft.callback.TntChainExplosionCallback;
import dev.ryanhcode.sable.api.block.BlockWithSubLevelCollisionCallback;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import net.minecraft.world.level.block.TntBlock;
import org.spongepowered.asm.mixin.Mixin;

// priority 1500 > Sable's default 1000, so our sable$getCallback() overwrites theirs
@Mixin(value = TntBlock.class, priority = 1500)
public abstract class TntBlockMixin implements BlockWithSubLevelCollisionCallback {

    @Override
    public BlockSubLevelCollisionCallback sable$getCallback() {
        return TntChainExplosionCallback.INSTANCE;
    }
}
