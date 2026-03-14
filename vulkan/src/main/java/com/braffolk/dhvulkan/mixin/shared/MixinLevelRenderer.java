package com.braffolk.dhvulkan.mixin.shared;

import com.braffolk.dhvulkan.compat.Compat;
import com.braffolk.dhvulkan.DhVulkanModEntrypoint;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into Minecraft's {@link LevelRenderer}.
 *
 * Previously used to composite DH at renderLevel() RETURN, but that caused
 * DH LODs to appear on top of weather (rain/snow) because the composite
 * happened after MC finished rendering everything including weather.
 *
 * The composite now happens inside DH's own render lifecycle
 * (applyToMcTexture on DH 3.0, delegate endFrame on DH 2.4) which fires
 * before weather renders — matching how DH core handles this in OpenGL.
 *
 * This mixin is kept as a placeholder for future render hooks but currently
 * has no active injections.
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer {
    // Composite is now triggered by DH core's applyToMcTexture() callback
    // at the correct pipeline stage (after solid terrain, before weather).
}
