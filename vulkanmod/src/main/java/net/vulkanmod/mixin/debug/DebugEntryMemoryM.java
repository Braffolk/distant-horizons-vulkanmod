package net.vulkanmod.mixin.debug;

import net.minecraft.client.gui.components.debug.DebugEntryMemory;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.vulkanmod.vulkan.memory.MemoryManager;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Locale;

@Mixin(DebugEntryMemory.class)
public abstract class DebugEntryMemoryM {

    @Shadow @Final private static ResourceLocation GROUP;

    @Shadow
    protected static long bytesToMegabytes(long l) {
        return 0;
    }

    // TODO
//    @Shadow @Final private DebugEntryMemory.AllocationRateCalculator allocationRateCalculator;

    @Overwrite
    public void display(DebugScreenDisplayer debugScreenDisplayer, @Nullable Level level, @Nullable LevelChunk levelChunk, @Nullable LevelChunk levelChunk2) {
        long l = Runtime.getRuntime().maxMemory();
        long m = Runtime.getRuntime().totalMemory();
        long n = Runtime.getRuntime().freeMemory();
        long o = m - n;
        debugScreenDisplayer.addToGroup(
                GROUP,
                List.of(
                        String.format(Locale.ROOT, "Mem: %2d%% %03d/%03dMB", o * 100L / l, bytesToMegabytes(o), bytesToMegabytes(l)),
//                        String.format(Locale.ROOT, "Allocation rate: %03dMB/s", bytesToMegabytes(this.allocationRateCalculator.bytesAllocatedPerSecond(o))),
                        String.format(Locale.ROOT, "Allocated: %2d%% %03dMB", m * 100L / l, bytesToMegabytes(m)),
                        String.format("Off-heap: " + getOffHeapMemory() + "MB"),
                        "NativeMemory: %dMB".formatted(MemoryManager.getInstance().getNativeMemoryMB()),
                        "DeviceMemory: %dMB".formatted(MemoryManager.getInstance().getAllocatedDeviceMemoryMB())
                )
        );

    }

    private long getOffHeapMemory() {
        return bytesToMegabytes(ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed());
    }
}
