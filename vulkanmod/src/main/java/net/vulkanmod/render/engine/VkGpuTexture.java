package net.vulkanmod.render.engine;

import com.mojang.blaze3d.opengl.GlStateManager;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.textures.AddressMode;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import it.unimi.dsi.fastutil.ints.*;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.VK10;

@Environment(EnvType.CLIENT)
public class VkGpuTexture extends GlTexture {
    private static final Reference2ReferenceOpenHashMap<GlTexture, VkGpuTexture> glToVkMap = new Reference2ReferenceOpenHashMap<>();

    protected VkGlTexture glTexture;
    protected final int id;
    private final Int2ReferenceMap<VkFbo> fboCache = new Int2ReferenceOpenHashMap<>();
    protected boolean closed;
    protected boolean modesDirty = true;

    boolean needsClear = false;
    int clearColor = 0;
    float depthClearValue = 1.0f;

    protected VkGpuTexture(int usage, String string, TextureFormat textureFormat, int width, int height, int layers, int mipLevel, int id, VkGlTexture glTexture) {
        super(usage, string, textureFormat, width, height, layers, mipLevel, id);
        this.id = id;
        this.glTexture = glTexture;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;
            GlStateManager._deleteTexture(this.id);

            for (VkFbo fbo : this.fboCache.values()) {
                fbo.close();
            }
        }
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    public void flushModeChanges() {
        if (this.modesDirty) {
            int maxLod = this.useMipmaps ? this.getMipLevels() - 1 : 0;

            int magFilterVk = magFilter == FilterMode.LINEAR ? VK10.VK_FILTER_LINEAR : VK10.VK_FILTER_NEAREST;
            int minFilterVk = minFilter == FilterMode.LINEAR ? VK10.VK_FILTER_LINEAR : VK10.VK_FILTER_NEAREST;

            int addressModeUVk = this.addressModeU == AddressMode.REPEAT ? VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT : VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            int addressModeVVk = this.addressModeV == AddressMode.REPEAT ? VK10.VK_SAMPLER_ADDRESS_MODE_REPEAT : VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;

            long sampler = SamplerManager.getSampler(addressModeUVk, addressModeVVk,
                                                     minFilterVk, magFilterVk, VK10.VK_SAMPLER_MIPMAP_MODE_LINEAR,
                                                     maxLod, false, 0, -1);

            glTexture.getVulkanImage().setSampler(sampler);

            this.modesDirty = false;
        }
    }

    public int glId() {
        return this.id;
    }

    @Override
    public void setAddressMode(AddressMode addressMode, AddressMode addressMode2) {
        super.setAddressMode(addressMode, addressMode2);
        this.modesDirty = true;
    }

    @Override
    public void setTextureFilter(FilterMode filterMode, FilterMode filterMode2, boolean bl) {
        super.setTextureFilter(filterMode, filterMode2, bl);
        this.modesDirty = true;
    }

    @Override
    public void setUseMipmaps(boolean bl) {
        super.setUseMipmaps(bl);
        this.modesDirty = true;
    }

    public void setClearColor(int clearColor) {
        this.needsClear = true;
        this.clearColor = clearColor;
    }

    public void setDepthClearValue(float depthClearValue) {
        this.needsClear = true;
        this.depthClearValue = depthClearValue;
    }

    public boolean needsClear() {
        return needsClear;
    }

    public VkFbo getFbo(@Nullable GpuTexture depthAttachment) {
        int depthAttachmentId = depthAttachment == null ? 0 : ((VkGpuTexture)depthAttachment).id;
        return this.fboCache.computeIfAbsent(depthAttachmentId, j -> new VkFbo(this, (VkGpuTexture) depthAttachment));
    }

    public VulkanImage getVulkanImage() {
        return glTexture.getVulkanImage();
    }

    public static VkGpuTexture fromGlTexture(GlTexture glTexture) {
        return glToVkMap.computeIfAbsent(glTexture, glTexture1 -> {
            var name = glTexture.getLabel();
            int id = glTexture.glId();
            VkGlTexture vglTexture = VkGlTexture.getTexture(id);
            VkGpuTexture gpuTexture = new VkGpuTexture(0, name, glTexture.getFormat(),
                                                       glTexture.getWidth(0), glTexture.getHeight(0),
                                                       1, glTexture.getMipLevels(),
                                                       glTexture.glId(), vglTexture);

            return gpuTexture;
        });
    }

    public static TextureFormat textureFormat(int format) {
        return switch (format) {
            case VK10.VK_FORMAT_R8G8B8A8_UNORM, VK10.VK_FORMAT_B8G8R8A8_UNORM, VK10.VK_FORMAT_R8G8B8A8_SRGB -> TextureFormat.RGBA8;
            case VK10.VK_FORMAT_R8_UNORM -> TextureFormat.RED8;
            case VK10.VK_FORMAT_D32_SFLOAT -> TextureFormat.DEPTH32;
            default -> null;
        };
    }

    public static int vkFormat(TextureFormat textureFormat) {
        return switch (textureFormat) {
            case RGBA8 -> VK10.VK_FORMAT_R8G8B8A8_UNORM;
            case RED8 -> VK10.VK_FORMAT_R8_UNORM;
            case RED8I -> VK10.VK_FORMAT_R8_SINT;
            case DEPTH32 -> VK10.VK_FORMAT_D32_SFLOAT;
        };
    }

    public static int vkImageViewType(int usage) {
        int viewType;
        if ((usage & GpuTexture.USAGE_CUBEMAP_COMPATIBLE) != 0) {
            viewType = VK10.VK_IMAGE_VIEW_TYPE_CUBE;
        }
        else {
            viewType = VK10.VK_IMAGE_VIEW_TYPE_2D;
        }

        return viewType;
    }
}

