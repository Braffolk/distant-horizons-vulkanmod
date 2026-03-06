package net.vulkanmod.vulkan.util;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.TextureFormat;
import net.minecraft.util.ARGB;
import net.vulkanmod.render.engine.VkGpuTexture;
import net.vulkanmod.vulkan.Renderer;
import org.lwjgl.vulkan.VK10;

import java.util.function.Consumer;

public abstract class ScreenshotUtil {

    public static void takeScreenshot(RenderTarget renderTarget, int mipLevel, Consumer<NativeImage> consumer) {
        int width = renderTarget.width;
        int height = renderTarget.height;
        GpuTexture gpuTexture = renderTarget.getColorTexture();
        if (gpuTexture == null) {
            throw new IllegalStateException("Tried to capture screenshot of an incomplete framebuffer");
        } else {
            // Need to submit and wait cmds if screenshot was requested
            // before the end of the frame
            Renderer.getInstance().flushCmds();

            int pixelSize = TextureFormat.RGBA8.pixelSize();
            GpuBuffer gpuBuffer = RenderSystem.getDevice()
                                              .createBuffer(() -> "Screenshot buffer", 9, width * height * pixelSize);
            CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
            RenderSystem.getDevice().createCommandEncoder().copyTextureToBuffer(gpuTexture, gpuBuffer, 0, () -> {
                try (GpuBuffer.MappedView readView = commandEncoder.mapBuffer(gpuBuffer, true, false)) {
                    NativeImage nativeImage = new NativeImage(width, height, false);

                    var colorAttachment = ((VkGpuTexture) Renderer.getInstance()
                                                                  .getMainPass()
                                                                  .getColorAttachment());
                    boolean isBgraFormat = (colorAttachment.getVulkanImage().format == VK10.VK_FORMAT_B8G8R8A8_UNORM);

                    int size = mipLevel * mipLevel;

                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {

                            if (mipLevel == 1) {
                                int color = readView.data().getInt((x + y * width) * pixelSize);

                                if (isBgraFormat) {
                                    color = ColorUtil.BGRAtoRGBA(color);
                                }

                                nativeImage.setPixelABGR(x, y, color | 0xFF000000);
                            } else {
                                int red = 0;
                                int green = 0;
                                int blue = 0;

                                for (int x1 = 0; x1 < mipLevel; x1++) {
                                    for (int y1 = 0; y1 < mipLevel; y1++) {
                                        int color = readView.data().getInt(((x + x1) + (y + y1) * width) * pixelSize);

                                        if (isBgraFormat) {
                                            color = ColorUtil.BGRAtoRGBA(color);
                                        }

                                        red += ARGB.red(color);
                                        green += ARGB.green(color);
                                        blue += ARGB.blue(color);
                                    }
                                }

                                nativeImage.setPixelABGR(x, y, ARGB.color(255, red / size, green / size, blue / size));
                            }
                        }
                    }

                    consumer.accept(nativeImage);
                }

                gpuBuffer.close();
            }, 0);
        }
    }
}
