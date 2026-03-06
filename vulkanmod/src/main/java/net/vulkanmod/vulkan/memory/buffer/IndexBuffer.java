package net.vulkanmod.vulkan.memory.buffer;

import net.vulkanmod.vulkan.memory.MemoryType;

import static org.lwjgl.vulkan.VK10.*;

public class IndexBuffer extends Buffer {

    public IndexType indexType;

    public IndexBuffer(int size, MemoryType type) {
        this(size, type, IndexType.UINT16);
    }

    public IndexBuffer(int size, MemoryType type, IndexType indexType) {
        super(VK_BUFFER_USAGE_INDEX_BUFFER_BIT, type);
        this.indexType = indexType;

        this.createBuffer(size);
    }

    public enum IndexType {
        UINT16(2, VK_INDEX_TYPE_UINT16),
        UINT32(4, VK_INDEX_TYPE_UINT32);

        public final int size;
        public final int value;

        IndexType(int size, int value) {
            this.size = size;
            this.value = value;
        }
    }


}
