package dev.vire.client.interact;

import dev.vire.client.VireClient;
import dev.vire.client.mixin.BlockItemInvoker;
import dev.vire.client.mode.VireMode;
import dev.vire.client.mode.VireSession;
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
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Virtual edits in the client level (M1: straight into {@code ClientLevel}, no simulation).
 *
 * <p>While {@link #recording} is on, every {@code ClientLevel.setBlock} (including neighbor shape
 * updates and the other half of doors and beds) is reported by {@code ClientLevelMixin} and captured
 * in the session's {@code ShadowRegion} and, when a step is open, in the edit history.
 */
public final class VireWorld {
	/** Flags for undo/redo/restore: tell the renderer, no neighbor shape updates (they are recorded already). */
	private static final int RAW = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_IMMEDIATE;

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
		if (s.history.isOpen()) {
			s.history.add(key, before, after);
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
		edit(s, () -> {
			if (s.level.setBlock(pos, state, Block.UPDATE_ALL_IMMEDIATE)) {
				BlockState placed = s.level.getBlockState(pos);
				placed.getBlock().setPlacedBy(s.level, pos, placed, s.player, stack);
			}
		});
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
		edit(s, () -> s.level.setBlock(pos, s.level.getFluidState(pos).createLegacyBlock(), Block.UPDATE_ALL_IMMEDIATE));
		SoundType sound = old.getSoundType();
		s.level.playLocalSound(pos, sound.getBreakSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F, false);
	}

	/** Middle click: put the targeted block in the virtual hotbar. */
	public static void pick(Minecraft mc) {
		VireSession s = VireMode.session();
		if (s == null || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) {
			return;
		}
		BlockState state = s.level.getBlockState(hit.getBlockPos());
		ItemStack stack = new ItemStack(state.getBlock().asItem());
		VirtualHotbar.pick(stack);
	}

	public static void undo(Minecraft mc) {
		VireSession s = VireMode.session();
		if (s == null) {
			return;
		}
		EditHistory.Step<BlockState> step = s.history.undo();
		if (step == null) {
			VireMode.hint(mc, "Nothing to undo");
			return;
		}
		apply(s, step.inverse());
		VireMode.hint(mc, "Undo (" + s.history.undoSize() + " left)");
	}

	public static void redo(Minecraft mc) {
		VireSession s = VireMode.session();
		if (s == null) {
			return;
		}
		EditHistory.Step<BlockState> step = s.history.redo();
		if (step == null) {
			VireMode.hint(mc, "Nothing to redo");
			return;
		}
		apply(s, step.changes());
		VireMode.hint(mc, "Redo");
	}

	private static void apply(VireSession s, List<EditHistory.Change<BlockState>> changes) {
		recording = true;
		try {
			for (EditHistory.Change<BlockState> c : changes) {
				s.level.setBlock(pos(c.key()), c.after(), RAW);
			}
		} finally {
			resetRecording();
		}
	}

	private static void edit(VireSession s, Runnable action) {
		s.history.begin();
		recording = true;
		try {
			action.run();
		} catch (RuntimeException e) {
			VireClient.LOG.warn("[Vire] Virtual edit failed", e);
		} finally {
			resetRecording();
			s.history.end();
		}
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
