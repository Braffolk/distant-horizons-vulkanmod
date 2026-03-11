package com.braffolk.dhvulkan.dh24.mixin;

import com.braffolk.dhvulkan.compat.Compat;
import com.braffolk.dhvulkan.dh24.duck.IVulkanVertexBuffer;
import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.render.glObject.buffer.GLVertexBuffer;
import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;
import java.util.ArrayList;

/**
 * Mixin into {@link LodBufferContainer} to intercept buffer uploads at the
 * same level as the fork's Vulkan path.
 *
 * The fork modified uploadBuffersDirect() to have a separate Vulkan code path
 * that bypasses vbo.uploadBuffer() entirely and stores raw ByteBuffer data
 * directly on the VBO. This mixin replicates that approach cleanly.
 */
@Mixin(value = LodBufferContainer.class, remap = false)
public class MixinLodBufferContainer {

    @Inject(method = "uploadBuffersDirect", at = @At("HEAD"), cancellable = true)
    private static void dhvulkan$vulkanUpload(GLVertexBuffer[] vbos,
            ArrayList<ByteBuffer> byteBuffers,
            EDhApiGpuUploadMethod uploadMethod,
            CallbackInfo ci) {

        if (!Compat.isVulkanModActive()) {
            return; // let the original GL path run
        }

        // DH vertex format: USHORT×3 + pad + light + UBYTE×4 + material + normal +
        // pad×2 = 16 bytes
        final int byteSize = 16;
        int vboIndex = 0;

        for (int i = 0; i < byteBuffers.size(); i++) {
            if (vboIndex >= vbos.length) {
                throw new RuntimeException("Too many vertex buffers!!");
            }

            // Get or create the VBO wrapper
            if (vbos[vboIndex] == null) {
                vbos[vboIndex] = new GLVertexBuffer(false);
            }
            GLVertexBuffer vbo = vbos[vboIndex];

            ByteBuffer buffer = byteBuffers.get(i);
            int size = buffer.limit() - buffer.position();

            try {
                // Copy raw vertex data into a direct ByteBuffer
                ByteBuffer copy = MemoryUtil.memAlloc(size);
                copy.put(buffer.duplicate());
                copy.flip();

                // Free old buffer if present
                IVulkanVertexBuffer vkBuf = (IVulkanVertexBuffer) vbo;
                Object oldHandle = vkBuf.dhvulkan$getVulkanBufferHandle();
                if (oldHandle instanceof ByteBuffer) {
                    MemoryUtil.memFree((ByteBuffer) oldHandle);
                }

                // Store data on the VBO via duck interface
                vkBuf.dhvulkan$setVulkanBufferHandle(copy);
                vkBuf.dhvulkan$setVulkanBufferByteSize(size);

                // Set vertex count the same way the GL path does:
                // vertices = totalBytes / bytesPerVertex
                // indexCount = (vertices / 4) * 6 (4 verts per quad, 6 indices per quad)
                int vertexCount = size / byteSize;
                vbo.setVertexCount((vertexCount / 4) * 6);
            } catch (Exception e) {
                vbos[vboIndex] = null;
                // Don't close — no GL resources to free
            }

            vboIndex++;
        }

        if (vboIndex < vbos.length) {
            throw new RuntimeException("Too few vertex buffers!!");
        }

        ci.cancel(); // Skip the original GL upload path entirely
    }
}
