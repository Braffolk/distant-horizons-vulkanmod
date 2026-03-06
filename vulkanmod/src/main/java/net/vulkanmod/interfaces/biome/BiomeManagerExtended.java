package net.vulkanmod.interfaces.biome;

import net.minecraft.world.level.biome.BiomeManager;

public interface BiomeManagerExtended {

    static BiomeManagerExtended of(BiomeManager biomeManager) {
        return (BiomeManagerExtended) biomeManager;
    }

    long getBiomeZoomSeed();

}
