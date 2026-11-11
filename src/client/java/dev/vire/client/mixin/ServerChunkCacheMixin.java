package dev.vire.client.mixin;

import dev.vire.client.sandbox.SandboxWorld;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Every block change a server would send to players passes here; for the sandbox server it feeds
 * the mirror (PLAN.md §6.1). Other servers (singleplayer) only pay an {@code instanceof}.
 */
@Mixin(ServerChunkCache.class)
abstract class ServerChunkCacheMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@Inject(method = "blockChanged", at = @At("HEAD"))
	private void vire$mirror(BlockPos pos, CallbackInfo ci) {
		SandboxWorld.onBlockChanged(level, pos);
	}
}
