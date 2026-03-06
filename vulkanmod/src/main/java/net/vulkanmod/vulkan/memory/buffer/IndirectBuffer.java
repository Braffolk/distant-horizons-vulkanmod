package net.vulkanmod.vulkan.memory.buffer;

import net.vulkanmod.vulkan.Synchronization;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryType;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.queue.TransferQueue;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;

public class IndirectBuffer extends Buffer {
    CommandPool.CommandBuffer commandBuffer;

    public IndirectBuffer(int size, MemoryType type) {
        super(VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, type);
        this.createBuffer(size);
    }

    public void recordCopyCmd(ByteBuffer byteBuffer) {
        int size = byteBuffer.remaining();

        if (size > this.bufferSize - this.usedBytes) {
            resizeBuffer((long) (this.bufferSize * 1.5f));
            this.usedBytes = 0;
        }

        if (this.type.mappable()) {
            this.type.copyToBuffer(this, byteBuffer, size, 0, this.usedBytes);
        } else {
            if (commandBuffer == null)
                commandBuffer = DeviceManager.getTransferQueue().beginCommands();

            StagingBuffer stagingBuffer = Vulkan.getStagingBuffer();
            stagingBuffer.copyBuffer(size, byteBuffer);

            TransferQueue.uploadBufferCmd(commandBuffer.getHandle(), stagingBuffer.id, stagingBuffer.offset, this.getId(), this.getUsedBytes(), size);
        }

        offset = usedBytes;
        usedBytes += size;
    }

    public void submitUploads() {
        if (commandBuffer == null)
            return;

        DeviceManager.getTransferQueue().submitCommands(commandBuffer);
        Synchronization.INSTANCE.addCommandBuffer(commandBuffer);
        commandBuffer = null;
    }

}
