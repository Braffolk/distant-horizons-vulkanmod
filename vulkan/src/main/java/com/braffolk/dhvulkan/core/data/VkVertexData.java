package com.braffolk.dhvulkan.core.data;

import java.nio.ByteBuffer;

/**
 * Wrapper for raw vertex data used by the Vulkan rendering engine.
 * Replaces the duck-interfaced GLVertexBuffer for DH-agnostic VBO handling.
 *
 * Each instance represents a single VBO's data. The integration layer
 * (dh24 or api) is responsible for creating these from DH's buffer types.
 */
public class VkVertexData {
    /** Unique identity for cache keying (e.g. System.identityHashCode of the source VBO) */
    public final int id;

    /**
     * Raw vertex data, or null if the source VBO has been destroyed.
     * When non-null, used to upload to GPU on first draw.
     */
    public volatile ByteBuffer vertexBuffer;

    /** Number of bytes in the vertex data */
    public volatile int byteSize;

    /**
     * Identity hash of the current vertex data handle.
     * Changes when DH re-uploads terrain data (LOD transition).
     * Used by the cache to detect invalidation.
     */
    public volatile int handleIdentity;

    public VkVertexData(int id) {
        this.id = id;
    }

    public void setData(ByteBuffer buffer, int handleIdentity) {
        this.vertexBuffer = buffer;
        this.byteSize = buffer != null ? buffer.remaining() : 0;
        this.handleIdentity = handleIdentity;
    }

    public void clearData() {
        this.vertexBuffer = null;
        this.byteSize = 0;
        this.handleIdentity = 0;
    }
}
