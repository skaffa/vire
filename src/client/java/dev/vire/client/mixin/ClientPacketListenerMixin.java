package dev.vire.client.mixin;

import dev.vire.client.interact.VireWorld;
import dev.vire.client.mode.VireMode;
import dev.vire.client.mode.VireSession;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Holds back block entity data inside the region and re-applies virtual blocks after chunk resends. */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
	// After ensureRunningOnSameThread: the handler is first called on the network thread, which throws there.
	@Inject(method = "handleBlockEntityData", cancellable = true, at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
			shift = At.Shift.AFTER))
	private void vire$holdBlockEntityData(ClientboundBlockEntityDataPacket packet, CallbackInfo ci) {
		VireSession s = VireMode.session();
		if (s != null && s.shadow.onServerBlockEntityData(VireWorld.key(packet.getPos()), packet)) {
			ci.cancel();
		}
	}

	@Inject(method = "handleLevelChunkWithLight", at = @At("TAIL"))
	private void vire$chunkReloaded(ClientboundLevelChunkWithLightPacket packet, CallbackInfo ci) {
		VireWorld.onChunkReloaded(packet.x(), packet.z());
	}
}
