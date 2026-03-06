package net.vulkanmod.render.profiling;

import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.vulkanmod.render.chunk.WorldRenderer;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class DebugEntryMemoryStats implements DebugScreenEntry {
    private static final ResourceLocation GROUP = ResourceLocation.withDefaultNamespace("vk_memory");

    @Override
    public void display(DebugScreenDisplayer debugScreenDisplayer, @Nullable Level level,
                        @Nullable LevelChunk levelChunk, @Nullable LevelChunk levelChunk2) {
        var chunkAreaManager = WorldRenderer.getInstance().getChunkAreaManager();

        if (chunkAreaManager != null) {
            debugScreenDisplayer.addToGroup(
                    GROUP,
                    List.of(chunkAreaManager.getStats())
            );
        }
    }
}
