package dev.vire.client.mixin;

import dev.vire.client.VireClient;
import dev.vire.client.mode.VireMode;

import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionResult;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Backstop for layer 1: the game-mode actions that talk to the server do nothing in Vire Mode. They
 * should never be reached (the {@code Minecraft} paths are cancelled first), so reaching one is logged.
 */
@Mixin(MultiPlayerGameMode.class)
abstract class MultiPlayerGameModeMixin {
	@Unique
	private static void vire$reached(String what) {
		VireClient.LOG.warn("[Vire] {} reached in Vire Mode; cancelled", what, new Throwable("caller"));
	}

	@Inject(method = "useItemOn", at = @At("HEAD"), cancellable = true)
	private void vire$useItemOn(CallbackInfoReturnable<InteractionResult> cir) {
		if (VireMode.active()) {
			vire$reached("useItemOn");
			cir.setReturnValue(InteractionResult.PASS);
		}
	}

	@Inject(method = "useItem", at = @At("HEAD"), cancellable = true)
	private void vire$useItem(CallbackInfoReturnable<InteractionResult> cir) {
		if (VireMode.active()) {
			vire$reached("useItem");
			cir.setReturnValue(InteractionResult.PASS);
		}
	}

	@Inject(method = "startDestroyBlock", at = @At("HEAD"), cancellable = true)
	private void vire$startDestroy(CallbackInfoReturnable<Boolean> cir) {
		if (VireMode.active()) {
			vire$reached("startDestroyBlock");
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "continueDestroyBlock", at = @At("HEAD"), cancellable = true)
	private void vire$continueDestroy(CallbackInfoReturnable<Boolean> cir) {
		if (VireMode.active()) {
			vire$reached("continueDestroyBlock");
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "attack", at = @At("HEAD"), cancellable = true)
	private void vire$attack(CallbackInfo ci) {
		if (VireMode.active()) {
			vire$reached("attack");
			ci.cancel();
		}
	}

	@Inject(method = "interact", at = @At("HEAD"), cancellable = true)
	private void vire$interact(CallbackInfoReturnable<InteractionResult> cir) {
		if (VireMode.active()) {
			vire$reached("interact");
			cir.setReturnValue(InteractionResult.PASS);
		}
	}
}
