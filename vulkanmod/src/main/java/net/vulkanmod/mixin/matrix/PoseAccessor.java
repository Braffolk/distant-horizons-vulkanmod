package net.vulkanmod.mixin.matrix;

import com.mojang.blaze3d.vertex.PoseStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PoseStack.Pose.class)
public interface PoseAccessor {

    @Accessor("trustedNormals")
    boolean trustedNormals();
}
