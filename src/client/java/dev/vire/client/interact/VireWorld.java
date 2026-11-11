package dev.vire.client.interact;

import dev.vire.client.VireClient;
import dev.vire.client.mixin.BlockItemInvoker;
import dev.vire.client.mode.VireMode;
import dev.vire.client.mode.VireSession;
import dev.vire.client.sandbox.MirrorBatch;
import dev.vire.client.sandbox.Sandbox;
import dev.vire.client.sandbox.SandboxWorld;
import dev.vire.core.design.EditHistory;
import dev.vire.core.region.PosKey;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockEventData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Client side of virtual editing (PLAN.md §6, §7).
 *
 * <p>User edits go to the sandbox server; the sandbox simulates and its changes come back as
 * {@link MirrorBatch}es, which are written into the client level here. While a batch is written,
 * {@link #recording} is on, so {@code ClientLevelMixin} captures every overwritten real state in the
 * session's {@code ShadowRegion} for the restore on exit.
 */
public final class VireWorld {
	/** Flags for restore and chunk re-apply: tell the renderer, no neighbor or shape updates. */
	private static final int RAW = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_IMMEDIATE;
	/** Flags for mirrored states: the same as vanilla uses for block updates from a server. */
	private static final int MIRROR = 19;

	private static boolean recording;
	private static final Deque<BlockState> BEFORE = new ArrayDeque<>();

	private VireWorld() {
	}

	public static void resetRecording() {
		recording = false;
		BEFORE.clear();
	}

	// ---- called from ClientLevelMixin -------------------------------------------------------------

	public static void onSetBlockHead(ClientLevel level, BlockPos pos) {
		VireSession s = VireMode.session();
		if (!recording || s == null || level != s.level) {
			return;
		}
		BEFORE.push(level.getBlockState(pos));
		long key = key(pos);
		if (!s.shadow.isVirtual(key) && !s.shadow.restoreList().containsKey(key)) {
			BlockEntity be = level.getBlockEntity(pos);
			if (be != null) {
				s.savedBlockEntities.put(key, be.saveWithFullMetadata(level.registryAccess()));
			}
		}
	}

	public static void onSetBlockReturn(ClientLevel level, BlockPos pos) {
		VireSession s = VireMode.session();
		if (!recording || s == null || level != s.level || BEFORE.isEmpty()) {
			return;
		}
		BlockState before = BEFORE.pop();
		BlockState after = level.getBlockState(pos);
		long key = key(pos);
		s.shadow.beforeVirtualWrite(key, before);
		s.shadow.afterVirtualWrite(key, after);
	}

	// ---- mirror (client tick) -----------------------------------------------------------------------

	/** Writes everything the sandbox sent since the last tick into the client level. */
	public static void applyMirror(VireSession s) {
		MirrorBatch b;
		while ((b = Sandbox.OUT.poll()) != null) {
			if (b.generation() != s.generation) {
				continue; // from an older load or session
			}
			s.frozen = b.frozen();
			s.tickRate = b.tickRate();
			s.sandboxTick = b.tick();
			s.loaded = true;
			recording = true;
			try {
				// like vanilla: block events (piston starts) first, then the block updates of that tick
				for (BlockEventData e : b.events()) {
					if (s.level.getBlockState(e.pos()).is(e.block())) {
						s.level.blockEvent(e.pos(), e.block(), e.paramA(), e.paramB());
					}
				}
				long[] keys = b.keys();
				BlockState[] states = b.states();
				for (int i = 0; i < keys.length; i++) {
					BlockPos p = pos(keys[i]);
					if (s.level.getBlockState(p) != states[i]) {
						s.level.setBlock(p, states[i], MIRROR);
					}
				}
			} catch (RuntimeException e) {
				VireClient.LOG.warn("[Vire] Mirror batch failed", e);
			} finally {
				resetRecording();
			}
		}
	}

	// ---- user actions -------------------------------------------------------------------------------

	/** Right click: place the selected virtual hotbar block against the targeted face. */
	public static void place(Minecraft mc, boolean secondary) {
		VireSession s = VireMode.session();
		if (s == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		ItemStack stack = VirtualHotbar.selectedStack();
		if (!(stack.getItem() instanceof BlockItem item)) {
			VireMode.hint(mc, stack.isEmpty() ? "Empty slot: press E to pick blocks" : "Not a block");
			return;
		}
		BlockPlaceContext ctx = new VirtualPlaceContext(s.level, s.player, stack, hit, s.camera, secondary);
		ctx = item.updatePlacementContext(ctx);
		if (ctx == null || !ctx.canPlace()) {
			return;
		}
		BlockPos pos = ctx.getClickedPos();
		if (!s.box.contains(pos.getX(), pos.getY(), pos.getZ())) {
			VireMode.hint(mc, "Outside the Vire region");
			return;
		}
		BlockState state = ((BlockItemInvoker) item).vire$getPlacementState(ctx);
		if (state == null) {
			return;
		}
		record(s, pos, state);
		Sandbox.edit(new SandboxWorld.Edit(key(pos), state, true));
		SoundType sound = state.getSoundType();
		s.level.playLocalSound(pos, sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F, false);
	}

	/** Left click: remove the targeted block instantly. */
	public static void breakTarget(Minecraft mc) {
		VireSession s = VireMode.session();
		if (s == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		BlockPos pos = hit.getBlockPos();
		if (!s.box.contains(pos.getX(), pos.getY(), pos.getZ())) {
			VireMode.hint(mc, "Outside the Vire region");
			return;
		}
		BlockState old = s.level.getBlockState(pos);
		if (old.isAir()) {
			return;
		}
		BlockState after = s.level.getFluidState(pos).createLegacyBlock();
		record(s, pos, after);
		Sandbox.edit(new SandboxWorld.Edit(key(pos), after, false));
		SoundType sound = old.getSoundType();
		s.level.playLocalSound(pos, sound.getBreakSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F, false);
	}

	/** Places an exact state (scripts and tests); recorded like a click. */
	public static void placeState(BlockPos pos, BlockState state) {
		VireSession s = VireMode.session();
		if (s != null && s.box.contains(pos.getX(), pos.getY(), pos.getZ())) {
			record(s, pos, state);
			Sandbox.edit(new SandboxWorld.Edit(key(pos), state, true));
		}
	}

	/** Removes the block at {@code pos} (scripts and tests); recorded like a click. */
	public static void breakAt(BlockPos pos) {
		VireSession s = VireMode.session();
		if (s != null && s.box.contains(pos.getX(), pos.getY(), pos.getZ())) {
			BlockState after = s.level.getFluidState(pos).createLegacyBlock();
			record(s, pos, after);
			Sandbox.edit(new SandboxWorld.Edit(key(pos), after, false));
		}
	}

	private static void record(VireSession s, BlockPos pos, BlockState after) {
		s.history.begin();
		s.history.add(key(pos), s.level.getBlockState(pos), after);
		s.history.end();
	}

	/** Middle click: put the targeted block in the virtual hotbar. */
	public static void pick(Minecraft mc) {
		VireSession s = VireMode.session();
		if (s == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		BlockState state = s.level.getBlockState(hit.getBlockPos());
		VirtualHotbar.pick(new ItemStack(state.getBlock().asItem()));
	}

	public static void undo(Minecraft mc) {
		VireSession s = VireMode.session();
		if (s == null) {
			return;
		}
		if (s.history.undo() == null) {
			VireMode.hint(mc, "Nothing to undo");
			return;
		}
		reload(s);
		VireMode.hint(mc, "Undo (" + s.history.undoSize() + " left), circuit reset");
	}

	public static void redo(Minecraft mc) {
		VireSession s = VireMode.session();
		if (s == null) {
			return;
		}
		if (s.history.redo() == null) {
			VireMode.hint(mc, "Nothing to redo");
			return;
		}
		reload(s);
		VireMode.hint(mc, "Redo, circuit reset");
	}

	/** Reset button: rebuild the sandbox from the snapshot plus all edits, paused (PLAN.md §7). */
	public static void reset(Minecraft mc) {
		VireSession s = VireMode.session();
		if (s != null) {
			reload(s);
			VireMode.hint(mc, "Circuit reset (paused)");
		}
	}

	/** Loads snapshot + current edits into the sandbox. */
	public static void reload(VireSession s) {
		s.generation = Sandbox.load(s.box, s.snapshot, s.snapshotBlockEntities, edits(s.history));
	}

	public static List<SandboxWorld.Edit> edits(EditHistory<BlockState> history) {
		List<SandboxWorld.Edit> out = new ArrayList<>();
		for (EditHistory.Step<BlockState> step : history.steps()) {
			for (EditHistory.Change<BlockState> c : step.changes()) {
				out.add(new SandboxWorld.Edit(c.key(), c.after(), !c.after().isAir() && c.after().getFluidState().isEmpty()));
			}
		}
		return out;
	}

	// ---- server traffic while active --------------------------------------------------------------

	/** The server resent a chunk that overlaps the region: put the virtual blocks back on top. */
	public static void onChunkReloaded(int chunkX, int chunkZ) {
		VireSession s = VireMode.session();
		if (s == null || !s.box.overlapsChunk(chunkX, chunkZ)) {
			return;
		}
		Map<Long, BlockState> reapply = s.shadow.onChunkReloaded(chunkX, chunkZ, k -> s.level.getBlockState(pos(k)));
		reapply.forEach((k, state) -> s.level.setBlock(pos(k), state, RAW));
		if (!reapply.isEmpty()) {
			VireClient.LOG.info("[Vire] Chunk {},{} resent; re-applied {} virtual blocks", chunkX, chunkZ, reapply.size());
		}
	}

	// ---- exit -------------------------------------------------------------------------------------

	/** Writes the real states back. Vire Mode is already off when this runs. */
	public static int restore(Minecraft mc, VireSession s) {
		int n = 0;
		for (Map.Entry<Long, BlockState> e : s.shadow.restoreList().entrySet()) {
			BlockPos pos = pos(e.getKey());
			if (!s.level.isLoaded(pos)) {
				continue; // the chunk comes back from the server with real data anyway
			}
			s.level.setBlock(pos, e.getValue(), RAW);
			CompoundTag tag = s.savedBlockEntities.get(e.getKey());
			BlockEntity be = s.level.getBlockEntity(pos);
			if (tag != null && be != null && sameType(be, tag)) {
				try (ProblemReporter.ScopedCollector reporter = new ProblemReporter.ScopedCollector(be.problemPath(), VireClient.LOG)) {
					be.loadWithComponents(TagValueInput.create(reporter, s.level.registryAccess(), tag));
				}
			}
			n++;
		}
		if (mc.getConnection() != null) {
			for (ClientboundBlockEntityDataPacket p : s.shadow.pendingBlockEntityData()) {
				mc.getConnection().handleBlockEntityData(p);
			}
		}
		return n;
	}

	/** The saved tag belongs to this kind of block entity (the server may have changed the block meanwhile). */
	private static boolean sameType(BlockEntity be, CompoundTag tag) {
		var id = net.minecraft.core.registries.BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType());
		return id != null && id.toString().equals(tag.getStringOr("id", ""));
	}

	public static long key(BlockPos pos) {
		return PosKey.pack(pos.getX(), pos.getY(), pos.getZ());
	}

	public static BlockPos pos(long key) {
		return new BlockPos(PosKey.x(key), PosKey.y(key), PosKey.z(key));
	}
}
