package dev.vire.client.design;

import dev.vire.client.VireClient;
import dev.vire.client.mode.VireMode;
import dev.vire.core.design.Bom;
import dev.vire.core.design.Design;
import dev.vire.core.design.Normalizer;
import dev.vire.core.design.StateRef;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The active design shown in the real world after Vire Mode (PLAN.md §9). Every client tick it
 * compares each design position with the real block (normalized, §7): built positions disappear,
 * missing blocks show as see-through blocks, a block in the way gets a red box, the right block in the
 * wrong state a yellow box with what is expected. When all positions match, a toast says so and the
 * ghost goes away.
 */
public final class GhostLayer {
	public enum Status { UNKNOWN, DONE, MISSING, BLOCKING, WRONG }

	/** One design position. Mutable: the last compared real state and its result are cached. */
	public static final class Ghost {
		public final BlockPos pos;
		public final StateRef want;
		/** Null for a removal, or when the block does not exist in this version (drawn as a box). */
		public final BlockState target;
		public final boolean removal;
		int[] tints;
		boolean modelled;
		String item;
		BlockState lastLive;
		Status status = Status.UNKNOWN;
		String difference;

		Ghost(Design.Entry e) {
			this.pos = new BlockPos(e.x(), e.y(), e.z());
			this.want = e.wantRef();
			this.removal = want.isAir();
			this.target = removal ? null : Designs.state(e.want());
		}

		public Status status() {
			return status;
		}

		/** What is wrong, e.g. {@code "facing: west expected"}, or null. */
		public String difference() {
			return difference;
		}
	}

	private static final int STROKE_BLOCKING = 0xFFFF3B30;
	private static final int FILL_BLOCKING = 0x55FF2020;
	private static final int STROKE_WRONG = 0xFFFFD020;
	private static final int FILL_WRONG = 0x40FFD020;
	private static final double INSET = 0.03;
	/** Red and yellow boxes surround a real solid block: drawn inside it, the block hides them up close. */
	private static final double OUTSET = 0.02;

	private static Design design;
	private static final List<Ghost> GHOSTS = new ArrayList<>();
	private static List<Ghost> visible = List.of();
	private static int remaining;
	private static int unknown;
	private static Map<String, Integer> needed = Map.of();
	private static Map<String, Integer> have = Map.of();

	private GhostLayer() {
	}

	public static Design design() {
		return design;
	}

	public static List<Ghost> ghosts() {
		return GHOSTS;
	}

	/** Ghosts the block renderer draws this frame. */
	public static List<Ghost> visible() {
		return visible;
	}

	public static int remaining() {
		return remaining;
	}

	/** Items still needed (item id → count) for the blocks left, from the last tick. */
	public static Map<String, Integer> needed() {
		return needed;
	}

	/** The player's inventory (item id → count), from the last tick. */
	public static Map<String, Integer> have() {
		return have;
	}

	/** Shows {@code d} as the ghost (null hides it) and remembers it in the config. */
	public static void show(Design d) {
		design = d;
		GHOSTS.clear();
		visible = List.of();
		GhostBlockRenderer.clearCache();
		if (d != null) {
			for (Design.Entry e : d.entries()) {
				GHOSTS.add(new Ghost(e));
			}
		}
		VireClient.config.activeGhost = d == null ? null : d.name();
		VireClient.save();
	}

	/** On join: bring back the ghost that was active. */
	public static void restoreActive() {
		String name = VireClient.config.activeGhost;
		if (name != null && design == null) {
			Design d = Designs.load(name);
			if (d != null) {
				design = d;
				GHOSTS.clear();
				for (Design.Entry e : d.entries()) {
					GHOSTS.add(new Ghost(e));
				}
			}
		}
	}

