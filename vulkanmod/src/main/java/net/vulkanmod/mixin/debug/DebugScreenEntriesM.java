package net.vulkanmod.mixin.debug;

import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.ResourceLocation;
import net.vulkanmod.render.profiling.DebugEntryMemoryStats;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(DebugScreenEntries.class)
public abstract class DebugScreenEntriesM {

    @Shadow
    public static ResourceLocation register(ResourceLocation resourceLocation, DebugScreenEntry debugScreenEntry) {
        return null;
    }

    @Inject(method = "<clinit>", at = @At("RETURN"))
    private static void addEntry(CallbackInfo ci) {
        register(ResourceLocation.fromNamespaceAndPath("vkmod","stats"), new DebugEntryMemoryStats());
    }
}
