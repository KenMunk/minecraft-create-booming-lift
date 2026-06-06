package com.kenmunk.createboomingleft;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = CreateBoomingLift.MOD_ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public class CreateBoomingLiftClientEvents {

    @SubscribeEvent
    public static void registerEntityRenderers(final EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(
            CreateBoomingLiftEntities.SUBLEVEL_SENTINEL.get(),
            SentinelRenderer::new
        );
    }

    private static final class SentinelRenderer extends EntityRenderer<SubLevelSentinelEntity> {
        SentinelRenderer(final EntityRendererProvider.Context ctx) {
            super(ctx);
        }

        @Override
        public ResourceLocation getTextureLocation(final SubLevelSentinelEntity entity) {
            return ResourceLocation.fromNamespaceAndPath("minecraft", "textures/misc/white.png");
        }

        @Override
        public void render(final SubLevelSentinelEntity entity, final float entityYaw,
                           final float partialTick, final PoseStack poseStack,
                           final MultiBufferSource bufferSource, final int packedLight) {
            // invisible entity — render nothing
        }
    }
}
