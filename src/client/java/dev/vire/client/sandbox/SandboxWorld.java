package dev.vire.client.sandbox;

import dev.vire.client.VireClient;
import dev.vire.core.region.Box;
import dev.vire.core.region.PosKey;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;

import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The sandbox side of a Vire session. Every method runs on the sandbox thread only.
 *
 * <p>Holds the region, collects the positions the sandbox changes inside it (fed by the
 * {@code ServerChunkCache.blockChanged} and {@code ServerLevel.doBlockEvent} mixins), and turns them
 * into a {@link MirrorBatch} at the end of every sandbox tick.
 */
public final class SandboxWorld {
	/** One user edit, replayed in order on every reset. {@code state} is air for a break. */
	public record Edit(long key, BlockState state, boolean placed) {
	}

	/** Raw copy: tell "clients" (our mirror), no neighbor or shape updates, no onPlace side effects. */
	private static final int RAW = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SKIP_ON_PLACE;

	private static int generation = -1;
	/** Blocks the last re-settle changed relative to the client's view (for the log and status). */
	public static volatile int lastSettleChanges;
	private static Box box;
	private static Box loaded;
	private static final LongLinkedOpenHashSet CHANGED = new LongLinkedOpenHashSet();
	private static final List<BlockEventData> EVENTS = new ArrayList<>();

	private SandboxWorld() {
	}

	// ---- hooks (sandbox thread) ----------------------------------------------------------------------

	public static void onBlockChanged(ServerLevel level, BlockPos pos) {
		if (box != null && level.getServer() instanceof SandboxServer && box.contains(pos.getX(), pos.getY(), pos.getZ())) {
			CHANGED.add(PosKey.pack(pos.getX(), pos.getY(), pos.getZ()));
		}
	}

	public static void onBlockEvent(ServerLevel level, BlockEventData event) {
		BlockPos p = event.pos();
		if (box != null && level.getServer() instanceof SandboxServer && box.contains(p.getX(), p.getY(), p.getZ())) {
			EVENTS.add(event);
		}
	}

	/** End of every sandbox tick (also while paused): send what changed. */
	public static void flush(SandboxServer server) {
		if (generation < 0 || (CHANGED.isEmpty() && EVENTS.isEmpty() && server.getTickCount() % 4 != 0)) {
			return; // nothing new; still send the clock status every 4 ticks
		}
		ServerLevel level = server.overworld();
		long[] keys = CHANGED.toLongArray();
		BlockState[] states = new BlockState[keys.length];
		for (int i = 0; i < keys.length; i++) {
			states[i] = level.getBlockState(Sandbox.pos(keys[i]));
		}
		var rate = server.tickRateManager();
		Sandbox.OUT.add(new MirrorBatch(generation, List.copyOf(EVENTS), keys, states, rate.isFrozen(), rate.tickrate(),
				level.getGameTime()));
		CHANGED.clear();
		EVENTS.clear();
	}

	// ---- operations ------------------------------------------------------------------------------------

