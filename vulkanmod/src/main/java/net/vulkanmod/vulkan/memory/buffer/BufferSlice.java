package net.vulkanmod.vulkan.memory.buffer;

public class BufferSlice {
    Buffer buffer;
    int offset;
    int size;

    public void set(Buffer buffer, int offset, int size) {
        this.buffer = buffer;
        this.offset = offset;
        this.size = size;
    }

    public Buffer getBuffer() {
        return buffer;
    }

    public int getOffset() {
        return offset;
    }

    public int getSize() {
        return size;
    }
}
