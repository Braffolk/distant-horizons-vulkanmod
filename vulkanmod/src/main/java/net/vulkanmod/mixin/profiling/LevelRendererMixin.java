package net.vulkanmod.mixin.profiling;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.resource.ResourceHandle;
import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.LevelRenderState;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.phys.Vec3;
import net.vulkanmod.render.profiling.Profiler;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public class LevelRendererMixin {

    @Inject(method = "method_62205", at = @At("HEAD"))
    private void pushProfiler(int i, CloudStatus cloudStatus, float f, Vec3 vec3, float g, CallbackInfo ci) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.push("Clouds");
    }

    @Inject(method = "method_62205", at = @At("RETURN"))
    private void popProfiler(int i, CloudStatus cloudStatus, float f, Vec3 vec3, float g, CallbackInfo ci) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.pop();
    }

    // TODO: fix
    @Inject(method = "method_62213", at = @At(value = "HEAD"))
    private void pushProfiler3(GpuBufferSlice gpuBufferSlice, ResourceHandle resourceHandle,
                               ResourceHandle resourceHandle2, CallbackInfo ci) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.push("Particles");
    }

    @Inject(method = "method_62213", at = @At(value = "RETURN"))
    private void popProfiler3(GpuBufferSlice gpuBufferSlice, ResourceHandle resourceHandle,
                              ResourceHandle resourceHandle2, CallbackInfo ci) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.pop();
    }

    @Inject(method = "method_62214",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/renderer/LevelRenderer;submitEntities(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/state/LevelRenderState;Lnet/minecraft/client/renderer/SubmitNodeCollector;)V"))
    private void profilerTerrain2(GpuBufferSlice gpuBufferSlice, LevelRenderState levelRenderState,
                                  ProfilerFiller profilerFiller, Matrix4f matrix4f, ResourceHandle resourceHandle,
                                  ResourceHandle resourceHandle2, boolean bl, Frustum frustum,
                                  ResourceHandle resourceHandle3, ResourceHandle resourceHandle4, CallbackInfo ci) {
        Profiler profiler = Profiler.getMainProfiler();
        profiler.pop();
        profiler.push("entities");
    }


}
