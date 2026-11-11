package dev.vire.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import dev.vire.client.dev.SelfTest;
import dev.vire.client.interact.VirtualHotbar;
import dev.vire.client.mode.PacketGuard;
import dev.vire.client.mode.VireMode;
import dev.vire.client.mode.VireSession;
import dev.vire.client.overlay.RegionOverlay;
import dev.vire.client.overlay.VireHud;
import dev.vire.core.config.ConfigStore;
import dev.vire.core.config.VireConfig;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/** Client entrypoint: config, keys, commands, tick hooks and HUD. */
public final class VireClient implements ClientModInitializer {
	public static final String MOD_ID = "vire";
	public static final Logger LOG = LoggerFactory.getLogger("Vire");

	/** All Vire files live in their own folder: {@code config/vire/}. */
	public static final ConfigStore STORE = new ConfigStore(FabricLoader.getInstance().getConfigDir().resolve(MOD_ID));
	public static VireConfig config = new VireConfig();

	private static KeyMapping toggleKey;

	@Override
	public void onInitializeClient() {
		try {
			config = STORE.load();
			if (STORE.lastBackup() != null) {
				LOG.warn("Broken {} moved to {}; using defaults", STORE.file(), STORE.lastBackup());
			}
		} catch (IOException e) {
			LOG.warn("Could not load {}; using defaults", STORE.file(), e);
		}

		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MOD_ID, "main"));
		toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.vire.toggle", InputConstants.KEY_V, category));

		ClientTickEvents.END_CLIENT_TICK.register(VireClient::tick);
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(() -> VireMode.exit(mc, "disconnected", false)));

		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Identifier.fromNamespaceAndPath(MOD_ID, "frame"), VireHud::frame);
		HudElementRegistry.replaceElement(VanillaHudElements.HOTBAR, vanilla -> (g, delta) -> {
			if (VireMode.active()) {
				VireHud.hotbar(g);
			} else {
				vanilla.extractRenderState(g, delta);
			}
		});

		ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
				ClientCommands.literal("vire")
						.executes(ctx -> {
							Minecraft mc = Minecraft.getInstance();
							mc.execute(() -> VireMode.toggle(mc));
							return 1;
						})
						.then(ClientCommands.literal("size")
								.then(ClientCommands.argument("blocks", IntegerArgumentType.integer(16, 64)).executes(ctx -> {
									config.regionSize = IntegerArgumentType.getInteger(ctx, "blocks");
									save();
									ctx.getSource().sendFeedback(Component.literal("[Vire] Region size " + config.regionSize
											+ " (used next time you enter)"));
									return 1;
								})))
						.then(ClientCommands.literal("status").executes(ctx -> {
							ctx.getSource().sendFeedback(Component.literal(status()));
							return 1;
						}))));

		SelfTest.init();

		String version = FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
		LOG.info("Vire {} loaded, config dir {}", version, STORE.dir());
	}

	private static void tick(Minecraft mc) {
		while (toggleKey.consumeClick()) {
			if (mc.player != null && mc.gui.screen() == null) {
				VireMode.toggle(mc);
			}
		}
		VireMode.tick(mc);
		RegionOverlay.tick();
		SelfTest.tick(mc);
	}

	public static KeyMapping toggleKey() {
		return toggleKey;
	}

	public static String status() {
		VireSession s = VireMode.session();
		Runtime rt = Runtime.getRuntime();
		long usedMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
		if (s == null) {
			return "[Vire] off. Region size " + config.regionSize + ". Memory " + usedMb + " MB.";
		}
		return "[Vire] on. Region " + s.box + ", edits " + s.history.undoSize() + ", held server updates "
				+ s.shadow.interceptedUpdates() + ", blocked packets " + PacketGuard.dropped() + ", slot "
				+ (VirtualHotbar.selected() + 1) + ". Memory " + usedMb + " MB.";
	}

	public static void save() {
		try {
			STORE.save(config);
		} catch (IOException e) {
			LOG.warn("Could not save {}", STORE.file(), e);
		}
	}
}
