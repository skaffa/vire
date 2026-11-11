package dev.vire.client.overlay;

import dev.vire.client.VireClient;
import dev.vire.client.mode.VireMode;
import dev.vire.client.mode.VireSession;
import dev.vire.core.region.Box;
import dev.vire.core.region.PosKey;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Signal labels, flow arrows and the delay tracker marks inside the Vire region (PLAN.md §8), submitted
 * as vanilla gizmos every client tick. The scan and label layout are adapted from Gauge's overlay
 * (copied, not a dependency): only chunk sections whose palette may hold a component are read, only
 * blocks in front of the camera and within {@code labelRange}, nearest first.
 */
public final class SignalOverlay {
	private static final float TEXT = 0.26f;
	private static final float SMALL = 0.2f;
	public static final int IN_COLOR = 0xFF40FF60;
	public static final int OUT_COLOR = 0xFFFFA020;

	private static long[] found = new long[512];
	private static long[] positions = new long[512];

	private SignalOverlay() {
	}

	public static void tick() {
		VireSession s = VireMode.session();
		if (s == null || !s.loaded) {
			return;
		}
		ClientLevel level = s.level;
		Map<Long, Integer> comps = s.comparators;
		Vec3 eye = s.camera.getEyePosition();
		Vec3 look = s.camera.getViewVector(1.0f);
		int n = scan(level, s.box, eye, look, VireClient.config.labelRange);
		if (n > VireClient.config.maxLabels) {
			Arrays.sort(found, 0, n);
			n = VireClient.config.maxLabels;
		}
		Set<Long> powered = new HashSet<>();
		boolean arrows = VireClient.config.showArrows;
		for (int k = 0; k < n; k++) {
			long packed = positions[(int) found[k]];
			BlockPos pos = BlockPos.of(packed);
			BlockState st = level.getBlockState(pos);
			String label = Signals.label(st, pos, comps);
			if (label != null) {
				boolean dust = st.getBlock() instanceof RedstoneWireBlock;
				int sig = Signals.level(level, pos, st, comps);
				var shape = st.getShape(level, pos);
				double y = shape.isEmpty() ? 0.3 : Math.min(1.0, shape.max(Direction.Axis.Y)) + 0.25;
				Gizmos.billboardText(label, new Vec3(pos.getX() + 0.5, pos.getY() + y, pos.getZ() + 0.5),
						TextGizmo.Style.forColorAndCentered(Signals.color(sig)).withScale(dust ? SMALL : TEXT));
			}
			if (arrows) {
				Direction out = Signals.output(st);
				if (out != null) {
					arrow(pos, out, Signals.active(st, pos, comps) ? 0xE0FF5050 : 0xB0C0C0C0);
				}
			}
			if (st.isSignalSource()) {
				boolean dust = st.getBlock() instanceof RedstoneWireBlock;
				for (Direction d : Direction.values()) {
					// the block under every piece of dust is powered too; labelling them all is noise
					if (!(dust && d == Direction.DOWN)) {
						poweredBlock(level, s.box, pos.relative(d), powered, comps);
					}
				}
			}
		}
		mark(s.markIn, "IN", IN_COLOR);
		mark(s.markOut, "OUT", OUT_COLOR);
	}

	private static void mark(Long key, String text, int color) {
		if (key == null) {
			return;
		}
		BlockPos p = BlockPos.containing(PosKey.x(key), PosKey.y(key), PosKey.z(key));
		Gizmos.cuboid(new AABB(p).inflate(0.03), GizmoStyle.stroke(color, 3.0f));
		Gizmos.billboardText(text, new Vec3(p.getX() + 0.5, p.getY() + 1.55, p.getZ() + 0.5),
				TextGizmo.Style.forColorAndCentered(color).withScale(TEXT)).setAlwaysOnTop();
	}

	/** Arrow from just inside the block out through the face it outputs to (as in Gauge). */
	private static void arrow(BlockPos pos, Direction out, int color) {
		Vec3 c = Vec3.atCenterOf(pos);
		Vec3 u = out.getUnitVec3();
		if (out.getAxis() != Direction.Axis.Y) {
			c = c.add(0, -0.3, 0); // keep it low so it does not cross the label
		}
		Gizmos.arrow(c.add(u.scale(0.1)), c.add(u.scale(0.85)), color, 2.0f);
	}

