package net.vulkanmod.vulkan.memory.buffer;

import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryType;

import java.nio.ByteBuffer;

public class Buffer {
    public final MemoryType type;
    public final int usage;

    protected long id;
    protected long allocation;

    protected long bufferSize;
    protected long usedBytes;
    protected long offset;

    protected long dataPtr;

    public Buffer(int usage, MemoryType type) {
        this.usage = usage;
        this.type = type;
    }

    public void createBuffer(long bufferSize) {
        this.type.createBuffer(this, bufferSize);

        if (this.type.mappable()) {
            this.dataPtr = MemoryManager.getInstance().Map(this.allocation).get(0);
        }
    }

    public void resizeBuffer(long newSize) {
        MemoryManager.getInstance().addToFreeable(this);
        this.createBuffer(newSize);
    }

    public void copyBuffer(ByteBuffer byteBuffer, int size) {
        if (size > this.bufferSize - this.usedBytes) {
            resizeBuffer((this.bufferSize + size) * 2);
        }

        this.type.copyToBuffer(this, byteBuffer, size, 0, this.usedBytes);
        this.offset = this.usedBytes;
        this.usedBytes += size;
    }

    public void copyBuffer(ByteBuffer byteBuffer, int size, int dstOffset) {
        if (size > this.bufferSize - dstOffset) {
            resizeBuffer((this.bufferSize + size) * 2);
        }

        this.type.copyToBuffer(this, byteBuffer, size, 0, dstOffset);
        this.offset = dstOffset;
        this.usedBytes = dstOffset + size;
    }

    public void scheduleFree() {
        MemoryManager.getInstance().addToFreeable(this);
    }

    public void reset() {
        usedBytes = 0;
    }

    public long getAllocation() {
        return allocation;
    }

    public long getUsedBytes() {
        return usedBytes;
    }

    public long getOffset() {
        return offset;
    }

    public long getId() {
        return id;
    }

    public long getBufferSize() {
        return bufferSize;
    }

    public long getDataPtr() {
        return dataPtr;
    }

    public void setBufferSize(long size) {
        this.bufferSize = size;
    }

    public void setId(long id) {
        this.id = id;
    }

    public void setAllocation(long allocation) {
        this.allocation = allocation;
    }

    public BufferInfo getBufferInfo() {
        return new BufferInfo(this.id, this.allocation, this.bufferSize, this.type.getType());
    }

    public record BufferInfo(long id, long allocation, long bufferSize, MemoryType.Type type) {
    }
}
