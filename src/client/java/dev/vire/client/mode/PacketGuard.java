package dev.vire.client.mode;

import dev.vire.client.VireClient;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundClientInformationPacket;
import net.minecraft.network.protocol.common.ServerboundCustomClickActionPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.common.ServerboundPongPacket;
import net.minecraft.network.protocol.common.ServerboundResourcePackPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundChatAckPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundChatCommandSignedPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundChatSessionUpdatePacket;
import net.minecraft.network.protocol.game.ServerboundChunkBatchReceivedPacket;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundClientTickEndPacket;
import net.minecraft.network.protocol.game.ServerboundCommandSuggestionPacket;
import net.minecraft.network.protocol.game.ServerboundConfigurationAcknowledgedPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundDebugSubscriptionRequestPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerLoadedPacket;
import net.minecraft.network.protocol.game.ServerboundSeenAdvancementsPacket;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;

import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Layer 2 of input isolation (PLAN.md §4.3): while Vire Mode is on, only an allow-list of harmless
 * packets may leave the client. Anything else is dropped and logged with a stack trace, because it
 * means layer 1 missed a path.
 *
 * <p>Allow-list, not block-list: a packet type added in a later Minecraft version is dropped by default.
 */
public final class PacketGuard {
	private static final Set<Class<?>> ALLOWED = Set.of(
			// connection upkeep
			ServerboundKeepAlivePacket.class, ServerboundPongPacket.class, ServerboundPingRequestPacket.class,
			ServerboundClientInformationPacket.class, ServerboundResourcePackPacket.class,
			ServerboundChunkBatchReceivedPacket.class, ServerboundClientTickEndPacket.class,
			ServerboundConfigurationAcknowledgedPacket.class, ServerboundPlayerLoadedPacket.class,
			ServerboundDebugSubscriptionRequestPacket.class,
			// chat and commands keep working in Vire Mode
			ServerboundChatPacket.class, ServerboundChatAckPacket.class, ServerboundChatCommandPacket.class,
			ServerboundChatCommandSignedPacket.class, ServerboundChatSessionUpdatePacket.class,
			ServerboundCommandSuggestionPacket.class, ServerboundCustomClickActionPacket.class,
			// the real body: it stands still; teleports are acknowledged (and end Vire Mode)
			ServerboundAcceptTeleportationPacket.class, ServerboundPlayerInputPacket.class,
			ServerboundClientCommandPacket.class, ServerboundContainerClosePacket.class,
			ServerboundSeenAdvancementsPacket.class);

	private static final boolean LOG_ALL = Boolean.getBoolean("vire.packetlog");

	private static int dropped;
	private static final Map<String, Integer> SENT = new TreeMap<>();
	private static final Map<String, Integer> DROPPED = new TreeMap<>();

	private PacketGuard() {
	}

	/** Returns {@code true} when {@code packet} must not be sent. Only called while Vire Mode is active. */
	public static boolean shouldDrop(Packet<?> packet) {
		String name = packet.getClass().getSimpleName();
		boolean allowed = ALLOWED.contains(packet.getClass()) || packet instanceof ServerboundMovePlayerPacket
				|| packet instanceof ServerboundCustomPayloadPacket;
		if (allowed) {
			SENT.merge(name, 1, Integer::sum);
			if (LOG_ALL || VireClient.config.packetLog) {
				VireClient.LOG.info("[packetlog] sent {}", name);
			}
			return false;
		}
		dropped++;
		DROPPED.merge(name, 1, Integer::sum);
		VireClient.LOG.warn("[Vire] Dropped {} in Vire Mode (isolation bug, please report)", name, new Throwable("packet source"));
		return true;
	}

	public static void reset() {
		dropped = 0;
		SENT.clear();
		DROPPED.clear();
	}

	public static int dropped() {
		return dropped;
	}

	public static Map<String, Integer> sent() {
		return SENT;
	}

	public static Map<String, Integer> droppedByType() {
		return DROPPED;
	}
}
