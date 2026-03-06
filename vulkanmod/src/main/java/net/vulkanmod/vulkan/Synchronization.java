package net.vulkanmod.vulkan;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.queue.CommandPool;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDevice;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/***
 * Synchronization utility to sync in frame ops that need to be completed before executing main cmd buffer.
 */
public class Synchronization {
    private static final int ALLOCATION_SIZE = 50;

    public static final Synchronization INSTANCE = new Synchronization(ALLOCATION_SIZE);

    private final LongBuffer fences;
    private int idx = 0;

    private final ObjectArrayList<CommandPool.CommandBuffer> fenceCbs = new ObjectArrayList<>();

    private final LongArrayList semaphores = new LongArrayList();
    private final ObjectArrayList<CommandPool.CommandBuffer> semaphoreCbs = new ObjectArrayList<>();

    Synchronization(int allocSize) {
        this.fences = MemoryUtil.memAllocLong(allocSize);
    }

    public void addCommandBuffer(CommandPool.CommandBuffer commandBuffer) {
        addCommandBuffer(commandBuffer, false);
    }

    public synchronized void addCommandBuffer(CommandPool.CommandBuffer commandBuffer, boolean useSemaphore) {
        if (!useSemaphore) {
            this.addFence(commandBuffer.getFence());
            this.fenceCbs.add(commandBuffer);
        }
        else {
            this.semaphores.add(commandBuffer.getSemaphore());
            this.semaphoreCbs.add(commandBuffer);
        }
    }

    public synchronized void addFence(long fence) {
        if (idx == ALLOCATION_SIZE)
            waitFences();

        fences.put(idx, fence);
        idx++;
    }

    public synchronized void waitFences() {
        if (idx == 0)
            return;

        VkDevice device = Vulkan.getVkDevice();

        fences.limit(idx);

        vkWaitForFences(device, fences, true, VUtil.UINT64_MAX);

        this.fenceCbs.forEach(CommandPool.CommandBuffer::reset);
        this.fenceCbs.clear();

        fences.limit(ALLOCATION_SIZE);
        idx = 0;
    }

    public synchronized void addWaitSemaphore(long semaphore) {
        this.semaphores.add(semaphore);
    }

    public LongBuffer getWaitSemaphores(MemoryStack stack) {
        var buffer = stack.mallocLong(this.semaphores.size())
                          .put(this.semaphores.elements(), 0, this.semaphores.size());
        buffer.flip();

        this.semaphores.clear();
        return buffer;
    }

    public void scheduleCbReset() {
        final var frameSemaphoreCbs = this.semaphoreCbs.clone();
        MemoryManager.getInstance().addFrameOp(
                () -> {
                    frameSemaphoreCbs.forEach(CommandPool.CommandBuffer::reset);
                }
        );

        this.semaphoreCbs.clear();
    }

    public static void waitFence(long fence) {
        VkDevice device = Vulkan.getVkDevice();

        vkWaitForFences(device, fence, true, VUtil.UINT64_MAX);
    }

    public static boolean checkFenceStatus(long fence) {
        VkDevice device = Vulkan.getVkDevice();
        return vkGetFenceStatus(device, fence) == VK_SUCCESS;
    }

}
