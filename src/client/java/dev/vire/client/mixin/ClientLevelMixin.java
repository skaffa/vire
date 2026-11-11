package dev.vire.client.mixin;

import dev.vire.client.interact.VireWorld;
import dev.vire.client.mode.VireMode;
import dev.vire.client.mode.VireSession;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Records virtual writes (PLAN.md §6.1) and holds back real block updates inside the region while
 * Vire Mode is on (§6.2). Both block-update packets end in {@code setServerVerifiedBlockState}.
 */
@Mixin(ClientLevel.class)
abstract class ClientLevelMixin {
	@Inject(method = "setBlock", at = @At("HEAD"))
	private void vire$setBlockHead(BlockPos pos, BlockState state, int flags, int limit, CallbackInfoReturnable<Boolean> cir) {
		VireWorld.onSetBlockHead((ClientLevel) (Object) this, pos);
	}

	@Inject(method = "setBlock", at = @At("RETURN"))
	private void vire$setBlockReturn(BlockPos pos, BlockState state, int flags, int limit, CallbackInfoReturnable<Boolean> cir) {
		VireWorld.onSetBlockReturn((ClientLevel) (Object) this, pos);
	}

	@Inject(method = "setServerVerifiedBlockState", at = @At("HEAD"), cancellable = true)
	private void vire$holdRealUpdate(BlockPos pos, BlockState state, int flags, CallbackInfo ci) {
		VireSession s = VireMode.session();
		if (s != null && s.level == (Object) this && s.shadow.onServerUpdate(VireWorld.key(pos), state)) {
			ci.cancel();
		}
	}
}
