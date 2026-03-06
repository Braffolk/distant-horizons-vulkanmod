package net.vulkanmod.mixin.render.shader;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import net.vulkanmod.interfaces.shader.ExtendedRenderPipeline;
import net.vulkanmod.render.engine.EGlProgram;
import net.vulkanmod.vulkan.shader.GraphicsPipeline;
import net.vulkanmod.vulkan.shader.Pipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(RenderPipeline.class)
public abstract class RenderPipelineM implements ExtendedRenderPipeline {
    @Unique GraphicsPipeline pipeline;
    @Unique EGlProgram eGlProgram;

    @Override
    public void setPipeline(GraphicsPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    public void setProgram(EGlProgram program) {
        this.eGlProgram = program;
    }

    @Override
    public EGlProgram getProgram() {
        return this.eGlProgram;
    }

    @Override
    public GraphicsPipeline getPipeline() {
        return this.pipeline;
    }
}
