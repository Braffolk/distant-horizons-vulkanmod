package net.vulkanmod.render.engine;

import com.mojang.blaze3d.buffers.GpuBuffer;

import java.nio.ByteBuffer;
import java.util.function.Supplier;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryType;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import org.jetbrains.annotations.Nullable;

import static org.lwjgl.vulkan.VK10.*;

@Environment(EnvType.CLIENT)
public class VkGpuBuffer extends GpuBuffer {
    protected boolean closed;
    @Nullable protected final Supplier<String> label;

    Buffer buffer;

    protected VkGpuBuffer(VkDebugLabel glDebugLabel, @Nullable Supplier<String> supplier, int usage, int size) {
        super(usage, size);
        this.label = supplier;

        int vkUsage = 0;
        if ((usage & GpuBuffer.USAGE_COPY_SRC) != 0) {
            vkUsage |= VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        }
        if ((usage & GpuBuffer.USAGE_COPY_DST) != 0) {
            vkUsage |= VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        }
        if ((usage & GpuBuffer.USAGE_VERTEX) != 0) {
            vkUsage |= VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
        }
        if ((usage & GpuBuffer.USAGE_INDEX) != 0) {
            vkUsage |= VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
        }
        if ((usage & GpuBuffer.USAGE_UNIFORM) != 0) {
            vkUsage |= VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
        }
        if ((usage & GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER) != 0) {
            vkUsage |= VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT;
        }

        boolean mappable = (usage & GpuBuffer.USAGE_MAP_READ) != 0 |
                           (usage & GpuBuffer.USAGE_MAP_WRITE) != 0 |
                           (usage & GpuBuffer.USAGE_HINT_CLIENT_STORAGE) != 0;

        MemoryType memoryType =  mappable ? MemoryTypes.HOST_MEM : MemoryTypes.GPU_MEM;

        this.buffer = new Buffer(vkUsage, memoryType);
        this.buffer.createBuffer(this.size());
    }

    @Override
    public boolean isClosed() {
        return this.closed;
    }

    @Override
    public void close() {
        if (!this.closed) {
            this.closed = true;

            MemoryManager.getInstance().addToFreeable(this.buffer);
        }
    }

    public Buffer getBuffer() {
        return buffer;
    }

    public static int bufferUsageToGlEnum(int i) {
        boolean stream = (i & 4) != 0;
        // Draw
        if ((i & 2) != 0) {
            return stream ? 35040 : 35044;
        }
        // Read
        else if ((i & 1) != 0) {
            return stream ? 35041 : 35045;
        } else {
            return 35044;
        }
    }

    @Environment(EnvType.CLIENT)
    public static class MappedView implements GpuBuffer.MappedView {
        private final int target;
        private final ByteBuffer data;

        protected MappedView(int i, ByteBuffer byteBuffer) {
            this.target = i;
            this.data = byteBuffer;
        }

        @Override
        public ByteBuffer data() {
            return this.data;
        }

        @Override
        public void close() {
//            GlStateManager._glUnmapBuffer(this.target);
        }
    }
}

