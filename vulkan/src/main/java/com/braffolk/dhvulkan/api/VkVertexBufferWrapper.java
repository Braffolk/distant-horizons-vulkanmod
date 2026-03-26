package com.braffolk.dhvulkan.api;

import com.braffolk.dhvulkan.core.VulkanBackend;
import com.braffolk.dhvulkan.core.data.VkVertexData;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Vulkan implementation of DH 3.0's VBO wrapper.
 * DH creates one of these per LOD section via the factory method
 * and calls upload() to push vertex data. We store the data as
 * VkVertexData which our VulkanBackend can draw.
 */
public class VkVertexBufferWrapper implements IVertexBufferWrapper {

    private final VulkanBackend backend;
    private VkVertexData vertexData;
    private int indexCount;
    private static int nextId = 0;
    private final int id;

    /** Our owned copy of the vertex buffer — allocated via MemoryUtil, must be explicitly freed */
    private ByteBuffer ownedBuffer;

    public VkVertexBufferWrapper(VulkanBackend backend) {
        this.backend = backend;
        this.id = nextId++;
    }

    @Override
    public void upload(ByteBuffer buffer, int vertexCount) {
        uploadVertexBufferImpl(buffer, vertexCount);
    }

    // DH 3.0 renamed upload() to uploadVertexBuffer() and added uploadIndexBuffer()
    // Both must exist for cross-version compatibility
    public void uploadVertexBuffer(ByteBuffer buffer, int vertexCount) {
        uploadVertexBufferImpl(buffer, vertexCount);
    }

    public void uploadIndexBuffer(ByteBuffer buffer, int vertexCount) {
        // No-op — we use a shared IBO (useSingleIbo() = true in VkRenderApiDefinition)
    }

    private void uploadVertexBufferImpl(ByteBuffer buffer, int vertexCount) {
        if (this.vertexData == null) {
            this.vertexData = new VkVertexData(id);
        }

        // Free any previously owned buffer before allocating a new one
        if (this.ownedBuffer != null) {
            MemoryUtil.memFree(this.ownedBuffer);
            this.ownedBuffer = null;
        }

        // DH 3.0 frees the source ByteBuffer immediately after upload() returns.
        // Copy data using LWJGL's MemoryUtil (raw malloc — much faster than ByteBuffer.allocateDirect
        // which involves JVM cleaner registration, synchronization, and slow GC finalization).
        int size = buffer.remaining();
        this.ownedBuffer = MemoryUtil.memAlloc(size);
        int oldPos = buffer.position();
        this.ownedBuffer.put(buffer);
        buffer.position(oldPos);
        this.ownedBuffer.flip();

        this.vertexData.setData(this.ownedBuffer, System.identityHashCode(this.ownedBuffer));

        // Register cleanup: when drawVertexData() uploads to GPU and calls clearData(),
        // free our native buffer immediately instead of waiting for close()
        this.vertexData.setOnClear(() -> {
            if (this.ownedBuffer != null) {
                MemoryUtil.memFree(this.ownedBuffer);
                this.ownedBuffer = null;
            }
        });

        // DH 3.0 has BYTES_PER_VERTEX = 14 but actual vertex layout is 16 bytes
        // (the putVertex writes short padding at the end which isn't counted).
        // Don't trust DH's vertexCount — compute from actual data size / stride.
        int actualStride = 16; // 3×short + 1×short + 4×ubyte + 2×ubyte + 1×short(pad) = 16
        this.indexCount = size / actualStride;
    }

    @Override
    public void close() {
        if (this.vertexData != null) {
            this.vertexData.setOnClear(null); // Break reference chain
            backend.queueDataFree(this.vertexData);
            this.vertexData = null;
        }
        if (this.ownedBuffer != null) {
            MemoryUtil.memFree(this.ownedBuffer);
            this.ownedBuffer = null;
        }
    }

    VkVertexData getVertexData() {
        return this.vertexData;
    }

    int getIndexCount() {
        return this.indexCount;
    }
}
