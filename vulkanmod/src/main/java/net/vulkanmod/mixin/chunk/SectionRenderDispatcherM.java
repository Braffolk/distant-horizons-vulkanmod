package net.vulkanmod.mixin.chunk;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(SectionRenderDispatcher.class)
public class SectionRenderDispatcherM {

	// TODO
//	@Redirect(method = "<init>", at = @At(value = "INVOKE", target = "Lnet/minecraft/util/thread/ProcessorMailbox;tell(Ljava/lang/Object;)V"))
//	private void redirectTask(ProcessorMailbox instance, Object object) {}
}
