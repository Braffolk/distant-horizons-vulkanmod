package net.vulkanmod.gl;

import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.texture.ImageUtil;
import net.vulkanmod.vulkan.texture.SamplerManager;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.lwjgl.vulkan.VK10.*;

public class VkGlTexture {
    private static int ID_COUNTER = 1;
    private static final Int2ReferenceOpenHashMap<VkGlTexture> map = new Int2ReferenceOpenHashMap<>();
    private static int boundTextureId = 0;
    private static VkGlTexture boundTexture;
    private static int activeTexture = 0;

    private static int unpackRowLength;
    private static int unpackSkipRows;
    private static int unpackSkipPixels;

    public static void bindIdToImage(int id, VulkanImage vulkanImage) {
        VkGlTexture texture = map.get(id);
        texture.vulkanImage = vulkanImage;
    }

    public static int genTextureId() {
        int id = ID_COUNTER;
        map.put(id, new VkGlTexture(id));
        ID_COUNTER++;
        return id;
    }

    public static void bindTexture(int id) {
        boundTextureId = id;
        boundTexture = map.get(id);

        if (id <= 0) {
            return;
        }

        if (boundTexture == null) {
            Initializer.LOGGER.error("Invalid id({}) value", id);
            return;
        }

        VulkanImage vulkanImage = boundTexture.vulkanImage;
        if (vulkanImage != null)
            VTextureSelector.bindTexture(activeTexture, vulkanImage);
    }

    public static void glDeleteTextures(IntBuffer intBuffer) {
        for (int i = intBuffer.position(); i < intBuffer.limit(); i++) {
            glDeleteTextures(intBuffer.get(i));
        }
    }

    public static void glDeleteTextures(int i) {
        VkGlTexture glTexture = map.remove(i);
        VulkanImage image = glTexture != null ? glTexture.vulkanImage : null;
        if (image != null)
            MemoryManager.getInstance().addToFreeable(image);
    }

    public static VkGlTexture getTexture(int id) {
        if (id == 0)
            return null;

        return map.get(id);
    }

    public static void activeTexture(int i) {
        activeTexture = i - GL30.GL_TEXTURE0;
        VTextureSelector.setActiveTexture(activeTexture);
    }

