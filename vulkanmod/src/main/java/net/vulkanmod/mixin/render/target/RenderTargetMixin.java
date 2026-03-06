package net.vulkanmod.mixin.render.target;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.RenderPipelines;
import net.vulkanmod.render.engine.VkFbo;
import net.vulkanmod.render.engine.VkGpuTexture;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.*;

import java.util.OptionalInt;

@Mixin(RenderTarget.class)
public abstract class RenderTargetMixin {

    @Shadow public int width;
    @Shadow public int height;

    @Shadow @Nullable protected GpuTexture colorTexture;
    @Shadow @Nullable protected GpuTexture depthTexture;
    @Shadow @Nullable protected GpuTextureView colorTextureView;

    @Overwrite
    public void blitAndBlendToTexture(GpuTextureView gpuTextureView) {
        RenderSystem.assertOnRenderThread();

        VkFbo fbo = ((VkGpuTexture) this.colorTexture).getFbo(this.depthTexture);
        if (fbo.needsClear()) {
            return;
        }

        try (RenderPass renderPass = RenderSystem.getDevice()
                                                 .createCommandEncoder()
                                                 .createRenderPass(() -> "Blit render target", gpuTextureView, OptionalInt.empty())) {
            renderPass.setPipeline(RenderPipelines.ENTITY_OUTLINE_BLIT);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.bindSampler("InSampler", this.colorTextureView);
            renderPass.draw(0, 3);
        }
    }

//    @Inject(method = "getColorTextureView", at = @At("HEAD"))
//    private void injClear(CallbackInfoReturnable<GpuTextureView> cir) {
//        applyClear();
//    }
//
//    @Unique
//    private void applyClear() {
//        VkFbo fbo = ((VkGpuTexture) this.colorTexture).getFbo(this.depthTexture);
//        if (fbo.needsClear()) {
//            fbo.bind();
//        }
//    }
}
