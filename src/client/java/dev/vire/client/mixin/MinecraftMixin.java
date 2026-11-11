package dev.vire.client.mixin;

import dev.vire.client.VireClient;
import dev.vire.client.interact.VireInput;
import dev.vire.client.mode.VireMode;
import dev.vire.client.mode.VireSession;

import net.minecraft.client.Minecraft;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Layer 1 (PLAN.md §4.1): vanilla's attack/use/pick paths never run in Vire Mode. */
@Mixin(Minecraft.class)
abstract class MinecraftMixin {
	@Inject(method = "handleKeybinds", at = @At("HEAD"))
	private void vire$handleKeys(CallbackInfo ci) {
		if (VireMode.active()) {
			VireInput.handleKeys((Minecraft) (Object) this);
		}
	}

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void vire$startAttack(CallbackInfoReturnable<Boolean> cir) {
		if (VireMode.active()) {
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void vire$continueAttack(boolean down, CallbackInfo ci) {
		if (VireMode.active()) {
			ci.cancel();
		}
	}

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void vire$startUseItem(CallbackInfo ci) {
		if (VireMode.active()) {
			ci.cancel();
		}
	}

	@Inject(method = "pickBlockOrEntity", at = @At("HEAD"), cancellable = true)
	private void vire$pickBlock(CallbackInfo ci) {
		if (VireMode.active()) {
			ci.cancel();
		}
	}

	/** Crosshair target comes from the free camera, with Vire's longer reach; entities are ignored. */
	@Inject(method = "pick", at = @At("HEAD"), cancellable = true)
	private void vire$pick(float partialTicks, CallbackInfo ci) {
		VireSession s = VireMode.session();
		if (s != null) {
			Minecraft mc = (Minecraft) (Object) this;
			mc.hitResult = s.camera.pick(VireClient.config.reach, partialTicks, false);
			mc.crosshairPickEntity = null;
			ci.cancel();
		}
	}
}
