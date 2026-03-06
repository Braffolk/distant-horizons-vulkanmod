package net.vulkanmod.vulkan.queue;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.device.DeviceManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkQueue;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import java.nio.IntBuffer;
import java.util.stream.IntStream;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSurface.vkGetPhysicalDeviceSurfaceSupportKHR;
import static org.lwjgl.vulkan.VK10.*;

public abstract class Queue {
    private static VkDevice device;
    private static QueueFamilyIndices queueFamilyIndices;

    private final VkQueue vkQueue;

    protected CommandPool commandPool;

    public synchronized CommandPool.CommandBuffer beginCommands() {
        try (MemoryStack stack = stackPush()) {
            CommandPool.CommandBuffer commandBuffer = this.commandPool.getCommandBuffer(stack);
            commandBuffer.begin(stack);

            return commandBuffer;
        }
    }

    Queue(MemoryStack stack, int familyIndex) {
        this(stack, familyIndex, true);
    }

    Queue(MemoryStack stack, int familyIndex, boolean initCommandPool) {
        PointerBuffer pQueue = stack.mallocPointer(1);
        vkGetDeviceQueue(DeviceManager.vkDevice, familyIndex, 0, pQueue);
        this.vkQueue = new VkQueue(pQueue.get(0), DeviceManager.vkDevice);

        if (initCommandPool)
            this.commandPool = new CommandPool(familyIndex);
    }

    public long submitCommands(CommandPool.CommandBuffer commandBuffer) {
        return submitCommands(commandBuffer, false);
    }

    public synchronized long submitCommands(CommandPool.CommandBuffer commandBuffer, boolean useSemaphore) {
        try (MemoryStack stack = stackPush()) {
            return commandBuffer.submitCommands(stack, vkQueue, useSemaphore);
        }
    }

    public VkQueue vkQueue() {
        return this.vkQueue;
    }

    public void cleanUp() {
        if (commandPool != null)
            commandPool.cleanUp();
    }

    public void waitIdle() {
        vkQueueWaitIdle(vkQueue);
    }

    public CommandPool getCommandPool() {
        return commandPool;
    }

    public enum Family {
        Graphics,
        Transfer,
        Compute
    }

    public static QueueFamilyIndices getQueueFamilies() {
        if (device == null)
            device = Vulkan.getVkDevice();

        if (queueFamilyIndices == null) {
            queueFamilyIndices = findQueueFamilies(device.getPhysicalDevice());
        }
        return queueFamilyIndices;
    }

    public static QueueFamilyIndices findQueueFamilies(VkPhysicalDevice device) {
        QueueFamilyIndices indices = new QueueFamilyIndices();

        try (MemoryStack stack = stackPush()) {

            IntBuffer queueFamilyCount = stack.ints(0);

            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, null);

            VkQueueFamilyProperties.Buffer queueFamilies = VkQueueFamilyProperties.mallocStack(queueFamilyCount.get(0), stack);

            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, queueFamilies);

            IntBuffer presentSupport = stack.ints(VK_FALSE);

            for (int i = 0; i < queueFamilies.capacity(); i++) {
                int queueFlags = queueFamilies.get(i).queueFlags();

                if ((queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0) {
                    indices.graphicsFamily = i;

                    vkGetPhysicalDeviceSurfaceSupportKHR(device, i, Vulkan.getSurface(), presentSupport);

                    if (presentSupport.get(0) == VK_TRUE) {
                        indices.presentFamily = i;
                    }
                } else if ((queueFlags & (VK_QUEUE_GRAPHICS_BIT)) == 0
                        && (queueFlags & VK_QUEUE_COMPUTE_BIT) != 0) {
                    indices.computeFamily = i;
                } else if ((queueFlags & (VK_QUEUE_COMPUTE_BIT | VK_QUEUE_GRAPHICS_BIT)) == 0
                        && (queueFlags & VK_QUEUE_TRANSFER_BIT) != 0) {
                    indices.transferFamily = i;
                }

                if (indices.presentFamily == -1) {
                    vkGetPhysicalDeviceSurfaceSupportKHR(device, i, Vulkan.getSurface(), presentSupport);

                    if (presentSupport.get(0) == VK_TRUE) {
                        indices.presentFamily = i;
                    }
                }

                if (indices.isComplete())
                    break;
            }

            if (indices.presentFamily == -1) {
                // Some drivers will not show present support even if some queue supports it
                // Use compute queue as fallback

                indices.presentFamily = indices.computeFamily;
                Initializer.LOGGER.warn("Using compute queue as present fallback");
            }

            // In case there's no dedicated transfer queue, we need choose another one
            // preferably a different one from the already selected queues
            if (indices.transferFamily == -1) {

                int transferIndex = -1;
                for (int i = 0; i < queueFamilies.capacity(); i++) {
                    int queueFlags = queueFamilies.get(i).queueFlags();

                    if ((queueFlags & VK_QUEUE_TRANSFER_BIT) != 0) {
                        if (transferIndex == -1)
                            transferIndex = i;

                        if ((queueFlags & (VK_QUEUE_GRAPHICS_BIT)) == 0) {
                            indices.transferFamily = i;

                            if (i != indices.computeFamily)
                                break;

                            transferIndex = i;
                        }
                    }
                }

                if (transferIndex == -1)
                    throw new RuntimeException("Failed to find queue family with transfer support");

                indices.transferFamily = transferIndex;
            }

            if (indices.computeFamily == -1) {
                for (int i = 0; i < queueFamilies.capacity(); i++) {
                    int queueFlags = queueFamilies.get(i).queueFlags();

                    if ((queueFlags & VK_QUEUE_COMPUTE_BIT) != 0) {
                        indices.computeFamily = i;
                        break;
                    }
                }
            }

            if (indices.graphicsFamily == VK_QUEUE_FAMILY_IGNORED)
                throw new RuntimeException("Unable to find queue family with graphics support.");
            if (indices.presentFamily == VK_QUEUE_FAMILY_IGNORED)
                throw new RuntimeException("Unable to find queue family with present support.");
            if (indices.computeFamily == VK_QUEUE_FAMILY_IGNORED)
                throw new RuntimeException("Unable to find queue family with compute support.");

            return indices;
        }
    }

    public static class QueueFamilyIndices {
        public int graphicsFamily = VK_QUEUE_FAMILY_IGNORED;
        public int presentFamily = VK_QUEUE_FAMILY_IGNORED;
        public int transferFamily = VK_QUEUE_FAMILY_IGNORED;
        public int computeFamily = VK_QUEUE_FAMILY_IGNORED;

        public boolean isComplete() {
            return graphicsFamily != -1 && presentFamily != -1 && transferFamily != -1 && computeFamily != -1;
        }

        public boolean isSuitable() {
            return graphicsFamily != -1 && presentFamily != -1;
        }

        public int[] unique() {
            return IntStream.of(graphicsFamily, presentFamily, transferFamily, computeFamily).distinct().toArray();
        }

        public int[] array() {
            return new int[]{graphicsFamily, presentFamily};
        }
    }
}
