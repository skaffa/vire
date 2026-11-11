package dev.vire.client.design;

import dev.vire.client.VireClient;
import dev.vire.client.mode.VireSession;
import dev.vire.core.design.Design;
import dev.vire.core.design.DesignStore;
import dev.vire.core.design.EditHistory;
import dev.vire.core.design.StateRef;

import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Designs on disk ({@code config/vire/designs/}) and the conversion between Minecraft states and {@link StateRef}. */
public final class Designs {
	public static final DesignStore STORE = new DesignStore(VireClient.STORE.dir().resolve("designs"));
	private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm");

	private Designs() {
	}

	public static StateRef ref(BlockState s) {
		return StateRef.parse(BlockStateParser.serialize(s));
	}

	/** The Minecraft state of a ref; unknown blocks or values (another version) give null. */
	public static BlockState state(String text) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, text, false).blockState();
		} catch (CommandSyntaxException | RuntimeException e) {
			return null;
		}
	}

	/** Where the player is now: server address or save name, plus dimension. Designs only show there. */
	public static String worldId(Minecraft mc) {
		return worldId(mc, mc.level);
	}

	public static String worldId(Minecraft mc, net.minecraft.world.level.Level level) {
		String where;
		if (mc.getSingleplayerServer() != null) {
			where = "sp:" + mc.getSingleplayerServer().getWorldData().getLevelName();
		} else if (mc.getCurrentServer() != null) {
			where = "mp:" + mc.getCurrentServer().ip;
		} else {
			where = "?";
		}
		return where + "/" + (level == null ? "?" : level.dimension().identifier().toString());
	}

	/**
	 * The design of a Vire session: the final state of every position the user's edits touched, against
	 * the real state there (the snapshot, or the newer real state the server sent meanwhile). Null when
	 * nothing differs.
	 */
	public static Design capture(Minecraft mc, VireSession s, String name) {
		Map<Long, StateRef> design = new HashMap<>();
		for (EditHistory.Step<BlockState> step : s.history.steps()) {
			for (EditHistory.Change<BlockState> c : step.changes()) {
				design.put(c.key(), ref(c.after()));
			}
		}
		Map<Long, StateRef> real = new HashMap<>();
		for (Long k : design.keySet()) {
			BlockState r = s.shadow.restoreList().get(k);
			if (r == null) {
				r = s.snapshot.getOrDefault(k, Blocks.AIR.defaultBlockState());
			}
			real.put(k, ref(r));
		}
		List<Design.Entry> entries = Design.diff(real, design);
		if (entries.isEmpty()) {
			return null;
		}
		return new Design(name, worldId(mc, s.level), System.currentTimeMillis(), entries);
	}

	/** A free default name: {@code vire_2026-11-11_1530}. */
	public static String defaultName() {
		try {
			return STORE.freeName("vire_" + LocalDateTime.now().format(NAME));
		} catch (IOException e) {
			return "vire_" + System.currentTimeMillis();
		}
	}

	/** Saves under {@code name} (cleaned); returns the saved design or null on error (logged). */
	public static Design save(Design d, String name) {
		Design named = new Design(DesignStore.cleanName(name), d.world(), d.created(), d.entries());
		try {
			STORE.save(named);
			return named;
		} catch (IOException e) {
			VireClient.LOG.warn("[Vire] Could not save design {}", named.name(), e);
			return null;
		}
	}

	public static Design load(String name) {
		try {
			return STORE.load(name);
		} catch (IOException e) {
			VireClient.LOG.warn("[Vire] Could not load design {}", name, e);
			return null;
		}
	}

	public static List<String> names() {
		try {
			return STORE.list();
		} catch (IOException e) {
			return List.of();
		}
	}
}
