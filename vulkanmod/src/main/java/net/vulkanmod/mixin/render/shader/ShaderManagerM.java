package net.vulkanmod.mixin.render.shader;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.pipeline.CompiledRenderPipeline;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import net.vulkanmod.render.shader.CustomRenderPipelines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderManager.class)
public class ShaderManagerM {

    @Inject(method = "apply(Lnet/minecraft/client/renderer/ShaderManager$Configs;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
            at = @At(value = "INVOKE", target = "Ljava/util/List;isEmpty()Z"))
    private void onApply(ShaderManager.Configs configs, ResourceManager resourceManager, ProfilerFiller profilerFiller,
                         CallbackInfo ci, @Local ShaderManager.CompilationCache compilationCache) {
        GpuDevice gpuDevice = RenderSystem.getDevice();
        var pipelines = CustomRenderPipelines.pipelines;

        for (RenderPipeline renderPipeline : pipelines) {
            CompiledRenderPipeline compiledRenderPipeline = gpuDevice.precompilePipeline(renderPipeline, compilationCache::getShaderSource);
        }
    }
}
