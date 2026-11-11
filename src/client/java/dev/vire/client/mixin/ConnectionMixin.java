package dev.vire.client.mixin;

import dev.vire.client.mode.PacketGuard;
import dev.vire.client.mode.VireMode;

import io.netty.channel.ChannelFutureListener;

import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Layer 2 (PLAN.md §4.3): every outgoing packet passes {@link PacketGuard} while Vire Mode is on. */
@Mixin(Connection.class)
abstract class ConnectionMixin {
	@Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
			at = @At("HEAD"), cancellable = true)
	private void vire$guard(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
		// Serverbound only: in singleplayer the integrated server's own connections pass here too.
		if (VireMode.active() && packet.type().flow() == PacketFlow.SERVERBOUND && PacketGuard.shouldDrop(packet)) {
			ci.cancel();
		}
	}
}
