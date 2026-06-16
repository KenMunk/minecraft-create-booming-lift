package com.kenmunk.createboomingleft.mixin;

import com.kenmunk.createboomingleft.CrashDetectionTracker;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = SubLevel.class, remap = false)
public class SubLevelNameMixin {

    @Inject(method = "setName", at = @At("TAIL"))
    private void onNameSet(final String name, final CallbackInfo ci) {
        final SubLevel self = (SubLevel) (Object) this;
        if (self.getLevel() instanceof ServerLevel serverLevel) {
            CrashDetectionTracker.onSubLevelNameChanged(serverLevel, self);
        }
    }
}