	/** S = strongly powered (powers dust beside it), W = weakly powered. */
	private static void poweredBlock(ClientLevel level, Box box, BlockPos pos, Set<Long> done, Map<Long, Integer> comps) {
		if (!box.contains(pos.getX(), pos.getY(), pos.getZ()) || !done.add(pos.asLong())) {
			return;
		}
		BlockState s = level.getBlockState(pos);
		if (s.isAir() || Signals.interesting(s) || !s.isRedstoneConductor(level, pos)) {
			return;
		}
		int[] r = Signals.received(level, pos, comps);
		if (r[0] <= 0) {
			return;
		}
		String label = r[1] > 0 ? "S" + r[1] : "W" + r[0];
		Gizmos.billboardText(label, new Vec3(pos.getX() + 0.5, pos.getY() + 1.15, pos.getZ() + 0.5),
				TextGizmo.Style.forColorAndCentered(r[1] > 0 ? 0xFFFFA040 : 0xFFD0A070).withScale(SMALL));
	}

	/** Components in the box, within the radius and in front of the camera, as (distance, index) pairs. */
	private static int scan(ClientLevel level, Box box, Vec3 eye, Vec3 look, int radius) {
		int n = 0;
		double r2 = (double) radius * radius;
		int ex = (int) Math.floor(eye.x);
		int ey = (int) Math.floor(eye.y);
		int ez = (int) Math.floor(eye.z);
		int minX = Math.max(box.minX(), ex - radius);
		int maxX = Math.min(box.maxX(), ex + radius);
		int minY = Math.max(box.minY(), ey - radius);
		int maxY = Math.min(box.maxY(), ey + radius);
		int minZ = Math.max(box.minZ(), ez - radius);
		int maxZ = Math.min(box.maxZ(), ez + radius);
		if (minX > maxX || minY > maxY || minZ > maxZ) {
			return 0;
		}
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		for (int cx = minX >> 4; cx <= maxX >> 4; cx++) {
			for (int cz = minZ >> 4; cz <= maxZ >> 4; cz++) {
				LevelChunk chunk = (LevelChunk) level.getChunkSource().getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) {
					continue;
				}
				for (int sy = minY >> 4; sy <= maxY >> 4; sy++) {
					int idx = level.getSectionIndexFromSectionY(sy);
					if (idx < 0 || idx >= chunk.getSections().length) {
						continue;
					}
					LevelChunkSection section = chunk.getSection(idx);
					if (section.hasOnlyAir() || !section.maybeHas(Signals::interesting)) {
						continue;
					}
					int x0 = Math.max(cx << 4, minX);
					int x1 = Math.min((cx << 4) + 15, maxX);
					int y0 = Math.max(sy << 4, minY);
					int y1 = Math.min((sy << 4) + 15, maxY);
					int z0 = Math.max(cz << 4, minZ);
					int z1 = Math.min((cz << 4) + 15, maxZ);
					for (int y = y0; y <= y1; y++) {
						for (int z = z0; z <= z1; z++) {
							for (int x = x0; x <= x1; x++) {
								double dx = x + 0.5 - eye.x;
								double dy = y + 0.5 - eye.y;
								double dz = z + 0.5 - eye.z;
								double d2 = dx * dx + dy * dy + dz * dz;
								if (d2 > r2) {
									continue;
								}
								if (d2 > 4 && dx * look.x + dy * look.y + dz * look.z < -1.0) {
									continue; // behind the camera
								}
								BlockState s = section.getBlockState(x & 15, y & 15, z & 15);
								if (!Signals.interesting(s)) {
									continue;
								}
								if (n == found.length) {
									found = Arrays.copyOf(found, n * 2);
									positions = Arrays.copyOf(positions, n * 2);
								}
								pos.set(x, y, z);
								positions[n] = pos.asLong();
								found[n] = ((long) Float.floatToIntBits((float) d2) << 32) | n;
								n++;
							}
						}
					}
				}
			}
		}
		return n;
	}
}
