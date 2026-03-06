package net.vulkanmod.mixin.render.particle;

import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.state.QuadParticleRenderState;
import net.vulkanmod.interfaces.ExtendedVertexBuilder;
import net.vulkanmod.vulkan.util.ColorUtil;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

@Mixin(QuadParticleRenderState.class)
public class QuadParticleRenderStateM {

    @Unique private final Quaternionf quaternionf = new Quaternionf();
    @Unique private final Vector3f vector3f = new Vector3f();

    @Overwrite
    public void renderRotatedQuad(
            VertexConsumer vertexConsumer, float x, float y, float z, float xr, float yr, float zr, float wr, float m, float u0, float u1, float v0, float v1, int color, int light
    ) {
        quaternionf.set(xr, yr, zr, wr);

        ExtendedVertexBuilder vertexBuilder = (ExtendedVertexBuilder)vertexConsumer;

        color = ColorUtil.BGRAtoRGBA(color);

        this.renderVertex(vertexBuilder, quaternionf, x, y, z, 1.0F, -1.0F, m, u1, v1, color, light);
        this.renderVertex(vertexBuilder, quaternionf, x, y, z, 1.0F, 1.0F, m, u1, v0, color, light);
        this.renderVertex(vertexBuilder, quaternionf, x, y, z, -1.0F, 1.0F, m, u0, v0, color, light);
        this.renderVertex(vertexBuilder, quaternionf, x, y, z, -1.0F, -1.0F, m, u0, v1, color, light);
    }

    private void renderVertex(
            ExtendedVertexBuilder vertexConsumer, Quaternionf quaternionf, float x, float y, float z, float i, float j, float k, float u, float v, int color, int light
    ) {
        vector3f.set(i, j, 0.0f)
                .rotate(quaternionf)
                .mul(k)
                .add(x, y, z);

        vertexConsumer.vertex(vector3f.x(), vector3f.y(), vector3f.z(), u, v, color, light);
    }
}
