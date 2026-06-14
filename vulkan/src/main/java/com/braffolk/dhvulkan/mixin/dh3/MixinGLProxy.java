package com.braffolk.dhvulkan.mixin.dh3;

import com.braffolk.dhvulkan.compat.Compat;
import com.seibel.distanthorizons.api.enums.config.EDhApiGpuUploadMethod;
import com.seibel.distanthorizons.common.render.openGl.glObject.GLProxy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * DH 3.0 GLProxy lives in {@code common.render.openGl.glObject}.
 * When VulkanMod is active we stub getInstance() so DH's data pipeline does not
 * require a real GL context (fixes "GLProxy was created outside the render thread").
 */
@Mixin(value = GLProxy.class, remap = false)
public class MixinGLProxy {

    @Shadow
    private static GLProxy instance;

    @Unique
    private static boolean dhvulkan$dummyCreated = false;

    @Inject(method = "getInstance", at = @At("HEAD"), cancellable = true)
    private static void dhvulkan$stubGetInstance(CallbackInfoReturnable<GLProxy> cir) {
        if (!Compat.isVulkanModActive()) return;

        if (!dhvulkan$dummyCreated) {
            try {
                sun.misc.Unsafe unsafe = dhvulkan$getUnsafe();
                instance = (GLProxy) unsafe.allocateInstance(GLProxy.class);

                java.lang.reflect.Field uploadField = GLProxy.class.getDeclaredField("preferredUploadMethod");
                uploadField.setAccessible(true);
                uploadField.set(instance, EDhApiGpuUploadMethod.DATA);

                dhvulkan$dummyCreated = true;
                GLProxy.LOGGER.debug("[DH-VulkanMod] Created dummy GLProxy for DH 3.0 (VulkanMod active).");
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