    public static void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, long pixels) {
        if (checkParams(level, width, height))
            return;

        boundTexture.updateParams(level, width, height, internalFormat, type);
        boundTexture.allocateIfNeeded();

        VTextureSelector.bindTexture(activeTexture, boundTexture.vulkanImage);

        texSubImage2D(target, level, 0, 0, width, height, format, type, pixels);
    }

    public static void texImage2D(int target, int level, int internalFormat, int width, int height, int border, int format, int type, @Nullable ByteBuffer pixels) {
        if (checkParams(level, width, height))
            return;

        boundTexture.updateParams(level, width, height, internalFormat, type);
        boundTexture.allocateIfNeeded();

        VTextureSelector.bindTexture(activeTexture, boundTexture.vulkanImage);

        texSubImage2D(target, level, 0, 0, width, height, format, type, pixels);
    }

    private static boolean checkParams(int level, int width, int height) {
        if (width == 0 || height == 0)
            return true;

        return false;
    }

    public static void texSubImage2D(int target, int level, int xOffset, int yOffset, int width, int height, int format, int type, long pixels) {
        if (width == 0 || height == 0)
            return;

        ByteBuffer src;

        VkGlBuffer glBuffer = VkGlBuffer.getPixelUnpackBufferBound();
        if (glBuffer != null) {

            glBuffer.data.position((int) pixels);
            src = glBuffer.data;
        } else {
            if (pixels != 0L) {
                src = getByteBuffer(width, height, pixels);
            } else {
                src = null;
            }
        }

        if (src != null)
            boundTexture.uploadSubImage(level, xOffset, yOffset, width, height, format, src);
    }

    private static ByteBuffer getByteBuffer(int width, int height, long pixels) {
        ByteBuffer src;
        // TODO: hardcoded format size
        int formatSize = 4;
        int rowLength = unpackRowLength != 0 ? unpackRowLength : width;
        int offset = (unpackSkipRows * rowLength + unpackSkipPixels) * formatSize;
        src = MemoryUtil.memByteBuffer(pixels + offset, (rowLength * height - unpackSkipPixels) * formatSize);
        return src;
    }

    public static void texSubImage2D(int target, int level, int xOffset, int yOffset, int width , int height, int format, int type, @Nullable ByteBuffer pixels) {
        if (width == 0 || height == 0)
            return;

        ByteBuffer src;

        VkGlBuffer glBuffer = VkGlBuffer.getPixelUnpackBufferBound();
        if (glBuffer != null) {
            if (pixels != null) {
                throw new IllegalStateException("Trying to use pixel buffer when there is a Pixel Unpack Buffer bound.");
            }

            glBuffer.data.position(0);
            src = glBuffer.data;
        } else {
            src = pixels;
        }

        if (src != null)
            boundTexture.uploadSubImage(level, xOffset, yOffset, width, height, format, src);
    }

    public static void texParameteri(int target, int pName, int param) {
        if (target != GL11.GL_TEXTURE_2D)
            throw new UnsupportedOperationException("target != GL_TEXTURE_2D not supported");

        if (boundTexture == null)
            return;

        switch (pName) {
            case GL30.GL_TEXTURE_MAX_LEVEL -> boundTexture.setMaxLevel(param);
            case GL30.GL_TEXTURE_MAX_LOD -> boundTexture.setMaxLod(param);
            case GL30.GL_TEXTURE_MIN_LOD -> {}
            case GL30.GL_TEXTURE_LOD_BIAS -> {}

            case GL11.GL_TEXTURE_MAG_FILTER -> boundTexture.setMagFilter(param);
            case GL11.GL_TEXTURE_MIN_FILTER -> boundTexture.setMinFilter(param);

            case GL11.GL_TEXTURE_WRAP_S, GL11.GL_TEXTURE_WRAP_T -> boundTexture.setClamp(param);

            default -> {}
        }

        //TODO
    }

    public static int getTexParameteri(int target, int pName) {
        if (target != GL11.GL_TEXTURE_2D)
            throw new UnsupportedOperationException("target != GL_TEXTURE_2D not supported");

        if (boundTexture == null)
            return -1;

        return switch (pName) {
            case GL11.GL_TEXTURE_INTERNAL_FORMAT -> GlUtil.getGlFormat(boundTexture.vulkanImage.format);
            case GL11.GL_TEXTURE_WIDTH -> boundTexture.vulkanImage.width;
            case GL11.GL_TEXTURE_HEIGHT -> boundTexture.vulkanImage.height;

            case GL30.GL_TEXTURE_MAX_LEVEL -> boundTexture.maxLevel;
            case GL30.GL_TEXTURE_MAX_LOD -> boundTexture.maxLod;

            case GL11.GL_TEXTURE_MAG_FILTER -> boundTexture.magFilter;
            case GL11.GL_TEXTURE_MIN_FILTER -> boundTexture.minFilter;

            default -> -1;
        };
    }

    public static int getTexLevelParameter(int target, int level, int pName) {
        if (target != GL11.GL_TEXTURE_2D)
            throw new UnsupportedOperationException("target != GL_TEXTURE_2D not supported");

        if (boundTexture == null)
            return -1;

        return switch (pName) {
            case GL11.GL_TEXTURE_INTERNAL_FORMAT -> GlUtil.getGlFormat(boundTexture.vulkanImage.format);
            case GL11.GL_TEXTURE_WIDTH -> boundTexture.vulkanImage.width;
            case GL11.GL_TEXTURE_HEIGHT -> boundTexture.vulkanImage.height;

            default -> -1;
        };
    }

    public static void pixelStoreI(int pName, int value) {
        switch (pName) {
            case GL11.GL_UNPACK_ROW_LENGTH -> unpackRowLength = value;
            case GL11.GL_UNPACK_SKIP_ROWS -> unpackSkipRows = value;
            case GL11.GL_UNPACK_SKIP_PIXELS -> unpackSkipPixels = value;
        }
    }

    public static void generateMipmap(int target) {
        if (target != GL11.GL_TEXTURE_2D)
            throw new UnsupportedOperationException("target != GL_TEXTURE_2D not supported");

        // TODO: crashing
//        boundTexture.generateMipmaps();
    }

    public static void getTexImage(int tex, int level, int format, int type, long pixels) {
        VulkanImage image = boundTexture.vulkanImage;

        VkGlBuffer buffer = VkGlBuffer.getPixelPackBufferBound();
        long ptr;
        if (buffer != null) {
            buffer.data.position((int) pixels);

            ptr = MemoryUtil.memAddress(buffer.data);
        } else {
            ptr = pixels;
        }


        ImageUtil.downloadTexture(image, ptr);
    }

    public static void setVulkanImage(int id, VulkanImage vulkanImage) {
        VkGlTexture texture = map.get(id);

        texture.vulkanImage = vulkanImage;
    }

    public static VkGlTexture getBoundTexture() {
        return boundTexture;
    }

    public final int id;
    VulkanImage vulkanImage;

    int width, height;
    int vkFormat;

    boolean needsUpdate = false;
    int maxLevel = 0;
    int maxLod = 0;
    int minFilter = GL11.GL_NEAREST, magFilter = GL11.GL_NEAREST;

    boolean clamp = true;

    public VkGlTexture(int id) {
        this.id = id;
    }

    void updateParams(int level, int width, int height, int internalFormat, int type) {
        if (level > this.maxLevel) {
            this.maxLevel = level;

            this.needsUpdate = true;
        }

        if (level == 0) {
            int vkFormat = GlUtil.vulkanFormat(internalFormat, type);

            if (this.vulkanImage == null || this.width != width || this.height != height || vkFormat != vulkanImage.format) {
                this.width = width;
                this.height = height;
                this.vkFormat = vkFormat;

                this.needsUpdate = true;
            }
        }
    }

    void allocateIfNeeded() {
        if (needsUpdate) {
            allocateImage(width, height, vkFormat);
            updateSampler();

            needsUpdate = false;
        }
    }

    void allocateImage(int width, int height, int vkFormat) {
        if (this.vulkanImage != null)
            this.vulkanImage.free();

        if (VulkanImage.isDepthFormat(vkFormat)) {
            this.vulkanImage = VulkanImage.createDepthImage(
                    vkFormat, width, height,
                    VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT,
                    false, true);
        }
        else {
            this.vulkanImage = new VulkanImage.Builder(width, height)
                    .setName(String.format("GlTexture %d", this.id))
                    .setMipLevels(maxLevel + 1)
                    .setFormat(vkFormat)
                    .addUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .createVulkanImage();
        }
    }

    void updateSampler() {
        if (vulkanImage == null)
            return;

        int addressMode = clamp ? VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE : VK_SAMPLER_ADDRESS_MODE_REPEAT;
        int vkMagFilter, vkMinFilter, mipmapMode;

        switch (minFilter) {
            case GL11.GL_LINEAR_MIPMAP_LINEAR, GL11.GL_LINEAR -> {
                vkMinFilter = VK_FILTER_LINEAR; mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
            }
            case GL11.GL_LINEAR_MIPMAP_NEAREST -> {
                vkMinFilter = VK_FILTER_LINEAR; mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
            }
            case GL11.GL_NEAREST_MIPMAP_NEAREST, GL11.GL_NEAREST -> {
                vkMinFilter = VK_FILTER_NEAREST; mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
            }
            case GL11.GL_NEAREST_MIPMAP_LINEAR -> {
                vkMinFilter = VK_FILTER_NEAREST; mipmapMode = VK_SAMPLER_MIPMAP_MODE_LINEAR;
            }
            default -> throw new IllegalStateException("Unexpected min filter value: %d".formatted(minFilter));
        }

        vkMagFilter = switch (magFilter) {
            case GL11.GL_LINEAR -> VK_FILTER_LINEAR;
            case GL11.GL_NEAREST -> VK_FILTER_NEAREST;
            default -> throw new IllegalStateException("Unexpected mag filter value: %d".formatted(magFilter));
        };

        long sampler = SamplerManager.getSampler(addressMode, addressMode, vkMinFilter, vkMagFilter, mipmapMode, maxLod, false, 0, -1);

        vulkanImage.setSampler(sampler);
    }

    private void uploadSubImage(int level, int xOffset, int yOffset, int width, int height, int format, ByteBuffer pixels) {
        ByteBuffer src;
        if (format == GL11.GL_RGB && vulkanImage.format == VK_FORMAT_R8G8B8A8_UNORM) {
            src = GlUtil.RGBtoRGBA_buffer(pixels);
        } else if (format == GL30.GL_BGRA && vulkanImage.format == VK_FORMAT_R8G8B8A8_UNORM) {
            src = GlUtil.BGRAtoRGBA_buffer(pixels);
        } else {
            src = pixels;
        }

        this.vulkanImage.uploadSubTextureAsync(level, width, height, xOffset, yOffset, 0, 0, unpackRowLength, src);

        if (src != pixels) {
            MemoryUtil.memFree(src);
        }
    }

    void generateMipmaps() {
        //TODO test
        ImageUtil.generateMipmaps(vulkanImage);
    }

    void setMaxLevel(int l) {
        if (l < 0)
            throw new IllegalStateException("max level cannot be < 0.");

        if (maxLevel != l) {
            maxLevel = l;
            needsUpdate = true;
        }
    }

    void setMaxLod(int l) {
        if (l < 0)
            throw new IllegalStateException("max level cannot be < 0.");

        if (maxLod != l) {
            maxLod = l;
            updateSampler();
        }
    }

    void setMagFilter(int v) {
        switch (v) {
            case GL11.GL_LINEAR, GL11.GL_NEAREST -> {
            }

            default -> throw new IllegalArgumentException("illegal mag filter value: " + v);
        }

        this.magFilter = v;
        updateSampler();
    }

    void setMinFilter(int v) {
        switch (v) {
            case GL11.GL_LINEAR, GL11.GL_NEAREST,
                 GL11.GL_LINEAR_MIPMAP_LINEAR, GL11.GL_NEAREST_MIPMAP_LINEAR,
                 GL11.GL_LINEAR_MIPMAP_NEAREST, GL11.GL_NEAREST_MIPMAP_NEAREST -> {
            }

            default -> throw new IllegalArgumentException("illegal min filter value: " + v);
        }

        this.minFilter = v;
        updateSampler();
    }

    void setClamp(int v) {
        if (v == GL30.GL_CLAMP_TO_EDGE) {
            this.clamp = true;
        } else {
            this.clamp = false;
        }

        updateSampler();
    }

    public VulkanImage getVulkanImage() {
        return vulkanImage;
    }

    public void setVulkanImage(VulkanImage vulkanImage) {
        this.vulkanImage = vulkanImage;
        this.width = vulkanImage.width;
        this.height = vulkanImage.height;
        this.maxLevel = vulkanImage.mipLevels;
        this.vkFormat = vulkanImage.format;
    }

}
