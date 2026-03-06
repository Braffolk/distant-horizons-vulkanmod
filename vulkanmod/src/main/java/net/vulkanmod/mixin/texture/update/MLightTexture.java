package net.vulkanmod.mixin.texture.update;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.Mth;
import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.vulkanmod.gl.VkGlTexture;
import net.vulkanmod.mixin.texture.image.NativeImageAccessor;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.render.texture.ImageUploadHelper;
import net.vulkanmod.vulkan.queue.CommandPool;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LightTexture.class)
public class MLightTexture {
    @Unique private static final Vector3f END_FLASH_SKY_LIGHT_COLOR = new Vector3f(0.9F, 0.5F, 1.0F);

    @Shadow @Final private Minecraft minecraft;
    @Shadow @Final private GameRenderer renderer;

    @Shadow private boolean updateLightTexture;
    @Shadow private float blockLightRedFlicker;

    @Unique private DynamicTexture lightTexture;
    @Unique private GpuTextureView textureView;
    @Unique private NativeImage lightPixels;

    private Vector3f[] tempVecs;

//    @Inject(method = "<init>", at = @At("RETURN"))
//    private void onInit(GameRenderer gameRenderer, Minecraft minecraft, CallbackInfo ci) {
//        initLightMap();
//
//        this.tempVecs = new Vector3f[]{new Vector3f(), new Vector3f(), new Vector3f()};
//    }
//
//    private void initLightMap() {
//        GpuDevice gpuDevice = RenderSystem.getDevice();
//        this.lightTexture = new DynamicTexture("Light Texture", 16, 16, false);
//        this.textureView = gpuDevice.createTextureView(this.lightTexture.getTexture());
//        this.lightPixels = this.lightTexture.getPixels();
//
//        for(int i = 0; i < 16; ++i) {
//            for(int j = 0; j < 16; ++j) {
//                this.lightPixels.setPixel(j, i, 0xFFFFFFFF);
//            }
//        }
//
//        this.lightTexture.upload();
//    }
//
//    /**
//     * @author
//     * @reason
//     */
//    @Overwrite
//    public void turnOnLightLayer() {
//        RenderSystem.setShaderTexture(2, this.textureView);
//    }

