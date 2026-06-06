package com.kenmunk.createboomingleft;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public class CreateBoomingLiftEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
        DeferredRegister.create(Registries.ENTITY_TYPE, CreateBoomingLift.MOD_ID);

    public static final DeferredHolder<EntityType<?>, EntityType<SubLevelSentinelEntity>> SUBLEVEL_SENTINEL =
        ENTITY_TYPES.register("sublevel_sentinel", () ->
            EntityType.Builder.<SubLevelSentinelEntity>of(SubLevelSentinelEntity::new, MobCategory.MISC)
                .sized(0.6f, 1.8f)
                .clientTrackingRange(10)
                .noSummon()
                .build("create_booming_lift:sublevel_sentinel")
        );
}
