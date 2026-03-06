package net.vulkanmod.mixin.profiling;

import com.mojang.blaze3d.systems.TimerQuery;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

@Mixin(TimerQuery.class)
public class TimerQueryM {

    @Overwrite
    public void beginProfile() {
    }

    @Overwrite
    public TimerQuery.FrameProfile endProfile() {
        return null;
    }
}
