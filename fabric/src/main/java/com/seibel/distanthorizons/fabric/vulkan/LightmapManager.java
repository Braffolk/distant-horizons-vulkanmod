package com.seibel.distanthorizons.fabric.vulkan;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.vulkanmod.render.texture.ImageUploadHelper;
import net.vulkanmod.render.texture.SpriteUpdateUtil;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.buffer.StagingBuffer;
import net.vulkanmod.vulkan.texture.VulkanImage;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Computes lightmap data on CPU and uploads to a VulkanImage.
 * Bypasses MC 1.21.x's framebuffer-based lightmap which doesn't work under
 * VulkanMod.
 * Lighting computation is based on VulkanMod's disabled MLightTexture mixin.
 */
public class LightmapManager {
    private static final Logger LOGGER = LoggerFactory.getLogger("DH-LightmapManager");
    private static final int SIZE = 16;

    private VulkanImage lightmapImage;
    private ByteBuffer pixelBuffer;
    private boolean initialized = false;

    // Reusable vectors to avoid allocation
    private final Vector3f skyLightColor = new Vector3f();
    private final Vector3f lightColor = new Vector3f();
    private final Vector3f tempVec = new Vector3f();

    public void init() {
        if (initialized)
            return;
        try {
            lightmapImage = new VulkanImage.Builder(SIZE, SIZE)
                    .setFormat(VK_FORMAT_R8G8B8A8_UNORM)
                    .setLinearFiltering(true)
                    .createVulkanImage();

            pixelBuffer = MemoryUtil.memAlloc(SIZE * SIZE * 4);

            // Initialize to full white (fully lit)
            for (int i = 0; i < SIZE * SIZE; i++) {
                pixelBuffer.putInt(i * 4, 0xFFFFFFFF);
            }

            // Initial upload
            uploadToGpu();

            initialized = true;
            LOGGER.info("[DH-Vulkan] LightmapManager initialized ({}x{} VulkanImage)", SIZE, SIZE);
        } catch (Exception e) {
            LOGGER.error("[DH-Vulkan] Failed to create lightmap image", e);
        }
    }

    /**
     * Update lightmap data based on current world lighting conditions.
     * Should be called each frame from beginFrame().
     */
    public void update(float partialTicks) {
        if (!initialized) {
            init();
            if (!initialized)
                return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null || mc.player == null)
            return;

        GameRenderer renderer = mc.gameRenderer;

        float skyDarken = level.getSkyDarken(1.0f);
        float skyFlashTime;
        if (level.getSkyFlashTime() > 0) {
            skyFlashTime = 1.0f;
        } else {
            skyFlashTime = skyDarken * 0.95f + 0.05f;
        }

        float darknessEffectScale = mc.options.darknessEffectScale().get().floatValue();
        float darknessGamma = getDarknessGamma(mc, partialTicks) * darknessEffectScale;
        float darknessScale = getDarknessScale(mc.player, darknessGamma, partialTicks) * darknessEffectScale;

        float waterVision = mc.player.getWaterVision();
        float nightVisionFactor;
        if (mc.player.hasEffect(MobEffects.NIGHT_VISION)) {
            nightVisionFactor = GameRenderer.getNightVisionScale(mc.player, partialTicks);
        } else if (waterVision > 0.0f && mc.player.hasEffect(MobEffects.CONDUIT_POWER)) {
            nightVisionFactor = waterVision;
        } else {
            nightVisionFactor = 0.0f;
        }

        // Sky light base color
        float skyR = Mth.lerp(0.35f, skyDarken, 1.0f);
        skyLightColor.set(skyR, skyR, 1.0f);

        float blockRedFlicker = getBlockLightRedFlicker(mc) + 1.5f;
        float gamma = mc.options.gamma().get().floatValue();
        float darkenWorldAmount = renderer.getDarkenWorldAmount(partialTicks);
        float ambientLight = level.dimensionType().ambientLight();
        boolean forceBrightLightmap = level.effects().constantAmbientLight();

        // Compute 16x16 lightmap
        for (int skyLight = 0; skyLight < SIZE; skyLight++) {
            float skyBrightness = getBrightness(ambientLight, skyLight) * skyFlashTime;

            for (int blockLight = 0; blockLight < SIZE; blockLight++) {
                float blockBrightness = getBrightness(ambientLight, blockLight) * blockRedFlicker;
                float blockG = blockBrightness * ((blockBrightness * 0.6f + 0.4f) * 0.6f + 0.4f);
                float blockB = blockBrightness * (blockBrightness * blockBrightness * 0.6f + 0.4f);
                lightColor.set(blockBrightness, blockG, blockB);

                if (forceBrightLightmap) {
                    lightColor.lerp(tempVec.set(0.99f, 1.12f, 1.0f), 0.25f);
                    clampColor(lightColor);
                } else {
                    tempVec.set(skyLightColor).mul(skyBrightness);
                    lightColor.add(tempVec);

                    lightColor.lerp(tempVec.set(0.75f, 0.75f, 0.75f), 0.04f);

                    if (darkenWorldAmount > 0.0f) {
                        tempVec.set(lightColor).mul(0.7f, 0.6f, 0.6f);
                        lightColor.lerp(tempVec, darkenWorldAmount);
                    }
                }

                // Night vision
                if (nightVisionFactor > 0.0f) {
                    float maxComponent = Math.max(lightColor.x(), Math.max(lightColor.y(), lightColor.z()));
                    if (maxComponent < 1.0f) {
                        float scale = 1.0f / maxComponent;
                        tempVec.set(lightColor).mul(scale);
                        lightColor.lerp(tempVec, nightVisionFactor);
                    }
                }

                // Darkness effect
                if (!forceBrightLightmap) {
                    lightColor.add(-darknessScale, -darknessScale, -darknessScale);
                    clampColor(lightColor);
                }

                // Gamma correction
                tempVec.set(gammaCorrect(lightColor.x), gammaCorrect(lightColor.y), gammaCorrect(lightColor.z));
                lightColor.lerp(tempVec, Math.max(0.0f, gamma - darknessGamma));

                lightColor.lerp(tempVec.set(0.75f, 0.75f, 0.75f), 0.04f);
                clampColor(lightColor);
                lightColor.mul(255.0f);

                int r = (int) lightColor.x();
                int g = (int) lightColor.y();
                int b = (int) lightColor.z();

                // ABGR format (NativeImage/BGRA convention) into R8G8B8A8_UNORM texture
                // We write RGBA directly since we control the swizzle in the shader
                int pixel = 0xFF000000 | (b << 16) | (g << 8) | r;

                int offset = (skyLight * SIZE + blockLight) * 4;
                pixelBuffer.putInt(offset, pixel);
            }
        }

        uploadToGpu();
    }

