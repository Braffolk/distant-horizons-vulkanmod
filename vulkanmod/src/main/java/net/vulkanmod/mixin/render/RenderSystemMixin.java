package net.vulkanmod.mixin.render;

import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.renderer.DynamicUniforms;
import net.minecraft.resources.ResourceLocation;
import net.vulkanmod.render.engine.VkGpuDevice;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.function.BiFunction;

import static com.mojang.blaze3d.systems.RenderSystem.*;

@Mixin(RenderSystem.class)
public abstract class RenderSystemMixin {
    @Shadow private static Matrix4f textureMatrix;
    @Shadow private static @Nullable Thread renderThread;

    @Shadow private static @Nullable GpuDevice DEVICE;
    @Shadow private static @Nullable DynamicUniforms dynamicUniforms;

    @Shadow private static String apiDescription;

    @Shadow
    public static void assertOnRenderThread() {}

    @Overwrite(remap = false)
    public static void initRenderer(long l, int i, boolean bl, BiFunction<ResourceLocation, ShaderType, String> shaderSource, boolean bl2) {
        renderThread.setPriority(Thread.NORM_PRIORITY + 2);

        VRenderSystem.initRenderer();

        DEVICE = new VkGpuDevice(l, i, bl, shaderSource, bl2);
        apiDescription = getDevice().getImplementationInformation();

        Renderer.initRenderer();

        dynamicUniforms = new DynamicUniforms();
    }

    @Overwrite(remap = false)
    public static void setTextureMatrix(Matrix4f matrix4f) {
        assertOnRenderThread();
        textureMatrix.set(matrix4f);
        VRenderSystem.setTextureMatrix(matrix4f);
    }

    @Overwrite(remap = false)
    public static void resetTextureMatrix() {
        assertOnRenderThread();
        textureMatrix.identity();
        VRenderSystem.setTextureMatrix(textureMatrix);
    }

}