	static void load(SandboxServer server, int gen, Box region, Map<Long, BlockState> snapshot,
			Map<Long, CompoundTag> blockEntities, List<Edit> edits) {
		ServerLevel level = server.overworld();
		long t0 = System.nanoTime();
		server.tickRateManager().setFrozen(true);
		clear(level);
		box = region;
		Box outer = region.expand(1);
		loaded = outer;
		setForced(level, outer, true);

		// 1. the copy, raw (the snapshot includes a 1-block border for context; only the box is mirrored)
		for (Map.Entry<Long, BlockState> e : snapshot.entrySet()) {
			BlockPos p = Sandbox.pos(e.getKey());
			if (!level.isOutsideBuildHeight(p)) {
				level.setBlock(p, e.getValue(), RAW);
			}
		}
		for (Map.Entry<Long, CompoundTag> e : blockEntities.entrySet()) {
			BlockEntity be = level.getBlockEntity(Sandbox.pos(e.getKey()));
			if (be != null) {
				try (ProblemReporter.ScopedCollector r = new ProblemReporter.ScopedCollector(be.problemPath(), VireClient.LOG)) {
					be.loadWithComponents(TagValueInput.create(r, level.registryAccess(), e.getValue()));
				} catch (RuntimeException ex) {
					VireClient.LOG.warn("[Vire] Could not copy block entity at {}", be.getBlockPos(), ex);
				}
			}
		}
		// 2. re-settle: every component re-reads its inputs, so power, comparators and pending
		//    repeater ticks become consistent with what the client could see (PLAN.md §5.5)
		int settled = 0;
		for (int x = outer.minX(); x <= outer.maxX(); x++) {
			for (int y = outer.minY(); y <= outer.maxY(); y++) {
				for (int z = outer.minZ(); z <= outer.maxZ(); z++) {
					BlockPos p = new BlockPos(x, y, z);
					BlockState s = level.getBlockState(p);
					if (!s.isAir()) {
						s.handleNeighborChanged(level, p, s.getBlock(), null, false);
						settled++;
					}
				}
			}
		}
		// how much the re-settle changed compared with what the client saw (logged; ideally 0 for a quiet area)
		int changedBySettle = 0;
		for (int x = region.minX(); x <= region.maxX(); x++) {
			for (int y = region.minY(); y <= region.maxY(); y++) {
				for (int z = region.minZ(); z <= region.maxZ(); z++) {
					long k = PosKey.pack(x, y, z);
					BlockState want = snapshot.getOrDefault(k, Blocks.AIR.defaultBlockState());
					BlockState got = level.getBlockState(new BlockPos(x, y, z));
					if (got != want) {
						if (changedBySettle < 5) {
							VireClient.LOG.info("[Vire] Re-settle changed {},{},{}: {} -> {}", x, y, z, want, got);
						}
						changedBySettle++;
					}
				}
			}
		}
		lastSettleChanges = changedBySettle;
		// 3. the user's edits, with normal updates
		for (Edit edit : edits) {
			apply(server, edit, false);
		}
		generation = gen;
		// everything in the box is "changed" for the mirror: the client may hold an older load
		for (int x = region.minX(); x <= region.maxX(); x++) {
			for (int y = region.minY(); y <= region.maxY(); y++) {
				for (int z = region.minZ(); z <= region.maxZ(); z++) {
					CHANGED.add(PosKey.pack(x, y, z));
				}
			}
		}
		EVENTS.clear();
		flush(server);
		VireClient.LOG.info("[Vire] Sandbox load {}: {} blocks, {} re-settled, {} edits in {} ms", gen, snapshot.size(), settled,
				edits.size(), (System.nanoTime() - t0) / 1_000_000);
	}

	static void unload(SandboxServer server) {
		server.tickRateManager().setFrozen(true);
		clear(server.overworld());
		generation = -1;
	}

	/** Applies one edit: place (with {@code setPlacedBy}, so doors and beds get their other half) or break. */
	static void apply(SandboxServer server, Edit edit, boolean flushNow) {
		ServerLevel level = server.overworld();
		BlockPos pos = Sandbox.pos(edit.key());
		if (box == null || !box.contains(pos.getX(), pos.getY(), pos.getZ()) || level.isOutsideBuildHeight(pos)) {
			return;
		}
		if (edit.placed()) {
			if (level.setBlock(pos, edit.state(), Block.UPDATE_ALL)) {
				BlockState placed = level.getBlockState(pos);
				placed.getBlock().setPlacedBy(level, pos, placed, null, new ItemStack(placed.getBlock().asItem()));
			}
		} else {
			level.setBlock(pos, level.getFluidState(pos).createLegacyBlock(), Block.UPDATE_ALL);
		}
		if (flushNow) {
			flush(server);
		}
	}

	/** Empties the previously loaded area (raw, nothing mirrored) and releases its chunks. */
	private static void clear(ServerLevel level) {
		Box old = loaded;
		box = null;
		CHANGED.clear();
		EVENTS.clear();
		if (old == null) {
			return;
		}
		BlockState air = Blocks.AIR.defaultBlockState();
		for (int x = old.minX(); x <= old.maxX(); x++) {
			for (int y = old.minY(); y <= old.maxY(); y++) {
				for (int z = old.minZ(); z <= old.maxZ(); z++) {
					BlockPos p = new BlockPos(x, y, z);
					if (!level.isOutsideBuildHeight(p)) {
						level.setBlock(p, air, RAW);
					}
				}
			}
		}
		level.getBlockTicks().clearArea(new net.minecraft.world.level.levelgen.structure.BoundingBox(
				old.minX(), old.minY(), old.minZ(), old.maxX(), old.maxY(), old.maxZ()));
		setForced(level, old, false);
		loaded = null;
	}

	private static void setForced(ServerLevel level, Box b, boolean forced) {
		for (int cx = b.minX() >> 4; cx <= b.maxX() >> 4; cx++) {
			for (int cz = b.minZ() >> 4; cz <= b.maxZ() >> 4; cz++) {
				level.setChunkForced(cx, cz, forced);
			}
		}
	}

	static ChunkPos chunk(long key) {
		return new ChunkPos(PosKey.x(key) >> 4, PosKey.z(key) >> 4);
	}
}