    private void uploadToGpu() {
        pixelBuffer.rewind();

        lightmapImage.uploadSubTextureAsync(
                0, // mipLevel
                SIZE, SIZE, // width, height
                0, 0, // xOffset, yOffset
                0, 0, // unpackSkipRows, unpackSkipPixels
                SIZE, // unpackRowLength
                pixelBuffer);

        SpriteUpdateUtil.addTransitionedLayout(lightmapImage);
        ImageUploadHelper.INSTANCE.submitCommands();
    }

    public VulkanImage getVulkanImage() {
        if (!initialized) {
            init();
        }
        return lightmapImage;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void cleanup() {
        if (lightmapImage != null) {
            lightmapImage.free();
            lightmapImage = null;
        }
        if (pixelBuffer != null) {
            MemoryUtil.memFree(pixelBuffer);
            pixelBuffer = null;
        }
        initialized = false;
    }

    // --- Utility methods (from VulkanMod's MLightTexture) ---

    private static float getBrightness(float ambientLight, int lightLevel) {
        float f = (float) lightLevel / 15.0f;
        float g = f / (4.0f - 3.0f * f);
        return Mth.lerp(ambientLight, g, 1.0f);
    }

    private static float getDarknessGamma(Minecraft mc, float partialTicks) {
        MobEffectInstance effect = mc.player.getEffect(MobEffects.DARKNESS);
        return effect != null ? effect.getBlendFactor(mc.player, partialTicks) : 0.0f;
    }

    private static float getDarknessScale(LivingEntity entity, float factor, float partialTicks) {
        float h = 0.45f * factor;
        return Math.max(0.0f, Mth.cos(((float) entity.tickCount - partialTicks) * (float) Math.PI * 0.025f) * h);
    }

    private static float getBlockLightRedFlicker(Minecraft mc) {
        // Access the flickerIntensity from LightTexture
        // This is field_21528 (intermediary) / blockLightRedFlicker
        // We approximate: the flicker is minor (±0.1), default baseline is 0
        try {
            return mc.gameRenderer.lightTexture().getBlockLightFlicker();
        } catch (Exception e) {
            return 0.0f;
        }
    }

    private static float gammaCorrect(float f) {
        float g = 1.0f - f;
        g = g * g;
        return 1.0f - g * g;
    }

    private static void clampColor(Vector3f v) {
        v.set(
                Mth.clamp(v.x, 0.0f, 1.0f),
                Mth.clamp(v.y, 0.0f, 1.0f),
                Mth.clamp(v.z, 0.0f, 1.0f));
    }
}