	public static void tick(Minecraft mc) {
		ClientLevel level = mc.level;
		if (design == null || level == null || mc.player == null || VireMode.active() || !design.world().equals(Designs.worldId(mc))) {
			visible = List.of();
			return;
		}
		Vec3 eye = mc.player.getEyePosition();
		double range = VireClient.config.ghostRange;
		double max2 = range * range;
		int left = 0;
		int unknownNow = 0;
		List<Ghost> inRange = new ArrayList<>();
		List<StateRef> todo = new ArrayList<>();
		Map<StateRef, String> itemOf = new HashMap<>();
		for (Ghost g : GHOSTS) {
			if (!level.hasChunk(g.pos.getX() >> 4, g.pos.getZ() >> 4)) {
				g.status = Status.UNKNOWN;
				unknownNow++;
				left++;
				if (!g.removal) {
					todo.add(g.want);
				}
				continue;
			}
			BlockState live = level.getBlockState(g.pos);
			if (live != g.lastLive) {
				g.lastLive = live;
				compare(g, live);
			}
			if (g.status == Status.DONE) {
				continue;
			}
			left++;
			if (!g.removal && g.status != Status.WRONG) {
				todo.add(g.want);
				if (g.item != null) {
					itemOf.put(g.want, g.item);
				}
			}
			if (g.pos.distToCenterSqr(eye) <= max2) {
				inRange.add(g);
			}
		}
		remaining = left;
		unknown = unknownNow;
		if (inRange.size() > VireClient.config.maxGhostsDrawn) {
			inRange.sort(Comparator.comparingDouble(g -> g.pos.distToCenterSqr(eye)));
			inRange = inRange.subList(0, VireClient.config.maxGhostsDrawn);
		}
		List<Ghost> blocks = new ArrayList<>();
		for (Ghost g : inRange) {
			switch (g.status) {
				case BLOCKING -> {
					Gizmos.cuboid(outset(g.pos), GizmoStyle.strokeAndFill(STROKE_BLOCKING, 3.0f, FILL_BLOCKING));
					if (!g.removal && g.difference != null && g.pos.distToCenterSqr(eye) < 256) {
						label(g, g.difference, STROKE_BLOCKING);
					}
				}
				case WRONG -> {
					Gizmos.cuboid(outset(g.pos), GizmoStyle.strokeAndFill(STROKE_WRONG, 3.0f, FILL_WRONG));
					if (g.difference != null && g.pos.distToCenterSqr(eye) < 256) {
						label(g, g.difference, STROKE_WRONG);
					}
				}
				case MISSING -> {
					if (g.modelled) {
						blocks.add(g);
					} else {
						Gizmos.cuboid(inset(g.pos), GizmoStyle.strokeAndFill(0xE060C0FF, 1.0f, 0x5060C0FF));
					}
				}
				default -> {
				}
			}
		}
		visible = blocks;

		if (mc.player.tickCount % 10 == 0) {
			needed = Bom.count(todo, s -> itemOf.containsKey(s) ? itemOf.get(s) : itemId(s));
			have = inventory(mc);
		}
		if (left == 0 && unknownNow == 0) {
			complete(mc);
		}
	}

	private static void compare(Ghost g, BlockState live) {
		StateRef liveRef = Designs.ref(live);
		if (Normalizer.matches(g.want, liveRef)) {
			g.status = Status.DONE;
			g.difference = null;
			return;
		}
		g.difference = Normalizer.difference(g.want, liveRef);
		if (g.removal || !live.isAir() && !live.canBeReplaced() && !liveRef.block().equals(g.want.block())) {
			g.status = Status.BLOCKING; // must go first
		} else if (liveRef.block().equals(g.want.block())) {
			g.status = Status.WRONG;
		} else {
			g.status = Status.MISSING;
		}
		if (g.status == Status.MISSING && g.tints == null && g.target != null) {
			g.modelled = GhostBlockRenderer.hasModel(g.target);
			g.tints = tints(Minecraft.getInstance().level, g.target, g.pos);
		}
		if (g.item == null && !g.removal) {
			g.item = itemId(g.want);
		}
	}

	private static String itemId(StateRef s) {
		BlockState st = Designs.state(s.toString());
		if (st == null) {
			return s.block(); // unknown here: show the block id
		}
		Item item = st.getBlock().asItem();
		return item == Items.AIR ? null : BuiltInRegistries.ITEM.getKey(item).toString();
	}

	private static Map<String, Integer> inventory(Minecraft mc) {
		Map<String, Integer> out = new HashMap<>();
		var inv = mc.player.getInventory();
		for (int i = 0; i < inv.getContainerSize(); i++) {
			ItemStack st = inv.getItem(i);
			if (!st.isEmpty()) {
				out.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), st.getCount(), Integer::sum);
			}
		}
		return out;
	}

	private static void complete(Minecraft mc) {
		String name = design.name();
		VireClient.LOG.info("[Vire] Design {} complete", name);
		SystemToast.add(mc.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION, Component.literal("Design complete"),
				Component.literal(name + ": every block is built"));
		show(null);
	}

	/** BOM lines sorted by count: item id → {needed, have}. */
	public static Map<String, int[]> bom() {
		Map<String, int[]> out = new LinkedHashMap<>();
		needed.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
				.forEach(e -> out.put(e.getKey(), new int[] {e.getValue(), have.getOrDefault(e.getKey(), 0)}));
		return out;
	}

	private static void label(Ghost g, String text, int color) {
		Gizmos.billboardText(text, new Vec3(g.pos.getX() + 0.5, g.pos.getY() + 1.3, g.pos.getZ() + 0.5),
				TextGizmo.Style.forColorAndCentered(color).withScale(0.22f));
	}

	private static AABB outset(BlockPos p) {
		return new AABB(p).inflate(OUTSET);
	}

	private static AABB inset(BlockPos p) {
		return new AABB(p.getX() + INSET, p.getY() + INSET, p.getZ() + INSET, p.getX() + 1 - INSET, p.getY() + 1 - INSET, p.getZ() + 1 - INSET);
	}

	/** Grass, leaves, water and similar blocks are coloured by biome (from Ghost Mason). */
	private static int[] tints(ClientLevel level, BlockState state, BlockPos pos) {
		List<BlockTintSource> sources = Minecraft.getInstance().getBlockColors().getTintSources(state);
		int[] out = new int[sources.size()];
		for (int i = 0; i < out.length; i++) {
			BlockTintSource s = sources.get(i);
			int color = s == null ? 0xFFFFFF : s.colorInWorld(state, level, pos);
			out[i] = 0xFF000000 | (color & 0xFFFFFF);
		}
		return out;
	}
}
