package net.vulkanmod.render.engine;

import com.mojang.blaze3d.opengl.*;
import com.mojang.logging.LogUtils;
import java.util.Set;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;

@Environment(EnvType.CLIENT)
public class VkDebugLabel {
    private static final Logger LOGGER = LogUtils.getLogger();

    public void applyLabel(VkGpuBuffer glBuffer) {
    }

    public void applyLabel(VkGpuTexture glTexture) {
    }

    public void applyLabel(GlShaderModule glShaderModule) {
    }

    public void applyLabel(GlProgram glProgram) {
    }

    public void applyLabel(VertexArrayCache.VertexArray vertexArray) {
    }

    public static VkDebugLabel create(boolean bl, Set<String> set) {
        return new VkDebugLabel();
    }

    public boolean exists() {
        return true;
    }


}

