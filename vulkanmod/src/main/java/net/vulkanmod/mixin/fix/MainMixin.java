package net.vulkanmod.mixin.fix;

import net.minecraft.client.main.Main;
import org.lwjgl.system.Configuration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Main.class)
public class MainMixin {

    @Inject(method = "main", at=@At("HEAD"))
    private static void inj1(String[] strings, CallbackInfo ci) {
        // Increase stack size to 256 KB to prevent out of stack error on nvidia driver
        Configuration.STACK_SIZE.set(256);
    }
}
