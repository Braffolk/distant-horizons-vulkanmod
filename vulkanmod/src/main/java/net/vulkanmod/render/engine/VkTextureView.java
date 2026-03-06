package net.vulkanmod.render.engine;

import com.mojang.blaze3d.textures.GpuTextureView;

public class VkTextureView extends GpuTextureView {
    private boolean closed;

    protected VkTextureView(VkGpuTexture gpuTexture, int i, int j) {
        super(gpuTexture, i, j);
        gpuTexture.addViews();
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            this.texture().removeViews();
        }
    }

    public VkGpuTexture texture() {
        return (VkGpuTexture) super.texture();
    }
}
