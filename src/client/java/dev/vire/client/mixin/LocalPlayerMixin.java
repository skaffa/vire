package dev.vire.client.mixin;

import dev.vire.client.mode.VireMode;
import dev.vire.client.mode.VireSession;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.phys.Vec3;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The real body does not move in Vire Mode (PLAN.md §3.2). Without this, removing a virtual block
 * under it would let it fall client-side, and the server would see a wrong position on exit.
 */
@Mixin(LocalPlayer.class)
abstract class LocalPlayerMixin {
	@Inject(method = "move", at = @At("HEAD"), cancellable = true)
	private void vire$freeze(MoverType type, Vec3 delta, CallbackInfo ci) {
		VireSession s = VireMode.session();
		if (s != null && (Object) this == s.player) {
			ci.cancel();
		}
	}
}
