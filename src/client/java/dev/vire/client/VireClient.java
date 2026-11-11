package dev.vire.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.brigadier.arguments.IntegerArgumentType;

import dev.vire.client.dev.SelfTest;
import dev.vire.client.interact.VirtualHotbar;
import dev.vire.client.mode.PacketGuard;
import dev.vire.client.mode.VireMode;
import dev.vire.client.mode.VireSession;
import dev.vire.client.design.BomHud;
import dev.vire.client.design.Designs;
import dev.vire.client.design.GhostBlockRenderer;
import dev.vire.client.design.GhostLayer;
import dev.vire.client.overlay.Probe;
import dev.vire.core.design.Design;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.SharedSuggestionProvider;
import java.util.List;
import java.util.Map;
import dev.vire.client.overlay.RegionOverlay;
import dev.vire.client.overlay.SignalOverlay;
import dev.vire.client.overlay.VireHud;
import dev.vire.client.interact.VireWorld;
import dev.vire.client.sandbox.Sandbox;
import dev.vire.core.config.ConfigStore;
import dev.vire.core.config.VireConfig;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
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
	private static KeyMapping pauseKey;
	private static KeyMapping stepKey;
	private static KeyMapping stepManyKey;
	private static KeyMapping slowerKey;
	private static KeyMapping fasterKey;
	private static KeyMapping resetKey;
	private static KeyMapping markInKey;
	private static KeyMapping markOutKey;
	private static KeyMapping arrowsKey;
	private static KeyMapping bomKey;
	/** Tick rates the speed keys step through (PLAN.md §5.7). */
	private static final float[] RATES = {1, 2, 5, 10, 20, 40, 60, 100};

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
		pauseKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.vire.pause", InputConstants.KEY_K, category));
		stepKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.vire.step", InputConstants.KEY_PERIOD, category));
		stepManyKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.vire.step_many", InputConstants.KEY_COMMA, category));
		slowerKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.vire.slower", InputConstants.KEY_LBRACKET, category));
		fasterKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.vire.faster", InputConstants.KEY_RBRACKET, category));
		resetKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.vire.reset", InputConstants.KEY_R, category));
		// N, M and J have no vanilla 26.3 default (A D E F G L O P Q S T W C X do)
		markInKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.vire.mark_in", InputConstants.KEY_N, category));
		markOutKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.vire.mark_out", InputConstants.KEY_M, category));
		arrowsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.vire.arrows", InputConstants.KEY_J, category));
		bomKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.vire.bom", InputConstants.KEY_B, category));
		GhostBlockRenderer.register();

		ClientTickEvents.END_CLIENT_TICK.register(VireClient::tick);
		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> {
			if (config.keepWarm) {
				Sandbox.ensureStarted(); // boots in the background, so V is instant later
			}
			mc.execute(GhostLayer::restoreActive);
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> mc.execute(() -> {
			VireMode.exit(mc, "disconnected", false);
			Sandbox.stop();
		}));
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> Sandbox.stop());

		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Identifier.fromNamespaceAndPath(MOD_ID, "frame"), VireHud::frame);
		HudElementRegistry.attachElementAfter(VanillaHudElements.CROSSHAIR, Identifier.fromNamespaceAndPath(MOD_ID, "bom"), BomHud::render);
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
						.then(ClientCommands.literal("ghost")
								.then(ClientCommands.literal("off").executes(ctx -> {
									Minecraft.getInstance().execute(() -> GhostLayer.show(null));
									ctx.getSource().sendFeedback(Component.literal("[Vire] Ghost hidden"));
									return 1;
								}))
								.then(ClientCommands.argument("name", StringArgumentType.word())
										.suggests((ctx, b) -> SharedSuggestionProvider.suggest(Designs.names(), b))
										.executes(ctx -> {
											String name = StringArgumentType.getString(ctx, "name");
											Design d = Designs.load(name);
											if (d == null) {
												ctx.getSource().sendError(Component.literal("[Vire] No design " + name + " (see /vire designs)"));
												return 0;
											}
											Minecraft.getInstance().execute(() -> GhostLayer.show(d));
											String here = Designs.worldId(Minecraft.getInstance());
											ctx.getSource().sendFeedback(Component.literal("[Vire] Ghost " + d.name() + ": " + d.entries().size() + " blocks"
													+ (d.world().equals(here) ? "" : " (made in " + d.world() + "; shows only there)")));
											return 1;
										})))
						.then(ClientCommands.literal("designs").executes(ctx -> {
							List<String> names = Designs.names();
							ctx.getSource().sendFeedback(Component.literal(names.isEmpty() ? "[Vire] No saved designs"
									: "[Vire] Designs: " + String.join(", ", names)));
							return 1;
						}))
						.then(ClientCommands.literal("bom").executes(ctx -> {
							if (GhostLayer.design() == null) {
								ctx.getSource().sendError(Component.literal("[Vire] No ghost shown (/vire ghost <name>)"));
								return 0;
							}
							ctx.getSource().sendFeedback(Component.literal("[Vire] Materials for " + GhostLayer.design().name() + " (needed / in inventory):"));
							for (Map.Entry<String, int[]> e : GhostLayer.bom().entrySet()) {
								int[] v = e.getValue();
								ctx.getSource().sendFeedback(Component.literal("  " + v[0] + " x " + e.getKey().replace("minecraft:", "") + " (" + v[1] + ")")
										.withStyle(v[1] >= v[0] ? ChatFormatting.GREEN : ChatFormatting.RED));
							}
							return 1;
						}))
						.then(ClientCommands.literal("status").executes(ctx -> {
							ctx.getSource().sendFeedback(Component.literal(status()));
							return 1;
						}))
						// M2 spike: boot the sandbox server, test redstone timing, measure cost
						.then(ClientCommands.literal("spike").executes(ctx -> {
							dev.vire.client.sandbox.SandboxSpike.start(Minecraft.getInstance());
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
		VireSession s = VireMode.session();
		if (s != null && !s.loaded) {
			s = null; // controls wait until the sandbox region ticks (HUD shows "loading…")
		}
		while (pauseKey.consumeClick()) {
			if (s != null) {
				Sandbox.togglePause();
			}
		}
		while (stepKey.consumeClick()) {
			if (s != null) {
				Sandbox.step(1);
			}
		}
		while (stepManyKey.consumeClick()) {
			if (s != null) {
				Sandbox.step(10);
			}
		}
		while (slowerKey.consumeClick()) {
			if (s != null) {
				Sandbox.setTickRate(nextRate(s.tickRate, -1));
			}
		}
		while (fasterKey.consumeClick()) {
			if (s != null) {
				Sandbox.setTickRate(nextRate(s.tickRate, +1));
			}
		}
		while (resetKey.consumeClick()) {
			if (s != null) {
				VireWorld.reset(mc);
			}
		}
		while (markInKey.consumeClick()) {
			if (s != null) {
				Probe.mark(mc, true);
			}
		}
		while (markOutKey.consumeClick()) {
			if (s != null) {
				Probe.mark(mc, false);
			}
		}
		while (arrowsKey.consumeClick()) {
			if (VireMode.active()) {
				config.showArrows = !config.showArrows;
				save();
				VireMode.hint(mc, "Flow arrows " + (config.showArrows ? "on" : "off"));
			}
		}
		while (bomKey.consumeClick()) {
			if (GhostLayer.design() != null && !VireMode.active()) {
				config.showBom = !config.showBom;
				save();
			}
		}
		VireMode.tick(mc);
		GhostLayer.tick(mc);
		RegionOverlay.tick();
		SignalOverlay.tick();
		SelfTest.tick(mc);
	}

	static float nextRate(float current, int dir) {
		int best = 0;
		for (int i = 0; i < RATES.length; i++) {
			if (Math.abs(RATES[i] - current) < Math.abs(RATES[best] - current)) {
				best = i;
			}
		}
		return RATES[Math.max(0, Math.min(RATES.length - 1, best + dir))];
	}

	public static KeyMapping toggleKey() {
		return toggleKey;
	}

	public static KeyMapping pauseKey() {
		return pauseKey;
	}

	public static KeyMapping stepKey() {
		return stepKey;
	}

	public static KeyMapping stepManyKey() {
		return stepManyKey;
	}

	public static KeyMapping fasterKey() {
		return fasterKey;
	}

	public static KeyMapping slowerKey() {
		return slowerKey;
	}

	public static KeyMapping resetKey() {
		return resetKey;
	}

	public static KeyMapping markInKey() {
		return markInKey;
	}

	public static KeyMapping markOutKey() {
		return markOutKey;
	}

	public static KeyMapping bomKey() {
		return bomKey;
	}

	public static KeyMapping arrowsKey() {
		return arrowsKey;
	}

	/** Short name of the key a mapping is bound to, for the HUD help line. */
	public static String keyName(KeyMapping k) {
		return k.getTranslatedKeyMessage().getString();
	}

	public static String status() {
		VireSession s = VireMode.session();
		Runtime rt = Runtime.getRuntime();
		long usedMb = (rt.totalMemory() - rt.freeMemory()) >> 20;
		if (s == null) {
			return "[Vire] off. Sandbox " + (Sandbox.ready() ? "ready" : Sandbox.startingOrReady() ? "starting" : "stopped")
					+ ". Region size " + config.regionSize + ". Memory " + usedMb + " MB.";
		}
		return "[Vire] on. Sandbox " + (s.frozen ? "paused" : "running") + " at " + s.tickRate + " TPS, tick " + s.sandboxTick
				+ ". Region " + s.box + ", edits " + s.history.undoSize() + ", held server updates "
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
