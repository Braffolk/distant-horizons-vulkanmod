package net.vulkanmod.mixin.debug;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerM {

    @Shadow protected abstract boolean handleChunkDebugKeys(KeyEvent keyEvent);

    @Shadow private boolean handledDebugKey;

    @Inject(method = "keyPress", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/KeyMapping;set(Lcom/mojang/blaze3d/platform/InputConstants$Key;Z)V", ordinal = 1))
    private void chunkDebug(long l, int i, KeyEvent keyEvent, CallbackInfo ci) {
        // GLFW key 296 -> F7
        // U -> Capture frustum
        this.handledDebugKey |= InputConstants.isKeyDown(Minecraft.getInstance().getWindow(), 296)
                && this.handleChunkDebugKeys(keyEvent);
    }
}
