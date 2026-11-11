package dev.vire.client;

import com.mojang.blaze3d.platform.InputConstants;

import dev.vire.core.config.ConfigStore;
import dev.vire.core.config.VireConfig;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;

/** Client entrypoint. M0: loads the config from {@code config/vire/} and registers the toggle key. */
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

		String version = FabricLoader.getInstance().getModContainer(MOD_ID)
				.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
		LOG.info("Vire {} loaded, config dir {}", version, STORE.dir());
	}

	private static void tick(Minecraft mc) {
		while (toggleKey.consumeClick()) {
			if (mc.player != null) {
				mc.player.sendSystemMessage(Component.translatable("vire.message.not_ready"));
			}
		}
	}
}
