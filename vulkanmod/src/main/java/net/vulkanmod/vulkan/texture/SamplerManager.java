package net.vulkanmod.vulkan.texture;

import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSamplerCreateInfo;
import org.lwjgl.vulkan.VkSamplerReductionModeCreateInfo;

import java.nio.LongBuffer;

import static net.vulkanmod.vulkan.Vulkan.getVkDevice;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public abstract class SamplerManager {
    public static final int ADDRESS_MODE_BITS = 2;
    public static final int REDUCTION_MODE_BITS = 2;

    public static final int ADDRESS_MODE_U_OFFSET = 0;
    public static final int ADDRESS_MODE_V_OFFSET = 2;
    public static final int MIN_FILTER_OFFSET = 4;
    public static final int MAG_FILTER_OFFSET = 5;
    public static final int MIPMAP_MODE_OFFSET = 6;
    public static final int ANISOTROPY_OFFSET = 7;
    public static final int REDUCTION_MODE_ENABLE_OFFSET = 8;
    public static final int REDUCTION_MODE_OFFSET = 9;

    static final float MIP_BIAS = -0.5f;

    static final Object2LongMap<SamplerInfo> SAMPLERS = new Object2LongOpenHashMap<>();

    public static long getSampler(boolean clamp, boolean linearFiltering, int maxLod) {
        return getSampler(clamp, linearFiltering, maxLod, false, 0);
    }

    public static long getSampler(boolean clamp, boolean linearFiltering, int maxLod, boolean anisotropy, int maxAnisotropy) {
        int addressMode = clamp ? VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE : VK_SAMPLER_ADDRESS_MODE_REPEAT;
        int filter = linearFiltering ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
        int mipmapMode = linearFiltering ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST;

        return getSampler(addressMode, addressMode, filter, filter, mipmapMode, maxLod, anisotropy, maxAnisotropy, -1);
    }

    public static long getSampler(int addressModeU, int addressModeV,
                                  int minFilter, int magFilter, int mipmapMode, float maxLod,
                                  boolean anisotropy, float maxAnisotropy, int reductionMode) {
        SamplerInfo samplerInfo = new SamplerInfo(addressModeU, addressModeV, minFilter, magFilter, mipmapMode, maxLod, anisotropy, maxAnisotropy, reductionMode);

        long sampler = SAMPLERS.getOrDefault(samplerInfo, 0L);

        if (sampler == 0L) {
            sampler = createTextureSampler(samplerInfo);
            SAMPLERS.put(samplerInfo, sampler);
        }

        return sampler;
    }

    public static long getDefaultSampler() {
        return getSampler(false, false, 0);
    }

    private static long createTextureSampler(SamplerInfo sampler) {
        int state = sampler.encodedState;

        try (MemoryStack stack = stackPush()) {
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack);
            samplerInfo.sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO);

            samplerInfo.magFilter(sampler.getMagFilter());
            samplerInfo.minFilter(sampler.getMinFilter());

            samplerInfo.addressModeU(sampler.getAddressModeU());
            samplerInfo.addressModeV(sampler.getAddressModeV());
            samplerInfo.addressModeW(VK_SAMPLER_ADDRESS_MODE_REPEAT);

            samplerInfo.anisotropyEnable(sampler.getAnisotropy());
            samplerInfo.maxAnisotropy(sampler.getMaxAnisotropy());
            samplerInfo.borderColor(VK_BORDER_COLOR_INT_OPAQUE_WHITE);
            samplerInfo.unnormalizedCoordinates(false);
            samplerInfo.compareEnable(false);
            samplerInfo.compareOp(VK_COMPARE_OP_ALWAYS);

            samplerInfo.mipmapMode(sampler.getMipmapMode());
            samplerInfo.maxLod(sampler.getMaxLod());
            samplerInfo.minLod(0.0F);
            samplerInfo.mipLodBias(MIP_BIAS);

            // Reduction Mode
            if (sampler.hasReductionMode()) {
                VkSamplerReductionModeCreateInfo reductionModeInfo = VkSamplerReductionModeCreateInfo.calloc(stack);
                reductionModeInfo.sType$Default();
                reductionModeInfo.reductionMode(sampler.getReductionMode());
                samplerInfo.pNext(reductionModeInfo.address());
            }

            LongBuffer pTextureSampler = stack.mallocLong(1);

            if (vkCreateSampler(getVkDevice(), samplerInfo, null, pTextureSampler) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create texture sampler");
            }

            return pTextureSampler.get(0);
        }
    }

    public static void cleanUp() {
        for (long id : SAMPLERS.values()) {
            vkDestroySampler(DeviceManager.vkDevice, id, null);
        }
    }

    public static class SamplerInfo {
        final int encodedState;
        final int maxLod;
        final int maxAnisotropy;

        public SamplerInfo() {
            this(VK_SAMPLER_ADDRESS_MODE_REPEAT, VK_SAMPLER_ADDRESS_MODE_REPEAT,
                 VK_FILTER_NEAREST, VK_FILTER_NEAREST, VK_SAMPLER_MIPMAP_MODE_NEAREST,
                 0, false, 0, -1);
        }



        public SamplerInfo(int addressModeU, int addressModeV, int minFilter, int magFilter, int mipmapMode,
                           float maxLod, boolean anisotropy, float maxAnisotropy, int reductionMode) {
            this.maxLod = (int) maxLod;
            this.maxAnisotropy = (int) maxAnisotropy;

            int encodedState = (addressModeU & ADDRESS_MODE_BITS) << ADDRESS_MODE_U_OFFSET;
            encodedState |= (addressModeV & ADDRESS_MODE_BITS) << ADDRESS_MODE_V_OFFSET;
            encodedState |= (minFilter & 1) << MIN_FILTER_OFFSET;
            encodedState |= (magFilter & 1) << MAG_FILTER_OFFSET;
            encodedState |= (mipmapMode & 1) << MIPMAP_MODE_OFFSET;
            encodedState |= ((anisotropy ? 1 : 0) & 1) << ANISOTROPY_OFFSET;
            encodedState |= (reductionMode != -1 ? 1 : 0) << REDUCTION_MODE_ENABLE_OFFSET;
            encodedState |= (reductionMode & REDUCTION_MODE_BITS) << REDUCTION_MODE_OFFSET;

            this.encodedState = encodedState;
        }

        public int getAddressModeU() {
            return (this.encodedState >> ADDRESS_MODE_U_OFFSET) & ADDRESS_MODE_BITS;
        }

        public int getAddressModeV() {
            return (this.encodedState >> ADDRESS_MODE_V_OFFSET) & ADDRESS_MODE_BITS;
        }

        public int getMinFilter() {
            return (this.encodedState >> MIN_FILTER_OFFSET) & 1;
        }

        public int getMagFilter() {
            return (this.encodedState >> MAG_FILTER_OFFSET) & 1;
        }

        public int getMipmapMode() {
            return (this.encodedState >> MIPMAP_MODE_OFFSET) & 1;
        }

        public boolean getAnisotropy() {
            return ((this.encodedState >> ANISOTROPY_OFFSET) & 1) != 0;
        }

        public boolean hasReductionMode() {
            return ((this.encodedState >> REDUCTION_MODE_ENABLE_OFFSET) & 1) != 0;
        }

        public int getReductionMode() {
            return (this.encodedState >> REDUCTION_MODE_OFFSET) & REDUCTION_MODE_BITS;
        }

        public int getMaxAnisotropy() {
            return maxAnisotropy;
        }

        public int getMaxLod() {
            return maxLod;
        }

        @Override
        public boolean equals(Object o) {
            if (o == null || getClass() != o.getClass()) return false;

            SamplerInfo samplerInfo = (SamplerInfo) o;
            return maxLod == samplerInfo.maxLod && maxAnisotropy == samplerInfo.maxAnisotropy && encodedState == samplerInfo.encodedState;
        }

        @Override
        public int hashCode() {
            int result = encodedState;
            result = 31 * result + maxLod;
            result = 31 * result + maxAnisotropy;
            return result;
        }
    }

}
