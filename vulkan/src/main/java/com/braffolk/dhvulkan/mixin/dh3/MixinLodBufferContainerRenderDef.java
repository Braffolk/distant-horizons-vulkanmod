package com.braffolk.dhvulkan.mixin.dh3;

import com.braffolk.dhvulkan.compat.Compat;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.wrapperInterfaces.render.AbstractDhRenderApiDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@link LodBufferContainer} caches {@code RENDER_DEF} in its static initializer from
 * whatever was in {@link SingletonInjector} at class-load time. After we bind Vulkan,
 * that cached value can be stale. Redirect {@code useSingleIbo()} to read live injector state.
 */
@Mixin(value = LodBufferContainer.class, remap = false)
public class MixinLodBufferContainerRenderDef {

    @Redirect(
            method = "createIndexBuffers",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/seibel/distanthorizons/core/wrapperInterfaces/render/AbstractDhRenderApiDefinition;useSingleIbo()Z"
            ),
            require = 0
    )
    private static boolean dhvulkan$useSingleIboFromInjector(AbstractDhRenderApiDefinition cachedDef) {
        if (!Compat.isVulkanModActive()) {
            return cachedDef.useSingleIbo();
        }
        return SingletonInjector.INSTANCE.get(AbstractDhRenderApiDefinition.class).useSingleIbo();
    }
}
