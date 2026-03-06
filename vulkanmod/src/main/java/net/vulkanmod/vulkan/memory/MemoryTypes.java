package net.vulkanmod.vulkan.memory;

import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.buffer.Buffer;
import net.vulkanmod.vulkan.memory.buffer.StagingBuffer;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkMemoryHeap;
import org.lwjgl.vulkan.VkMemoryType;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.*;

public class MemoryTypes {
    public static MemoryType GPU_MEM;
    public static MemoryType HOST_MEM;

    public static void createMemoryTypes() {

        for (int i = 0; i < DeviceManager.memoryProperties.memoryTypeCount(); ++i) {
            VkMemoryType memoryType = DeviceManager.memoryProperties.memoryTypes(i);
            VkMemoryHeap heap = DeviceManager.memoryProperties.memoryHeaps(memoryType.heapIndex());
            int propertyFlags = memoryType.propertyFlags();

            if (propertyFlags == VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) {
                GPU_MEM = new DeviceLocalMemory(memoryType, heap);
            }

            if (propertyFlags == (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
                HOST_MEM = new HostCoherentMemory(memoryType, heap);
            }
        }

        if (GPU_MEM != null && HOST_MEM != null)
            return;

        // Could not find 1 or more MemoryTypes, need to use fallback
        for (int i = 0; i < DeviceManager.memoryProperties.memoryTypeCount(); ++i) {
            VkMemoryType memoryType = DeviceManager.memoryProperties.memoryTypes(i);
            VkMemoryHeap heap = DeviceManager.memoryProperties.memoryHeaps(memoryType.heapIndex());

            // GPU mappable memory
            if ((memoryType.propertyFlags() & (VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) == (VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) {
                GPU_MEM = new DeviceMappableMemory(memoryType, heap);
            }

            if ((memoryType.propertyFlags() & (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) == (VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)) {
                HOST_MEM = new HostLocalFallbackMemory(memoryType, heap);
            }

            if (GPU_MEM != null && HOST_MEM != null)
                return;
        }

        // Could not find device memory, fallback to host memory
        GPU_MEM = HOST_MEM;
    }

    public static class DeviceLocalMemory extends MemoryType {

        DeviceLocalMemory(VkMemoryType vkMemoryType, VkMemoryHeap vkMemoryHeap) {
            super(Type.DEVICE_LOCAL, vkMemoryType, vkMemoryHeap);
        }

        @Override
        public void createBuffer(Buffer buffer, long size) {
            MemoryManager.getInstance().createBuffer(buffer, size,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | buffer.usage,
                    VK_MEMORY_HEAP_DEVICE_LOCAL_BIT);
        }

        @Override
        public void copyToBuffer(Buffer buffer, ByteBuffer src, long size, long srcOffset, long dstOffset) {
            StagingBuffer stagingBuffer = Vulkan.getStagingBuffer();
            stagingBuffer.copyBuffer((int) size, src);

            DeviceManager.getTransferQueue().copyBufferCmd(stagingBuffer.getId(), stagingBuffer.getOffset(), buffer.getId(), dstOffset, size);
        }

        @Override
        public void copyFromBuffer(Buffer buffer, long bufferSize, ByteBuffer byteBuffer) {
            // TODO
        }

        public long copyBuffer(Buffer src, Buffer dst) {
            if (dst.getBufferSize() < src.getBufferSize()) {
                throw new IllegalArgumentException("dst size is less than src size.");
            }

            return DeviceManager.getTransferQueue().copyBufferCmd(src.getId(), 0, dst.getId(), 0, src.getBufferSize());
        }

        @Override
        public boolean mappable() {
            return false;
        }
    }

    static abstract class MappableMemory extends MemoryType {

        MappableMemory(Type type, VkMemoryType vkMemoryType, VkMemoryHeap vkMemoryHeap) {
            super(type, vkMemoryType, vkMemoryHeap);
        }

        @Override
        public void copyToBuffer(Buffer buffer, ByteBuffer src, long size, long srcOffset, long dstOffset) {
            VUtil.memcpy(src, buffer, size, srcOffset, dstOffset);
        }

        @Override
        public void copyFromBuffer(Buffer buffer, long size, ByteBuffer byteBuffer) {
            MemoryUtil.memCopy(buffer.getDataPtr(), MemoryUtil.memAddress(byteBuffer), size);
            VUtil.memcpy(buffer, byteBuffer, size);
        }

        @Override
        public boolean mappable() {
            return true;
        }
    }

    static class HostCoherentMemory extends MappableMemory {

        HostCoherentMemory(VkMemoryType vkMemoryType, VkMemoryHeap vkMemoryHeap) {
            super(Type.HOST_LOCAL, vkMemoryType, vkMemoryHeap);
        }

        @Override
        public void createBuffer(Buffer buffer, long size) {
            MemoryManager.getInstance().createBuffer(buffer, size,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | buffer.usage,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        }

    }

    static class HostLocalFallbackMemory extends MappableMemory {

        HostLocalFallbackMemory(VkMemoryType vkMemoryType, VkMemoryHeap vkMemoryHeap) {
            super(Type.HOST_LOCAL, vkMemoryType, vkMemoryHeap);
        }

        @Override
        public void createBuffer(Buffer buffer, long size) {
            MemoryManager.getInstance().createBuffer(buffer, size,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | buffer.usage,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        }
    }

    static class DeviceMappableMemory extends MappableMemory {

        DeviceMappableMemory(VkMemoryType vkMemoryType, VkMemoryHeap vkMemoryHeap) {
            super(Type.DEVICE_LOCAL, vkMemoryType, vkMemoryHeap);
        }

        @Override
        public void createBuffer(Buffer buffer, long size) {
            MemoryManager.getInstance().createBuffer(buffer, size,
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT | buffer.usage,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT);
        }
    }
}
