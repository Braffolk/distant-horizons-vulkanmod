package net.vulkanmod.mixin.render.frapi;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.vulkanmod.render.chunk.build.frapi.accessor.AccessBatchingRenderCommandQueue;
import net.vulkanmod.render.chunk.build.frapi.render.ItemRenderContext;
import net.vulkanmod.render.chunk.build.frapi.render.MeshItemCommand;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemFeatureRenderer.class)
public class ItemFeatureRendererM {

    @Shadow @Final private PoseStack poseStack;

    @Unique private final ItemRenderContext itemRenderContext = new ItemRenderContext();

    @Inject(method = "render", at = @At("RETURN"))
    private void onReturnRender(SubmitNodeCollection queue, MultiBufferSource.BufferSource vertexConsumers, OutlineBufferSource outlineVertexConsumers, CallbackInfo ci) {
        for (MeshItemCommand itemCommand : ((AccessBatchingRenderCommandQueue) queue).getMeshItemCommands()) {
            poseStack.pushPose();
            poseStack.last().set(itemCommand.positionMatrix());

            itemRenderContext.renderModel(itemCommand.displayContext(), poseStack, vertexConsumers, itemCommand.lightCoords(), itemCommand.overlayCoords(), itemCommand.tintLayers(), itemCommand.quads(), itemCommand.mesh(), itemCommand.renderLayer(), itemCommand.glintType(), false);

            if (itemCommand.outlineColor() != 0) {
                outlineVertexConsumers.setColor(itemCommand.outlineColor());
                itemRenderContext.renderModel(itemCommand.displayContext(), poseStack, outlineVertexConsumers, itemCommand.lightCoords(), itemCommand.overlayCoords(), itemCommand.tintLayers(), itemCommand.quads(), itemCommand.mesh(), itemCommand.renderLayer(), ItemStackRenderState.FoilType.NONE, true);
            }

            poseStack.popPose();
        }
    }
}
