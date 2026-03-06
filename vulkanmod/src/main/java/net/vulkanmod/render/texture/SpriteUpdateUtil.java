package net.vulkanmod.render.texture;

import net.vulkanmod.vulkan.texture.VulkanImage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.HashSet;
import java.util.Set;

public abstract class SpriteUpdateUtil {

    private static boolean doUpload = true;
    private static final Set<VulkanImage> transitionedLayouts = new HashSet<>();

    public static void setDoUpload(boolean b) {
        doUpload = b;
    }

    public static boolean doUploadFrame() {
        return doUpload;
    }

    public static void addTransitionedLayout(VulkanImage image) {
        transitionedLayouts.add(image);
    }

    public static void transitionLayouts() {
        if (transitionedLayouts.isEmpty()) {
            return;
        }

        VkCommandBuffer commandBuffer = ImageUploadHelper.INSTANCE.getOrStartCommandBuffer().handle;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            transitionedLayouts.forEach(image -> image.readOnlyLayout(stack, commandBuffer));
            transitionedLayouts.clear();
        }
    }
}
