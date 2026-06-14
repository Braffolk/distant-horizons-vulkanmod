package com.braffolk.dhvulkan.mixin.beryl;

import com.braffolk.dhvulkan.beryl.BerylCompat;
import com.braffolk.dhvulkan.beryl.DhBerylDefines;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Mixin into Beryl's BerylPipelineConfigs to inject DISTANT_HORIZONS preprocessor define
 * into the shader compilation environment.
 */
@Mixin(value = net.beryl.render.shader.BerylPipelineConfigs.class, remap = false)
public class MixinBerylPipelineConfigs {

    private static final Logger LOGGER = LogManager.getLogger("DH-VulkanMod-Beryl");

    @ModifyVariable(
            method = "getDefines",
            at = @At("RETURN"),
            require = 0,
            ordinal = 0
    )
    private List<String> dhvulkan$injectDhDefine(List<String> defines) {
        if (!BerylCompat.shouldUseVulkanWithBeryl() || defines == null) return defines;

        List<String> modified = new ArrayList<>(defines);
        modified.add("DISTANT_HORIZONS");
        modified.add("DH_INTEGRATION_VERSION=" + DhBerylDefines.getDefineVersion());
        return modified;
    }
}
