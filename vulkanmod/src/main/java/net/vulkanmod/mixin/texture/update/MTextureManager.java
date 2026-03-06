package net.vulkanmod.mixin.texture.update;

import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.renderer.texture.Tickable;
import net.vulkanmod.Initializer;
import net.vulkanmod.render.texture.SpriteUpdateUtil;
import net.vulkanmod.vulkan.Renderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Set;

@Mixin(TextureManager.class)
public abstract class MTextureManager {

    @Shadow @Final private Set<Tickable> tickableTextures;

    /**
     * @author
     */
    @Overwrite
    public void tick() {
        if (Renderer.skipRendering || !Initializer.CONFIG.textureAnimations)
            return;

        //Debug D
        for (Tickable tickable : this.tickableTextures) {
            tickable.tick();
        }

        SpriteUpdateUtil.transitionLayouts();
    }
}
