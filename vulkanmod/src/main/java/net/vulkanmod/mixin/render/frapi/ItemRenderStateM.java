package net.vulkanmod.mixin.render.frapi;

import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.vulkanmod.render.chunk.build.frapi.accessor.AccessLayerRenderState;
import net.vulkanmod.render.chunk.build.frapi.mesh.MutableMeshImpl;
import net.vulkanmod.render.chunk.build.frapi.render.QuadToPosPipe;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Consumer;

@Mixin(ItemStackRenderState.class)
abstract class ItemRenderStateM {
    @Inject(method = "visitExtents", at = @At(value = "NEW", target = "()Lcom/mojang/blaze3d/vertex/PoseStack$Pose;"))
    private void afterInitVecLoad(Consumer<Vector3fc> posConsumer, CallbackInfo ci, @Local Vector3f vec, @Share("pipe") LocalRef<QuadToPosPipe> pipeRef) {
        pipeRef.set(new QuadToPosPipe(posConsumer, vec));
    }

    @Inject(method = "visitExtents", at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/PoseStack$Pose;setIdentity()V", shift = At.Shift.BEFORE))
    private void afterLayerLoad(Consumer<Vector3fc> posConsumer, CallbackInfo ci, @Local(ordinal = 0) Vector3f vec, @Local ItemStackRenderState.LayerRenderState layer, @Local Matrix4f matrix, @Share("pipe") LocalRef<QuadToPosPipe> pipeRef) {
        MutableMeshImpl mutableMesh = ((AccessLayerRenderState) layer).getMutableMesh();

        if (mutableMesh.size() > 0) {
            QuadToPosPipe pipe = pipeRef.get();
            pipe.matrix = matrix;
            // Use the mutable version here as it does not use a ThreadLocal or cursor stack
            mutableMesh.forEachMutable(pipe);
        }
    }
}
