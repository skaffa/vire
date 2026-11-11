package dev.vire.client.mode;

import dev.vire.client.VireClient;
import dev.vire.client.design.Designs;
import dev.vire.core.design.EditHistory;
import dev.vire.core.design.SessionStore;
import dev.vire.core.region.Box;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps the Vire session of each world on disk ({@link SessionStore}): written every few seconds while
 * Vire Mode is on and on every exit, so pressing V in the same region continues where you left off, and
 * a session cut short by a crash or kick is offered for restore on the next join.
 */
public final class Sessions {
	public static final SessionStore STORE = new SessionStore(VireClient.STORE.dir().resolve("sessions"));
	/** Autosave interval while Vire Mode is on, in client ticks. */
	private static final int AUTOSAVE_TICKS = 100;

	/** World whose unclean session should be offered once the player is in the world; null for none. */
	private static String promptWorld;
	private static int promptDelay;

	private Sessions() {
	}

	public static SessionStore.Saved load(String world) {
		try {
			return STORE.load(world);
		} catch (IOException e) {
			VireClient.LOG.warn("[Vire] Could not read the saved session of {}", world, e);
			return null;
		}
	}

	public static void delete(String world) {
		try {
			STORE.delete(world);
		} catch (IOException e) {
			VireClient.LOG.warn("[Vire] Could not delete the saved session of {}", world, e);
		}
	}

	/** Deletes the saved session of the world the player is in now. Returns whether there was one. */
	public static boolean deleteHere(Minecraft mc) {
		if (mc.level == null) {
			return false;
		}
		String world = Designs.worldId(mc);
		boolean had = load(world) != null;
		delete(world);
		return had;
	}

	/**
	 * Writes the session; a session whose edits cancel out deletes the file instead.
	 *
	 * @param clean whether the player saw it end (see {@link SessionStore.Saved#clean()})
	 */
	public static void save(Minecraft mc, VireSession s, boolean clean, String reason) {
		Map<Long, BlockState> last = new LinkedHashMap<>();
		for (EditHistory.Change<BlockState> c : s.history.changes()) {
			last.put(c.key(), c.after());
		}
		List<SessionStore.Edit> edits = new ArrayList<>(last.size());
		last.forEach((k, st) -> edits.add(new SessionStore.Edit(k, BlockStateParser.serialize(st))));
		String world = Designs.worldId(mc, s.level);
		s.dirty = false;
		if (edits.isEmpty()) {
			delete(world);
			return;
		}
		try {
			STORE.save(new SessionStore.Saved(world, s.box, System.currentTimeMillis(), clean, reason, edits));
		} catch (IOException e) {
			VireClient.LOG.warn("[Vire] Could not save the session", e);
		}
	}

	/** Every client tick while Vire Mode is on: write the session when it changed (crash safety). */
	public static void tickActive(Minecraft mc, VireSession s) {
		if (s.dirty && ++s.sinceSave >= AUTOSAVE_TICKS) {
			s.sinceSave = 0;
			save(mc, s, false, "crash");
		}
	}

	/**
	 * The saved session to continue when entering at {@code (x, y, z)}: one of this world whose region
	 * contains the player and whose chunks are all loaded. Null otherwise.
	 */
	public static SessionStore.Saved resumable(Minecraft mc, ClientLevel level, int x, int y, int z) {
		SessionStore.Saved saved = load(Designs.worldId(mc, level));
		if (saved == null || !saved.box().contains(x, y, z)) {
			return null;
		}
		Box b = saved.box();
		for (int cx = b.minX() >> 4; cx <= b.maxX() >> 4; cx++) {
			for (int cz = b.minZ() >> 4; cz <= b.maxZ() >> 4; cz++) {
				if (!level.hasChunk(cx, cz)) {
					return null;
				}
			}
		}
		return saved;
	}

	/** Puts the saved edits into a new session's history as one undoable step. Returns how many applied. */
	public static int apply(VireSession s, SessionStore.Saved saved) {
		int n = 0;
		s.history.begin();
		for (SessionStore.Edit e : saved.edits()) {
			BlockState st = Designs.state(e.state());
			if (st == null || !s.box.contains(dev.vire.core.region.PosKey.x(e.key()), dev.vire.core.region.PosKey.y(e.key()),
					dev.vire.core.region.PosKey.z(e.key()))) {
				continue; // unknown block (other version) or outside the region
			}
			s.history.add(e.key(), s.snapshot.getOrDefault(e.key(), Blocks.AIR.defaultBlockState()), st);
			n++;
		}
		s.history.end();
		return n;
	}

	// ---- restore prompt after a crash or kick ---------------------------------------------------------

	/** On join: remember to ask about an unclean session of this world. */
	public static void onJoin(Minecraft mc) {
		promptWorld = null;
		if (mc.level == null) {
			return;
		}
		String world = Designs.worldId(mc);
		SessionStore.Saved saved = load(world);
		if (saved != null && !saved.clean()) {
			promptWorld = world;
			promptDelay = 40; // let the world settle first
		}
	}

	public static void tick(Minecraft mc) {
		if (promptWorld == null || mc.player == null || mc.level == null || mc.gui.screen() != null || VireMode.active()) {
			return;
		}
		if (--promptDelay > 0) {
			return;
		}
		String world = promptWorld;
		promptWorld = null;
		if (!world.equals(Designs.worldId(mc))) {
			return;
		}
		SessionStore.Saved saved = load(world);
		if (saved != null && !saved.clean()) {
			mc.gui.setScreen(new RestoreScreen(saved));
		}
	}

	/** The player answered the restore question: the session is now known to them. */
	static void markClean(SessionStore.Saved saved) {
		try {
			STORE.save(saved.withClean(true, saved.reason()));
		} catch (IOException e) {
			VireClient.LOG.warn("[Vire] Could not update the saved session", e);
		}
	}

	static String where(Box b) {
		return (b.minX() + b.maxX()) / 2 + " " + (b.minY() + b.maxY()) / 2 + " " + (b.minZ() + b.maxZ()) / 2;
	}

	static void tell(Minecraft mc, String msg) {
		VireMode.say(mc, msg, ChatFormatting.AQUA);
	}
}
