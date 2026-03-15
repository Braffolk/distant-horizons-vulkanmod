package com.braffolk.dhvulkan.mixin.dh24;

import com.braffolk.dhvulkan.compat.Compat;
import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;
import com.seibel.distanthorizons.core.render.glObject.GLProxy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Mixin into {@link GLProxy} to prevent GL context initialization when
 * VulkanMod
 * is active.
 *
 * Creates a dummy GLProxy instance via sun.misc.Unsafe (bypassing the GL-heavy
 * constructor) so that DH's hasInstance() returns true and the data pipeline
 * (buffer uploads, thread checks) continues to work. Essential fields like
 * preferredUploadMethod are set via reflection after creation.
 */
@Mixin(value = GLProxy.class, remap = false)
public class MixinGLProxy {

    @Shadow
    private static GLProxy instance;

    @Unique
    private static boolean dhvulkan$dummyCreated = false;

    @Inject(method = "getInstance", at = @At("HEAD"), cancellable = true)
    private static void dhvulkan$skipGetInstance(CallbackInfoReturnable<GLProxy> cir) {
        if (!Compat.isVulkanModActive())
            return;

        if (!dhvulkan$dummyCreated) {
            try {
                // Create a GLProxy without invoking its constructor (which does GL calls).
                sun.misc.Unsafe unsafe = dhvulkan$getUnsafe();
                instance = (GLProxy) unsafe.allocateInstance(GLProxy.class);

                // Set required fields that DH reads during buffer upload.
                // preferredUploadMethod is used by getGpuUploadMethod() which is called
                // from LodBufferContainer.uploadBuffersDirect().
                java.lang.reflect.Field uploadField = GLProxy.class.getDeclaredField("preferredUploadMethod");
                uploadField.setAccessible(true);
                uploadField.set(instance, EDhApiGpuUploadMethod.DATA); // most basic, no special GL features needed

                // Initialize renderThreadRunnableQueue — DH 2.4.0 has this as an instance
                // field; without it, runRenderThreadTasks() NPEs.
                // Older/newer versions may use a static field or different name, so we
                // try both and silently skip if not found.
                for (String queueFieldName : new String[] { "renderThreadRunnableQueue",
                        "RENDER_THREAD_RUNNABLE_QUEUE" }) {
                    try {
                        java.lang.reflect.Field queueField = GLProxy.class.getDeclaredField(queueFieldName);
                        queueField.setAccessible(true);
                        if (queueField.get(instance) == null) {
                            queueField.set(instance, new java.util.concurrent.ConcurrentLinkedQueue<>());
                        }
                    } catch (NoSuchFieldException ignored) {
                        // Field doesn't exist in this DH version — fine
                    }
                }

                dhvulkan$dummyCreated = true;
                GLProxy.LOGGER.info("[DH-VulkanMod] Created dummy GLProxy (VulkanMod active, no GL context).");
            } catch (Exception e) {
                GLProxy.LOGGER.error("[DH-VulkanMod] Failed to create dummy GLProxy", e);
            }
        }

        cir.setReturnValue(instance);
    }

    @Inject(method = "runningOnRenderThread", at = @At("HEAD"), cancellable = true)
    private static void dhvulkan$alwaysOnRenderThread(CallbackInfoReturnable<Boolean> cir) {
        if (Compat.isVulkanModActive()) {
            cir.setReturnValue(true);
        }
    }

    @Unique
    private static sun.misc.Unsafe dhvulkan$getUnsafe() throws Exception {
        java.lang.reflect.Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        f.setAccessible(true);
        return (sun.misc.Unsafe) f.get(null);
    }
}
