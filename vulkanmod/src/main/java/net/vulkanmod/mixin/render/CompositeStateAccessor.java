package net.vulkanmod.mixin.render;

import com.google.common.collect.ImmutableList;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderType.CompositeState.class)
public interface CompositeStateAccessor {

    @Accessor("textureState")
    RenderStateShard.EmptyTextureStateShard getTextureState();

    @Accessor("outputState")
    RenderStateShard.OutputStateShard getOutputState();

    @Accessor("outlineProperty")
    RenderType.OutlineProperty getOutlineProperty();

    @Accessor("states")
    ImmutableList<RenderStateShard> getStates();
}
