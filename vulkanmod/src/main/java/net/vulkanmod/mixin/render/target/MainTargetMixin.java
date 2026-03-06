package net.vulkanmod.mixin.render.target;

import com.mojang.blaze3d.pipeline.MainTarget;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.*;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(MainTarget.class)
public class MainTargetMixin extends RenderTarget {

    public MainTargetMixin(boolean useDepth) {
        super("Main", useDepth);
    }

    /**
     * @author
     * @reason
     */
    @Overwrite
    private void createFrameBuffer(int width, int height) {
        this.width = width;
        this.height = height;
    }

    @Override
    public void createBuffers(int i, int j) {
        RenderSystem.assertOnRenderThread();
        int k = RenderSystem.getDevice().getMaxTextureSize();
        if (i > 0 && i <= k && j > 0 && j <= k) {
            this.width = i;
            this.height = j;
            if (this.useDepth) {
                this.depthTexture = RenderSystem.getDevice().createTexture(() -> this.label + " / Depth", 15, TextureFormat.DEPTH32, i, j, 1, 1);
                this.depthTexture.setTextureFilter(FilterMode.NEAREST, false);
                this.depthTexture.setAddressMode(AddressMode.CLAMP_TO_EDGE);
            }

            this.colorTexture = RenderSystem.getDevice().createTexture(() -> this.label + " / Color", 15, TextureFormat.RGBA8, i, j, 1, 1);
            this.colorTexture.setAddressMode(AddressMode.CLAMP_TO_EDGE);
            this.setFilterMode(FilterMode.NEAREST, true);
        } else {
            throw new IllegalArgumentException("Window " + i + "x" + j + " size out of bounds (max. size: " + k + ")");
        }
    }

    private void setFilterMode(FilterMode filterMode, boolean bl) {
        if (this.colorTexture == null) {
            throw new IllegalStateException("Can't change filter mode, color texture doesn't exist yet");
        } else {
            if (bl || filterMode != this.filterMode) {
                this.filterMode = filterMode;
                this.colorTexture.setTextureFilter(filterMode, false);
            }
        }
    }

    @Override
    public GpuTexture getColorTexture() {
        return Renderer.getInstance().getMainPass().getColorAttachment();
    }

    @Override
    public GpuTextureView getColorTextureView() {
        return Renderer.getInstance().getMainPass().getColorAttachmentView();
    }

    @Override
    public GpuTexture getDepthTexture() {
        return Renderer.getInstance().getMainPass().getDepthAttachment();
    }
}
