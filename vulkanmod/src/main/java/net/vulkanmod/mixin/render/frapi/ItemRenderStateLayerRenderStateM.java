package net.vulkanmod.mixin.render.frapi;

import com.mojang.blaze3d.vertex.PoseStack;
import net.fabricmc.fabric.api.renderer.v1.render.FabricLayerRenderState;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import net.vulkanmod.render.chunk.build.frapi.accessor.AccessLayerRenderState;
import net.vulkanmod.render.chunk.build.frapi.accessor.AccessRenderCommandQueue;
import net.vulkanmod.render.chunk.build.frapi.mesh.MutableMeshImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(value = ItemStackRenderState.LayerRenderState.class)
abstract class ItemRenderStateLayerRenderStateM implements FabricLayerRenderState, AccessLayerRenderState {
    @Unique
    private final MutableMeshImpl mutableMesh = new MutableMeshImpl();

    @Inject(method = "clear()V", at = @At("RETURN"))
    private void onReturnClear(CallbackInfo ci) {
        mutableMesh.clear();
    }

    @Redirect(method = "submit", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/SubmitNodeCollector;submitItem(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/world/item/ItemDisplayContext;III[ILjava/util/List;Lnet/minecraft/client/renderer/RenderType;Lnet/minecraft/client/renderer/item/ItemStackRenderState$FoilType;)V"))
    private void submitItemProxy(SubmitNodeCollector commandQueue, PoseStack matrices, ItemDisplayContext displayContext, int light, int overlay, int outlineColor, int[] tints, List<BakedQuad> quads, RenderType layer, ItemStackRenderState.FoilType glint) {
        if (mutableMesh.size() > 0 && commandQueue instanceof AccessRenderCommandQueue access) {
            // We don't have to copy the mesh here because vanilla doesn't copy the tint array or quad list either.
            access.submitItem(matrices, displayContext, light, overlay, outlineColor, tints, quads, layer, glint, mutableMesh);
        } else {
            commandQueue.submitItem(matrices, displayContext, light, overlay, outlineColor, tints, quads, layer, glint);
        }
    }

    @Override
    public MutableMeshImpl getMutableMesh() {
        return mutableMesh;
    }
}
