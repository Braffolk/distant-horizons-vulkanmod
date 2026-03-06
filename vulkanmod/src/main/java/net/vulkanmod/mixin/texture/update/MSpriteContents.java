package net.vulkanmod.mixin.texture.update;

import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.render.texture.SpriteUpdateUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(SpriteContents.Ticker.class)
public class MSpriteContents {

    @Shadow int subFrame;
    @Shadow int frame;
    @Shadow @Final SpriteContents.AnimatedTexture animationInfo;

    @Inject(method = "tickAndUpload", at = @At("HEAD"), cancellable = true)
    private void checkUpload(int i, int j, GpuTexture gpuTexture, CallbackInfo ci) {
        if (!SpriteUpdateUtil.doUploadFrame()) {
            // Update animations frames even if no upload is scheduled
            ++this.subFrame;
            SpriteContents.FrameInfo frameInfo = this.animationInfo.frames.get(this.frame);
            if (this.subFrame >= frameInfo.time) {
                this.frame = (this.frame + 1) % this.animationInfo.frames.size();
                this.subFrame = 0;
            }

            ci.cancel();
        }
        else {
            SpriteUpdateUtil.addTransitionedLayout(((VkGpuTexture) gpuTexture).getVulkanImage());
        }
    }
}
