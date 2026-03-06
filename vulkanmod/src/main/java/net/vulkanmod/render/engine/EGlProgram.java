package net.vulkanmod.render.engine;

import com.google.common.collect.Sets;
import com.mojang.blaze3d.opengl.*;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.vulkanmod.vulkan.shader.Pipeline;
import net.vulkanmod.vulkan.shader.descriptor.UBO;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.*;

public class EGlProgram {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static Set<String> BUILT_IN_UNIFORMS = Sets.<String>newHashSet("Projection", "Lighting", "Fog", "Globals");
    public static EGlProgram INVALID_PROGRAM = new EGlProgram(-1, "invalid");
    private final Map<String, Uniform> uniformsByName = new HashMap();
    private final int programId;
    private final String debugLabel;

    public EGlProgram(int i, String string) {
        this.programId = i;
        this.debugLabel = string;
    }

    public void setupUniforms(Pipeline pipeline, List<RenderPipeline.UniformDescription> uniformDescriptions, List<String> samplers) {
        int i = 0;
        int j = 0;

        for (RenderPipeline.UniformDescription uniformDescription : uniformDescriptions) {
            String name = uniformDescription.name();

            Uniform uniform = switch (uniformDescription.type()) {
                case UNIFORM_BUFFER -> {
                    UBO ubo = pipeline.getUBO(name);

                    if (ubo == null) {
                        yield null;
                    }

                    int binding = ubo.binding;
                    yield new Uniform.Ubo(binding);
                }
                case TEXEL_BUFFER -> {
                    int binding = i++;
                    yield new Uniform.Utb(binding, 0, Objects.requireNonNull(uniformDescription.textureFormat()));
                }
            };

            this.uniformsByName.put(name, uniform);
        }

        for (String samplerName : samplers) {
            var imageDescriptor = pipeline.getImageDescriptor(samplerName);
            int binding = imageDescriptor.getBinding();
            int imageIdx = imageDescriptor.imageIdx;
            this.uniformsByName.put(samplerName, new Uniform.Sampler(binding, imageIdx));
        }

    }

    @Nullable
    public Uniform getUniform(String string) {
        RenderSystem.assertOnRenderThread();
        return this.uniformsByName.get(string);
    }

    public int getProgramId() {
        return this.programId;
    }

    public String toString() {
        return this.debugLabel;
    }

    public String getDebugLabel() {
        return this.debugLabel;
    }

    public Map<String, Uniform> getUniforms() {
        return this.uniformsByName;
    }

}
