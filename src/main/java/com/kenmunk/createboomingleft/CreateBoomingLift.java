package com.kenmunk.createboomingleft;

import net.minecraft.world.entity.PathfinderMob;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

@Mod(CreateBoomingLift.MOD_ID)
public class CreateBoomingLift {
    public static final String MOD_ID = "create_booming_lift";

    public CreateBoomingLift(final IEventBus modEventBus) {
        CreateBoomingLiftEntities.ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(CreateBoomingLift::registerAttributes);
        new CrashDetectionTracker().register();
    }

    private static void registerAttributes(final EntityAttributeCreationEvent event) {
        event.put(CreateBoomingLiftEntities.SUBLEVEL_SENTINEL.get(),
            PathfinderMob.createMobAttributes().build());
    }
}
