package com.braffolk.dhvulkan.api;

import com.braffolk.dhvulkan.core.VulkanBackend;
import com.braffolk.dhvulkan.core.data.VkVertexData;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.objects.IVertexBufferWrapper;

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

    public VkVertexBufferWrapper(VulkanBackend backend) {
        this.backend = backend;
        this.id = nextId++;
    }

    @Override
    public void upload(ByteBuffer buffer, int vertexCount) {
        if (this.vertexData == null) {
            this.vertexData = new VkVertexData(id);
        }
        // DH 3.0 frees the source ByteBuffer immediately after upload() returns
        // (via MemoryUtil.memFree in LodBufferContainer's finally block).
        // We MUST copy the data here — storing a reference would be a use-after-free.
        int size = buffer.remaining();
        ByteBuffer copy = ByteBuffer.allocateDirect(size);
        copy.order(buffer.order());
        int oldPos = buffer.position();
        copy.put(buffer);
        buffer.position(oldPos);
        copy.flip();

        this.vertexData.setData(copy, System.identityHashCode(copy));
        this.indexCount = vertexCount;
    }

    @Override
    public void close() {
        if (this.vertexData != null) {
            backend.queueDataFree(this.vertexData);
            this.vertexData = null;
        }
    }

    VkVertexData getVertexData() {
        return this.vertexData;
    }

    int getIndexCount() {
        return this.indexCount;
    }
}
