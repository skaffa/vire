package dev.vire.client.mixin;

import dev.vire.client.sandbox.SandboxWorld;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockEventData;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Block events that ran in the sandbox (piston starts, note blocks) are replayed on the client. */
@Mixin(ServerLevel.class)
abstract class ServerLevelMixin {
	@Inject(method = "doBlockEvent", at = @At("RETURN"))
	private void vire$mirrorEvent(BlockEventData event, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValueZ()) {
			SandboxWorld.onBlockEvent((ServerLevel) (Object) this, event);
		}
	}
}
