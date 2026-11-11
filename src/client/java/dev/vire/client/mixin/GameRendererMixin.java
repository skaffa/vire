package dev.vire.client.mixin;

import dev.vire.client.mode.VireMode;

import net.minecraft.client.renderer.GameRenderer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** No first-person hand in Vire Mode: the view belongs to the free camera, not the body. */
@Mixin(GameRenderer.class)
abstract class GameRendererMixin {
	@Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true)
	private void vire$hideHand(CallbackInfo ci) {
		if (VireMode.active()) {
			ci.cancel();
		}
	}
}