    // TODO
//    @SuppressWarnings("UnreachableCode")
//    @Inject(method = "updateLightTexture", at = @At("HEAD"), cancellable = true)
//    public void updateLightTexture(float partialTicks, CallbackInfo ci) {
//        if (this.updateLightTexture) {
//            this.updateLightTexture = false;
//
//            ProfilerFiller profilerFiller = Profiler.get();
//            profilerFiller.push("lightTex");
//
//            // TODO: Other mods might be changing lightmap behaviour, we can't be aware of that here
//
//            ClientLevel clientLevel = this.minecraft.level;
//            if (clientLevel != null) {
//                float skyDarken = clientLevel.getSkyDarken(1.0F);
//                float skyFlashTime;
//                if (clientLevel.getSkyFlashTime() > 0) {
//                    skyFlashTime = 1.0F;
//                } else {
//                    skyFlashTime = skyDarken * 0.95F + 0.05F;
//                }
//
//                float darknessEffectScale = this.minecraft.options.darknessEffectScale().get().floatValue();
//                float darknessGamma = this.getDarknessGamma(partialTicks) * darknessEffectScale;
//                float darknessScale = this.calculateDarknessScale(this.minecraft.player, darknessGamma, partialTicks) * darknessEffectScale;
//                float waterVision = this.minecraft.player.getWaterVision();
//                float nightVisionFactor;
//                if (this.minecraft.player.hasEffect(MobEffects.NIGHT_VISION)) {
//                    nightVisionFactor = GameRenderer.getNightVisionScale(this.minecraft.player, partialTicks);
//                } else if (waterVision > 0.0F && this.minecraft.player.hasEffect(MobEffects.CONDUIT_POWER)) {
//                    nightVisionFactor = waterVision;
//                } else {
//                    nightVisionFactor = 0.0F;
//                }
//
////                Vector3f skyLightColor = new Vector3f(skyDarken, skyDarken, 1.0F).lerp(new Vector3f(1.0F, 1.0F, 1.0F), 0.35F);
//                skyDarken = lerp(skyDarken, 1.0f, 0.35f);
//                Vector3f skyLightColor = this.tempVecs[0].set(skyDarken, skyDarken, 1.0F);
//                float redFlicker = this.blockLightRedFlicker + 1.5F;
//                Vector3f lightColor = this.tempVecs[1];
//
//                float gamma = this.minecraft.options.gamma().get().floatValue();
//                float darkenWorldAmount = this.renderer.getDarkenWorldAmount(partialTicks);
////                boolean forceBrightLightmap = clientLevel.effects().forceBrightLightmap();
//                float ambientLight = clientLevel.dimensionType().ambientLight();
//
//                Vector3f vector3f2;
//                if (clientLevel.effects().hasEndFlashes()) {
//                    vector3f2 = END_FLASH_SKY_LIGHT_COLOR;
//                } else {
//                    vector3f2 = (new Vector3f(g, g, 1.0F)).lerp(new Vector3f(1.0F, 1.0F, 1.0F), 0.35F);
//                }
//
//                float n = this.blockLightRedFlicker + 1.5F;
//                float o = clientLevel.dimensionType().ambientLight();
//                float p = ((Double)this.minecraft.options.gamma().get()).floatValue();
//                CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
//
//                long ptr = ((NativeImageAccessor)(Object)this.lightPixels).getPixels();
//                int width = this.lightPixels.getWidth();
//
//                Vector3f tVec3f = this.tempVecs[2];
//
//                for(int y = 0; y < 16; ++y) {
//                    float brY = getBrightness(ambientLight, y) * skyFlashTime;
//
//                    for(int x = 0; x < 16; ++x) {
//                        float brX = getBrightness(ambientLight, x) * redFlicker;
//                        float t = brX * ((brX * 0.6F + 0.4F) * 0.6F + 0.4F);
//                        float u = brX * (brX * brX * 0.6F + 0.4F);
//                        lightColor.set(brX, t, u);
//
//                        if (forceBrightLightmap) {
//                            lightColor.lerp(tVec3f.set(0.99F, 1.12F, 1.0F), 0.25F);
//                            clampColor(lightColor);
//                        } else {
//                            tVec3f.set(skyLightColor).mul(brY);
//                            lightColor.add(tVec3f);
//
//                            tVec3f.set(0.75F, 0.75F, 0.75F);
//                            lightColor.lerp(tVec3f, 0.04F);
//
//                            if (darkenWorldAmount > 0.0F) {
//                                tVec3f.set(lightColor).mul(0.7F, 0.6F, 0.6F);
//                                lightColor.lerp(tVec3f, darkenWorldAmount);
//                            }
//                        }
//
//                        if (nightVisionFactor > 0.0F) {
//                            // scale up uniformly until 1.0 is hit by one of the colors
//                            float maxComponent = Math.max(lightColor.x(), Math.max(lightColor.y(), lightColor.z()));
//                            if (maxComponent < 1.0F) {
//                                float brightColor = 1.0F / maxComponent;
//                                tVec3f.set(lightColor).mul(brightColor);
//                                lightColor.lerp(tVec3f, nightVisionFactor);
//                            }
//                        }
//
//                        if (!forceBrightLightmap) {
//                            lightColor.add(-darknessScale, -darknessScale, -darknessScale);
//                            clampColor(lightColor);
//                        }
//
//                        tVec3f.set(this.notGamma(lightColor.x), this.notGamma(lightColor.y), this.notGamma(lightColor.z));
//                        lightColor.lerp(tVec3f, Math.max(0.0F, gamma - darknessGamma));
//
//                        lightColor.lerp(tVec3f.set(0.75F, 0.75F, 0.75F), 0.04F);
//                        clampColor(lightColor);
//
//                        lightColor.mul(255.0F);
//                        int r = (int)lightColor.x();
//                        int g = (int)lightColor.y();
//                        int b = (int)lightColor.z();
//
//                        MemoryUtil.memPutInt(ptr + (((long) y * width + x) * 4L), 0xFF000000 | b << 16 | g << 8 | r);
//                    }
//                }
//
//                CommandPool.CommandBuffer commandBuffer = ImageUploadHelper.INSTANCE.getOrStartCommandBuffer();
//                this.lightTexture.upload();
//
//                try (MemoryStack stack = MemoryStack.stackPush()) {
//                    VkGlTexture.getTexture(((VkGpuTexture)this.lightTexture.getTexture()).glId()).getVulkanImage().readOnlyLayout(stack, commandBuffer.getHandle());
//                }
//
//                profilerFiller.pop();
//            }
//        }
//
//        ci.cancel();
//    }

    @Unique
    private float getDarknessGamma(float f) {
        MobEffectInstance mobEffectInstance = this.minecraft.player.getEffect(MobEffects.DARKNESS);
        return mobEffectInstance != null ? mobEffectInstance.getBlendFactor(this.minecraft.player, f) : 0.0F;
    }

    @Unique
    private float calculateDarknessScale(LivingEntity livingEntity, float f, float g) {
        float h = 0.45F * f;
        return Math.max(0.0F, Mth.cos(((float)livingEntity.tickCount - g) * (float) Math.PI * 0.025F) * h);
    }

    @Unique
    private static float lerp(float a, float x, float t) {
        return (x - a) * t + a;
    }

    @Unique
    private static void clampColor(Vector3f vector3f) {
        vector3f.set(Mth.clamp(vector3f.x, 0.0F, 1.0F), Mth.clamp(vector3f.y, 0.0F, 1.0F), Mth.clamp(vector3f.z, 0.0F, 1.0F));
    }

    @Unique
    private float notGamma(float f) {
        float g = 1.0F - f;
        g = g * g;
        return 1.0F - g * g;
    }

    @Unique
    private static float getBrightness(float ambientLight, int i) {
        float f = (float)i / 15.0F;
        float g = f / (4.0F - 3.0F * f);
        return Mth.lerp(ambientLight, g, 1.0F);
    }

}
