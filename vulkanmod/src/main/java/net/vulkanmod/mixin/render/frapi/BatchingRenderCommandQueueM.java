package net.vulkanmod.mixin.render.frapi;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.renderer.v1.mesh.MeshView;
import net.minecraft.client.renderer.OrderedSubmitNodeCollector;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SubmitNodeCollection;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.vulkanmod.render.chunk.build.frapi.accessor.AccessBatchingRenderCommandQueue;
import net.vulkanmod.render.chunk.build.frapi.accessor.AccessRenderCommandQueue;
import net.vulkanmod.render.chunk.build.frapi.render.MeshItemCommand;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(SubmitNodeCollection.class)
abstract class BatchingRenderCommandQueueM implements OrderedSubmitNodeCollector, AccessRenderCommandQueue, AccessBatchingRenderCommandQueue {
    @Shadow private boolean wasUsed;

    @Unique private final List<MeshItemCommand> meshItemCommands = new ArrayList<>();

    @Inject(method = "clear()V", at = @At("RETURN"))
    public void clear(CallbackInfo ci) {
        meshItemCommands.clear();
    }

    @Override
    public void submitItem(PoseStack matrices, ItemDisplayContext displayContext, int light, int overlay, int outlineColors, int[] tintLayers, List<BakedQuad> quads, RenderType renderLayer, ItemStackRenderState.FoilType glintType, MeshView mesh) {
        wasUsed = true;
        meshItemCommands.add(new MeshItemCommand(matrices.last().copy(), displayContext, light, overlay, outlineColors, tintLayers, quads, renderLayer, glintType, mesh));
    }

    @Override
    public List<MeshItemCommand> getMeshItemCommands() {
        return meshItemCommands;
    }
}
