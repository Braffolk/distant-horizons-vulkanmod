package com.braffolk.dhvulkan.mixin.dh3;

import com.braffolk.dhvulkan.render.DhVulkanRenderBridge;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.ResourceHandle;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayerGroup;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * VulkanMod {@code @Redirect}s {@code ChunkSectionsToRender.renderGroup} to
 * {@code LevelRenderer.renderSectionLayer}. DH hooks {@code renderGroup} directly.
 *
 * We inject at the original {@code renderGroup} call sites inside
 * {@code lambda$addMainPass$0} (before each invoke), which still exist when VulkanMod
 * redirects the call.
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRendererRenderBridge {

    private static final String RENDER_GROUP =
            "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;renderGroup"
                    + "(Lnet/minecraft/client/renderer/chunk/ChunkSectionLayerGroup;"
                    + "Lcom/mojang/blaze3d/textures/GpuSampler;)V";

    private static final String LAMBDA_ADD_MAIN_PASS =
            "lambda$addMainPass$0(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
                    + "Lnet/minecraft/client/renderer/state/level/LevelRenderState;"
                    + "Lnet/minecraft/util/profiling/ProfilerFiller;"
                    + "Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"
                    + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                    + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                    + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                    + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                    + "Lcom/mojang/blaze3d/resource/ResourceHandle;"
                    + "ZLorg/joml/Matrix4fc;)V";

    @Inject(
            method = LAMBDA_ADD_MAIN_PASS,
            at = @At(value = "INVOKE", target = RENDER_GROUP, ordinal = 0),
            require = 1
    )
    private void dhvulkan$beforeOpaqueTerrain(
            GpuBufferSlice gpuBufferSlice,
            LevelRenderState levelRenderState,
            ProfilerFiller profiler,
            ChunkSectionsToRender chunkSections,
            ResourceHandle entityOutlineTarget,
            ResourceHandle entityTarget,
            ResourceHandle translucentTarget,
            ResourceHandle weatherTarget,
            ResourceHandle outlineTarget,
            boolean renderBlockOutline,
            Matrix4fc matrix4fc,
            CallbackInfo ci) {
        DhVulkanRenderBridge.onTerrainLayerGroup(ChunkSectionLayerGroup.OPAQUE, matrix4fc);
    }

    @Inject(
            method = LAMBDA_ADD_MAIN_PASS,
            at = @At(value = "INVOKE", target = RENDER_GROUP, ordinal = 1),
            require = 0
    )
    private void dhvulkan$beforeTranslucentTerrain(
            GpuBufferSlice gpuBufferSlice,
            LevelRenderState levelRenderState,
            ProfilerFiller profiler,
            ChunkSectionsToRender chunkSections,
            ResourceHandle entityOutlineTarget,
            ResourceHandle entityTarget,
            ResourceHandle translucentTarget,
            ResourceHandle weatherTarget,
            ResourceHandle outlineTarget,
            boolean renderBlockOutline,
            Matrix4fc matrix4fc,
            CallbackInfo ci) {
        DhVulkanRenderBridge.onTerrainLayerGroup(ChunkSectionLayerGroup.TRANSLUCENT, matrix4fc);
    }
}
